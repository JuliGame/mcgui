# Branch and tag rules

These files are the repository's rulesets as they are set on GitHub (Settings, Rules, Rulesets), kept here so
a change to the rules is a reviewed change like any other. GitHub does not read this folder: after editing a
file, an admin applies it.

```sh
# a new ruleset
gh api -X POST repos/resourcepack-ai/mcgui/rulesets --input .github/rulesets/main.json
# an existing one (ids: gh api repos/resourcepack-ai/mcgui/rulesets)
gh api -X PUT repos/resourcepack-ai/mcgui/rulesets/<id> --input .github/rulesets/main.json
```

| File | Rule |
|---|---|
| `main.json` | `main` changes only by a pull request that passed `checks`, `regression`, `perf` and `server-bench`. No force-push, no deletion, nobody exempt. `checks` refuses any source but `dev`. |
| `dev.json` | `dev` changes only by a pull request that passed `checks`, `regression`, `perf` and `cla`. No force-push, no deletion, nobody exempt. |
| `version-tags-create.json` | Only maintainers and admins make a `v*` tag. |
| `version-tags.json` | A `v*` tag is never moved or deleted. |

`integration_id` 15368 is GitHub Actions: a check of that name from anywhere else does not count.
`server-bench` has none because it is a commit status posted from the maintainers' bench machine.
