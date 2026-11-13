#!/bin/sh
# What the server-bench gate runs inside its sandboxed unit, as the unprivileged CI user: no GitHub token, no /root,
# no sudo. The deployed poller (server_bench.py) starts it twice per commit with systemd-run, holding the box's
# build lock round the first and its timing lock round the second:
#
#   bench_unit.sh build <sha>         check <sha> out in $MCGUI_CI_WORK/repo and build it as bench/bench.py does
#   bench_unit.sh bench <sha> <out>   run bench/bench.py on that build, writing its JSON to <out>
#
# Everything it needs comes from the environment the poller passes (systemd-run -E), never from a file in /root.
set -eu

: "${MCGUI_CI_WORK:?}" "${MCGUI_CI_REPO_URL:?}" "${JAVA_HOME:?}"
CO="$MCGUI_CI_WORK/repo"
# bench.py's --work (server copies, classpath.txt, a dead server's console log): outside the checkout, so the next
# build's `git clean` cannot take the evidence of a failed run with it.
BW="$MCGUI_CI_WORK/bench"
phase=$1
sha=$2
echo "== $phase $sha at $(date -u +%FT%TZ) as $(id -un) on $(uname -n)"

case $phase in
build)
  if [ ! -d "$CO/.git" ]; then
    git clone --quiet --no-checkout "$MCGUI_CI_REPO_URL" "$CO"
  fi
  cd "$CO"
  git fetch --quiet --no-tags origin "$sha"
  git checkout --quiet --force --detach "$sha"
  git clean -ffdxq
  rm -rf "$BW"
  mkdir -p "$BW"
  # Online first: bench.py builds with --offline, so the Gradle cache must already hold every dependency.
  sh gradlew --no-daemon -q shadowJar benchClasspath > /dev/null
  (cd examples/arcade && sh ../../gradlew --no-daemon -q jar)
  # Then bench.py's own build step, so the jars and classpath are exactly what `bench.py` would make itself;
  # `bench.py --no-build` in the next phase reads them back.
  BW="$BW" python3 - <<'EOF'
import os, sys
sys.path.insert(0, 'bench')
import bench


class A:
    gradle_java = os.environ['JAVA_HOME']
    build_lock = ''


jar, arcade, cp = bench.build(A)
open(os.path.join(os.environ['BW'], 'classpath.txt'), 'w').write(cp)
print('built', jar, arcade)
EOF
  ;;
bench)
  out=$3
  mkdir -p "$(dirname "$out")"
  cd "$CO"
  head=$(git rev-parse HEAD)
  if [ "$head" != "$sha" ]; then
    echo "the checkout is at $head, not $sha: the build phase did not run for it" >&2
    exit 3
  fi
  rc=0
  # shellcheck disable=SC2086 # MCGUI_CI_BENCH_ARGS is a list of words on purpose
  python3 bench/bench.py --no-build --work "$BW" ${MCGUI_CI_BENCH_ARGS:-} --ports "${MCGUI_CI_PORTS:-25990}" \
    --tag "ci-$(echo "$sha" | cut -c1-7)" --out "$out" || rc=$?
  # A server that died: its own last words, into this run's log.
  for f in "$BW"/died-*.log; do
    [ -f "$f" ] || continue
    echo "== $f (last 150 lines)"
    tail -n 150 "$f"
  done
  [ "$rc" = 0 ] || exit "$rc"
  ;;
*)
  echo "usage: bench_unit.sh build <sha> | bench <sha> <out.json>" >&2
  exit 2
  ;;
esac
echo "== $phase done at $(date -u +%FT%TZ)"
