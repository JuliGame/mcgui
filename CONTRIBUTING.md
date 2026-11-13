# Contributing to MC GUI

How a change gets from your branch to a release, and what checks it on the way. `NOTES.md` is about the
code itself; this file is about the process.

## The short version

1. Branch from `dev`. Open a pull request into `dev`.
2. The fast checks run. When they are green, it is merged.
3. `dev` is promoted into `main` by a pull request of its own, which runs the full regression and
   performance checks.
4. A maintainer tags the release on `main`.

Nobody pushes to `dev` or `main` directly, maintainers and admins included, and no check can be skipped.

## Branches

| Branch | What it is | How it changes |
|---|---|---|
| `main` | What is released. Every commit on it passed everything below. | Only by a pull request from `dev`. |
| `dev` | What the next release will be. Always builds, always passes the tests. | Only by a pull request, from any branch or fork. |
| anything else | Your work. Name it `feat/...`, `fix/...`, `perf/...` and so on. | However you like. |

Pull requests are merged with a merge commit, titled with the pull request's title.

## Titles

Pull request titles, and the commits in a pull request into `dev`, follow
[Conventional Commits](https://www.conventionalcommits.org/en/v1.0.0/):

```
feat: tooltips follow the pointer
fix(render): borders keep their radius when clipped
perf(views): send only the rows a scroll brought in
```

The types are `feat`, `fix`, `perf`, `docs`, `style`, `refactor`, `test`, `build`, `ci`, `chore` and `revert`.
A promotion is titled `chore(release): <version>`.

## What runs on a pull request

| Check | Into `dev` | Into `main` | What it does |
|---|---|---|---|
| `checks` | yes | yes | Builds the plugin, runs the unit tests, builds the example plugins against it, checks the titles, and refuses a pull request into `main` that does not come from `dev`. |
| `perf` | yes | yes | Runs the headless benches on your commit and on the commit it is based on, on one machine in one job, and fails when yours is worse. |
| `regression` | on request | yes | Compares every sample and shipped page pixel for pixel with the base, runs the tests that need a real Paper server, and starts real servers with the plugin installed. Add the label `full-regression` to run it on a pull request into `dev`. |
| `server-bench` | no | yes | The whole-server bench (`bench/bench.py`) with fake players, run on the maintainers' bench machine against the last result for `main`. |
| `cla` | yes | no | Asks a first-time contributor to accept the [contributor licence agreement](CLA.md). |
| `codeql` | yes | yes | Static analysis. Findings appear on the pull request and under the Security tab. |

Run the first of these yourself before you push: `sh gradlew build` (JDK 21).

### When a check fails for a good reason

Sometimes a change is meant to do what a check guards against. No check is skipped, but each has an
honest way through:

- **A page looks different on purpose.** Add the label `render-change`. The comparison still runs and still
  shows the before and after pictures; it stops failing on them.
- **A change is meant to cost something.** The limits of the performance checks are files in this
  repository. Raise the one limit in the same pull request and say why, so the cost is seen and agreed
  rather than waved through.

## Releases

- **Dev builds.** Every merge into `dev` publishes the jar as the pre-release
  [`dev-latest`](https://github.com/resourcepack-ai/mcgui/releases/tag/dev-latest). The plugin reports a
  version like `0.8.6+dev.42.abc1234`. It has passed the fast checks only.
- **Releases.** When `main` holds what should go out, a maintainer sets `version` in `build.gradle` (through
  `dev`, like any change) and, once that is promoted, pushes the tag `v<version>` on `main`. The tag is what
  starts the release: the maintainers' build is told about it, builds that commit and publishes it. A
  version tag is never moved or deleted.

## The contributor licence agreement

MC GUI is AGPL-3.0-or-later with the extra terms in [`NOTICE.md`](NOTICE.md), which include a permission for
closed-source plugins that use it. To keep granting that for the whole project, the maintainers need the
right to license every part of it, so a first pull request from outside is asked to accept
[`CLA.md`](CLA.md) with one comment. You keep the copyright in what you wrote.

## Security

- Do not open a public issue for a vulnerability. Write to support@resourcepack.ai.
- Secrets pushed by mistake are blocked by GitHub's push protection. If one gets through, treat it as
  leaked and replace it.
- Workflows may only use Actions pinned to a full commit, with the version in a comment. Dependabot
  proposes the updates.

## The rules themselves

The branch and tag rules are in [`.github/rulesets/`](.github/rulesets/), the workflows in
[`.github/workflows/`](.github/workflows/). Changing either is a pull request like any other.
