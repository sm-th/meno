# My zeno config

This repo is a **[zeno](https://github.com/reflection-dev/zeno) config** — the thing
zeno *loads and runs*, the way Emacs loads `~/.emacs.d`. zeno is the binary (an
orchestrator core + a launcher); this repo is the init. It is a normal `deps.edn`
project with an `init.clj` entrypoint, published as dotfiles (`<name>.zeno`) and
symlinked as `~/.zeno`.

```sh
nix run github:reflection-dev/zeno
```

That reads `~/.zeno` (this repo), puts its `src` and machine `:deps` on the classpath,
and `load-file`s [`init.clj`](init.clj). `init.clj` runs the instance when the owner
creds are present, else it loads the instance and prints how to bootstrap:

```clojure
(require 'boot)
(if (System/getenv "ZULIP_OWNER_API_KEY")
  (boot/-main)                 ; provision identities, deliver accesses, run
  (…print machines + bootstrap steps…))
```

## What this config wires

Two **machines** (reusable workflow packages — ELPA-style, to be extracted to their own
repos over time) plus the generic glue that gives them chat identities and secrets.

- **RESEARCHER** — an auto-researcher that turns the author's notes into a densely
  `[[wikilinked]]` **discourse-graph** research wiki, entirely by AI, versioned in git.
  Its design is [`docs/design.md`](docs/design.md) / [`docs/process.md`](docs/process.md).
  Each published post is ingested by a **fresh microVM**: the agent (universal
  native tools, broad web egress) researches, commits and pushes to a
  `researcher/<slug>` review branch — all in the box, every secret network-bound
  (nothing real in the guest, nothing on the host). Wired at `src/research.clj`.
- **PUBLISHER** — a new note in the Zulip stream `#blog` becomes an English post on an
  [11ty](https://11ty.dev) site **and** a Telegram repost; both links are replied back
  into the thread, then the thread is resolved. Runs as a trusted host machine. See
  [`docs/publisher.md`](docs/publisher.md).

Two cross-cutting concerns, generic and machine-agnostic (candidates to move into zeno
core), described in [`docs/provisioning.md`](docs/provisioning.md):

- **Identity provisioning** — each machine's `:identity` in [`instance.edn`](instance.edn)
  is reconciled into a **Zulip bot**: created if missing, its API key **read from Zulip
  each boot** (nothing stored in the config), subscribed to its declared streams (existing ones only).
  Bots can't create bots, so the creator is an **owner admin account** bootstrapped once
  by [`botfather`](src/botfather.clj).
- **Access delivery** — [`deliver`](src/deliver.clj) resolves each machine's declared
  accesses to secret values (`<MACHINE>_<SECRET>`, with a flat `<SECRET>` fallback) and
  injects them under **canonical names** (`GH_TOKEN`, …). A trusted host machine gets
  them as plain env; a sandboxed agent gets each secret **network-bound**
  (`--secret ENV@HOST`, value released only toward its allowed host, never on the argv or
  VM disk) plus an egress allowlist.

## Structure

| Path | What it is |
|---|---|
| [`init.clj`](init.clj) | Entrypoint zeno loads. Run when configured, explain when not. |
| [`instance.edn`](instance.edn) | The fleet: machines, their chat identities + accesses, the access catalog, and the Zulip owner/realm env keys. Single source of truth. |
| [`publisher.edn`](publisher.edn) | The publisher machine's structural config (source stream, 11ty repo, Telegram channel). Secrets are not here. |
| [`src/boot.clj`](src/boot.clj) | Instance boot: load `instance.edn`, provision identities, deliver accesses, build + run. Knows which machines exist. |
| [`src/provision.clj`](src/provision.clj) | Generic identity reconcile (create / subscribe / read the bot's key). Machine-agnostic. |
| [`src/deliver.clj`](src/deliver.clj) | Generic access delivery: per-machine secrets under canonical names; `env-map` (host) vs `sandbox-spec` (network-bound). |
| [`src/zulip_identity.clj`](src/zulip_identity.clj) | Owner-cred Zulip REST adapter (list/create bots, regenerate key, list/subscribe streams). |
| [`src/botfather.clj`](src/botfather.clj) | One-time owner bootstrap: `create` a fresh admin account or `adopt` one you made by hand. Stores `ZULIP_OWNER_*` in secretspec. |
| [`src/publisher/`](src/publisher/) | The publisher machine: `translate` · `site` (11ty) · `telegram` · `zulip` (source) · `machine` (pipeline + overridable facades) · `config` · `main` (poll loop). |
| [`src/shared/http.clj`](src/shared/http.clj) | Minimal JSON/form HTTP util (no dependency on `researcher.*`). |
| [`src/research.clj`](src/research.clj) | The **live** researcher: ingest one published post in a fresh microVM (clone → agent → commit → push a review branch), all secrets network-bound. Wired into the publisher's `on-published`. |
| [`src/researcher/`](src/researcher/) | The richer gateway/grant/KB researcher (ingest, KB, recall, corpus, graph) — its own `:run`/`:image` CLI, separate from the live `research.clj` path. |
| [`secretspec.toml`](secretspec.toml) | Secret declarations: owner creds + per-machine accesses + the researcher's own keys. |

## Bootstrap and run

The owner admin account is the **one bootstrap secret**; the bots' keys live in Zulip
(the owner reads them each boot), so nothing else about identity is stored here.

1. **Bootstrap the owner** (once). `botfather` mints or adopts an admin *user* account
   (bots can't create bots) and stores `ZULIP_OWNER_EMAIL` + `ZULIP_OWNER_API_KEY` in
   secretspec:

   ```sh
   cd ~/.zeno && nix develop -c clojure -M -m botfather
   ```

   `create` mints a fresh admin via the API (self-hosted, or a realm where your account
   has `can_create_users` + password auth); `adopt` stores an account you made by hand.

2. **Set the per-machine secrets** you already created (delivered under canonical names):

   ```sh
   secretspec set PUBLISHER_TELEGRAM_BOT_TOKEN …
   secretspec set RESEARCHER_GH_TOKEN …
   secretspec set ZULIP_SITE https://your-realm.zulipchat.com
   ```

3. **Run.** With `ZULIP_OWNER_API_KEY` in the environment, `init.clj` runs
   `boot/-main` — it reconciles every machine's Zulip bot, delivers accesses, and runs
   the publisher pipeline:

   ```sh
   cd ~/.zeno && secretspec run -- nix run github:reflection-dev/zeno
   ```

Config lives on local disk, so config changes need no push — just re-run. Only
zeno-core changes need a push to `reflection-dev/zeno`. `nix develop` here provides the
toolchain (clojure + jdk + git + secretspec) used for `botfather` and for running a
machine's own CLI (e.g. the researcher's `clojure -M:image`).

## Docs

- [`docs/publisher.md`](docs/publisher.md) — the publisher machine.
- [`docs/provisioning.md`](docs/provisioning.md) — identity provisioning, botfather, and
  access delivery.
- [`docs/design.md`](docs/design.md) / [`docs/process.md`](docs/process.md) — the
  **researcher** machine's design and process.
- [`docs/zeno.md`](docs/zeno.md) — the execution-engine pattern (parked; moving to zeno).
- [`docs/auth.md`](docs/auth.md) — the researcher's two-token GitHub model.
