# Auth & GitHub tokens

The researcher uses **two** GitHub tokens on the bot account (`agent-smith-wiki`),
because of a non-obvious GitHub limitation.

| Secret | Type | Scope | Used for |
|---|---|---|---|
| `GH_TOKEN` | **fine-grained** PAT | repo `agent-smith-wiki/smith-wiki`: Contents R/W, Issues R/W, Pull requests R/W, Metadata R | all REST ops: issues (task queue), branches, PRs |
| `GH_PROJECTS_TOKEN` | **classic** PAT | **`project` only** (no repo) | Projects v2 **GraphQL** API (the approval board) |

## Why two tokens (the gotcha)

GitHub **fine-grained PATs do NOT expose a Projects permission for USER-owned
Projects v2** — the "Projects" permission appears only for **organization**-owned
projects. Our board is a **user-owned** Project v2, so a fine-grained PAT simply
has no option to grant it (the scope isn't in the list at all).

The only way to authorize user-owned Projects v2 is a **classic PAT with the
`project` scope**. We keep it as a **separate** token with `project` scope **only**
(no `repo`), so this broader classic token cannot touch code — repository writes
stay on the narrow fine-grained `GH_TOKEN`.

- REST (issues / contents / PRs) → `GH_TOKEN` (fine-grained).
- GraphQL (`api.github.com/graphql`, Projects v2) → `GH_PROJECTS_TOKEN` (classic).

Both are declared in `secretspec.toml` and injected from secretspec at launch.
