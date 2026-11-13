#!/bin/sh
# Installs (or redeploys) MC GUI's bench-box CI on this machine, from the checkout this script is in. Run as root:
#
#   sh ci/box/deploy/install.sh              copy the scripts, (re)install the units, enable both timers
#   sh ci/box/deploy/install.sh --no-timers  the same, timers left as they are (for a first, hand-driven run)
#
# It is a copy: what runs on the box is /opt/mcgui-ci, root-owned, and nothing there ever updates itself from the
# repository. To deploy a change to ci/box/, check the reviewed commit out and run this again.
#
# Needs /etc/mcgui-ci.env (mode 600; start from ci/box/deploy/mcgui-ci.env.example). Makes, if missing:
#   user mcgui-ci           unprivileged system user the benches run as (no login shell, no sudo, no /root)
#   /opt/mcgui-ci/          the pollers, bench_unit.sh, gate.sh, gates.json (root:root, read-only to others)
#   /var/lib/mcgui-ci/      results/ logs/ state/ tagwatch/ (root), server/ probe/ (root, read-only to the user),
#                           work/ home/ (the CI user's: checkout, bench output, Gradle cache)
#   /etc/systemd/system/    atlas-mcgui.slice, mcgui-server-bench.{service,timer}, mcgui-tag-watch.{service,timer}
set -eu

HERE=$(cd "$(dirname "$0")" && pwd)
BOX=$(dirname "$HERE")
ENVFILE=/etc/mcgui-ci.env
USER_NAME=mcgui-ci
OPT=/opt/mcgui-ci
VAR=/var/lib/mcgui-ci
TIMERS=yes
[ "${1:-}" = "--no-timers" ] && TIMERS=no

[ "$(id -u)" = 0 ] || { echo "run as root" >&2; exit 1; }
[ -f "$ENVFILE" ] || { echo "no $ENVFILE: copy $HERE/mcgui-ci.env.example there (mode 600) and fill it in" >&2; exit 1; }
chown root:root "$ENVFILE"
chmod 600 "$ENVFILE"
set -a
# shellcheck disable=SC1090
. "$ENVFILE"
set +a

if ! id "$USER_NAME" >/dev/null 2>&1; then
  useradd --system --user-group --home-dir "$VAR/home" --no-create-home --shell /usr/sbin/nologin \
    --comment "MC GUI bench box CI (unprivileged)" "$USER_NAME"
fi

install -d -o root -g root -m 755 "$OPT" "$VAR" "$VAR/results" "$VAR/logs" "$VAR/probe"
install -d -o root -g root -m 700 "$VAR/state" "$VAR/tagwatch"
install -d -o "$USER_NAME" -g "$USER_NAME" -m 755 "$VAR/work" "$VAR/home" "$VAR/home/.gradle"
# A single-use Gradle per build: no daemon left holding memory in the bench unit.
printf 'org.gradle.daemon=false\norg.gradle.vfs.watch=false\n' > "$VAR/home/.gradle/gradle.properties"
chown "$USER_NAME:$USER_NAME" "$VAR/home/.gradle/gradle.properties"

# The private Probe plugin: copied in, so the CI user never needs to read where it is built.
if [ -n "${MCGUI_CI_PROBE_SRC:-}" ]; then
  install -o root -g root -m 644 "$MCGUI_CI_PROBE_SRC" "$VAR/probe/probe-plugin.jar"
fi
[ -f "$VAR/probe/probe-plugin.jar" ] || { echo "no Probe jar: set MCGUI_CI_PROBE_SRC" >&2; exit 1; }

# The bench server: a Paper folder that has run once, root-owned so the code under test can read but not change it
# (bench.py copies it for each run). Copied once; delete $VAR/server to take a new one.
if [ ! -f "$VAR/server/paper.jar" ]; then
  [ -n "${MCGUI_CI_SERVER_SRC:-}" ] || { echo "no bench server: set MCGUI_CI_SERVER_SRC" >&2; exit 1; }
  rsync -a --delete --exclude plugins/ --exclude logs/ --exclude 'world/playerdata/' --exclude firstboot.log \
    "$MCGUI_CI_SERVER_SRC/" "$VAR/server/"
  mkdir -p "$VAR/server/plugins"
  echo eula=true > "$VAR/server/eula.txt"
  chown -R root:root "$VAR/server"
  chmod -R u+rwX,go+rX,go-w "$VAR/server"
fi

for f in server_bench.py tag_watch.py bench_unit.sh gate.sh; do
  install -o root -g root -m 755 "$BOX/$f" "$OPT/$f"
done
install -o root -g root -m 644 "$BOX/gates.json" "$OPT/gates.json"
git -C "$BOX" rev-parse HEAD > "$OPT/DEPLOYED_FROM" 2>/dev/null || echo unknown > "$OPT/DEPLOYED_FROM"
chmod 644 "$OPT/DEPLOYED_FROM"

for u in atlas-mcgui.slice mcgui-server-bench.service mcgui-server-bench.timer mcgui-tag-watch.service mcgui-tag-watch.timer; do
  install -o root -g root -m 644 "$HERE/$u" "/etc/systemd/system/$u"
done
systemctl daemon-reload
if [ "$TIMERS" = yes ]; then
  systemctl enable --now mcgui-server-bench.timer mcgui-tag-watch.timer
fi
echo "deployed $(cat "$OPT/DEPLOYED_FROM") to $OPT"
systemctl list-timers 'mcgui-*' --no-pager
