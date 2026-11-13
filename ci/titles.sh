#!/bin/sh
# Checks a pull request's title, and for a pull request into dev each of its commits, against Conventional Commits:
#   <type>(<optional scope>)<optional !>: <what changed>
# Run by .github/workflows/checks.yml with PR_TITLE, PR_NUMBER, BASE_REF and GH_TOKEN set. To try it by hand:
#   PR_TITLE='feat: tooltips' BASE_REF=main sh ci/titles.sh
set -eu

TYPES='feat|fix|perf|docs|style|refactor|test|build|ci|chore|revert'
PATTERN="^($TYPES)(\([a-z0-9._/-]+\))?!?: [^ ].*"
bad=0

check() { # $1: what it is, $2: the line
    if ! printf '%s\n' "$2" | grep -Eq "$PATTERN"; then
        echo "::error::$1 is not a Conventional Commits title: $2"
        bad=1
    fi
}

check 'The pull request title' "$PR_TITLE"

# The commits of a promotion were checked on their way into dev.
if [ "${BASE_REF:-}" = dev ] && [ -n "${PR_NUMBER:-}" ]; then
    gh api "repos/$GITHUB_REPOSITORY/pulls/$PR_NUMBER/commits" --paginate \
        --jq '.[] | select(.parents | length < 2) | .commit.message | split("\n")[0]' > commits.txt
    while IFS= read -r subject; do
        check 'A commit' "$subject"
    done < commits.txt
    rm -f commits.txt
fi

if [ "$bad" -ne 0 ]; then
    echo "Use one of: $(echo "$TYPES" | tr '|' ' '). For example: fix(render): borders keep their radius when clipped"
    exit 1
fi
echo 'Titles are fine.'
