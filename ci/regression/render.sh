#!/bin/sh
# Renders a checkout's sample pages and shipped pages to PNGs, the same way for any commit.
#
#   ci/regression/render.sh <checkout> <out-dir>
#
# <out-dir>/samples/ gets examples/samples/*.html, <out-dir>/pages/ gets src/main/resources/pages/*.html (each as
# <name>.png, <name>@2x.png and <name>-end.png where a page scrolls). A folder that is not in the checkout (an old
# commit lays its files out differently) is skipped, and says so. Rendering is hermetic (hermetic.init.gradle).
#
# Environment: GRADLE_FLAGS extra flags for gradlew (e.g. --no-daemon); JAVA_HOME as for Gradle.
set -eu

tree=$(cd "$1" && pwd)
out=$2
here=$(cd "$(dirname "$0")" && pwd)
mkdir -p "$out"
out=$(cd "$out" && pwd)

render() {
    label=$1
    dir=$2
    if ! ls "$tree/$dir"/*.html >/dev/null 2>&1; then
        echo "render.sh: $dir has no pages in this checkout, skipped"
        return 0
    fi
    rm -rf "${tree:?}/build/samples" "${out:?}/${label:?}"
    # shellcheck disable=SC2086
    (cd "$tree" && sh gradlew ${GRADLE_FLAGS:-} -q -I "$here/hermetic.init.gradle" renderSamples "-Pdir=$dir")
    mkdir -p "$out/$label"
    cp "$tree"/build/samples/*.png "$out/$label/"
    rm -rf "$tree/build/samples"
    echo "render.sh: $dir -> $label ($(find "$out/$label" -name "*.png" | wc -l) pictures)"
}

render samples examples/samples
render pages src/main/resources/pages
