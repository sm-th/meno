# Identity provisioning & access delivery

Two generic, machine-agnostic concerns turn [`instance.edn`](../instance.edn) into
running machines with chat identities and secrets. Both are candidates to move into zeno
core; today they live here as `provision` / `deliver`, driven by `boot`.

## The owner / botfather model

Provisioning creates and manages **Zulip bots**, one per machine. But **bots cannot
create bots** — so the account that provisions must be a regular **owner admin account**
(a user). [`botfather`](../src/botfather.clj) bootstraps that account **once**:

```sh
cd ~/.zeno && nix develop github:reflection-dev/zeno -c clojure -M -m botfather
```

It offers two paths:

- **create** — mint a fresh admin account via the API. Needs a self-hosted realm, or a
  realm where your account has `can_create_users` **and** password auth
  (`EmailAuthBackend`) enabled: it `POST /users`, promotes the new user to administrator
  (`role 200`), then `POST /fetch_api_key` to mint its API key. The generated password is
  used only transiently — it is never stored.
- **adopt** — store an account you created by hand: paste its email + API key.

Either way it verifies the account can reach `/bots` and stores `ZULIP_OWNER_EMAIL` +
`ZULIP_OWNER_API_KEY` in secretspec. **Runtime auth is email + API key** (a token); the
password only ever exists inside `create`.

The owner key is the **one bootstrap secret** for identity. Everything else about bot
identity is recomputed on boot.

## Identity provisioning

[`provision/reconcile!`](../src/provision.clj) is generic: given the desired agents and
an identity adapter (owner-cred), for each machine's `:identity` it —

1. **creates** the bot if a bot of that full name doesn't already exist;
2. **reads** its API key from `GET /bots` each boot (this Zulip build returns the bot's
   `:username` = email + `:api_key`, but no user id) — nothing stored; held in memory only;
3. **subscribes** it to its declared `:streams` — **existing streams only**; a missing
   stream is warned and skipped, never created.

It is idempotent: a re-run converges. It returns `{machine-key {:email :api-key}}`.

[`zulip_identity/adapter`](../src/zulip_identity.clj) implements the port
(`list-bots` / `create-bot!` / `regenerate!` / `list-streams` / `subscribe!`) against
the Zulip REST API with the owner's Basic auth. The port shape is deliberately small:
another chat bus can be provisioned by supplying the same five functions.

`boot/provision!` reads the realm + owner creds from the `:zulip` env keys named in
`instance.edn` (`ZULIP_SITE` / `ZULIP_OWNER_EMAIL` / `ZULIP_OWNER_API_KEY`) and
reconciles every machine's identity.

## Access delivery

Machines declare `:accesses` (catalog keys) in `instance.edn`; the `:access-catalog`
maps each to a `{:secret :hosts}`. Secrets themselves are **not** in `instance.edn` —
they live in the store as `<MACHINE>_<SECRET>`.

[`deliver/plan`](../src/deliver.clj) resolves each declared access:

- **lookup** — `resolve-secret` reads `<MACHINE>_<SECRET>` (e.g. `RESEARCHER_GH_TOKEN`),
  falling back to a flat `<SECRET>` for the transition. The machine always sees the
  **canonical name** (`GH_TOKEN`) — the prefix never leaks into machine code.
- if the machine has a provisioned identity, its Zulip key rides along as
  `ZULIP_API_KEY` (bound to the realm host), with `ZULIP_EMAIL` / `ZULIP_SITE` as plain
  env.

The plan is then materialized in one of two shapes:

- **`env-map`** — trusted **host** machine: every value inlined as plain env. The
  publisher (`:sandboxed false`) is delivered this way.
- **`sandbox-spec`** — **sandboxed** agent: plain (non-secret) values as env, but each
  secret is **network-bound** — `msb --secret ENV@HOST`, the value released only toward
  its allowed `:host`, plus an egress allowlist. Values never go on the argv and never
  hit VM disk; the guest sees only an `$MSB_<ENV>` placeholder. msb injects the real
  value even inside git's base64 Basic-auth, so the agent's own `git clone`/`git push`
  work from the box with a network-bound token. The researcher (`:sandboxed true`) is
  delivered this way — `GH_TOKEN@github` (clone + push) and `ANTHROPIC_API_KEY@manifest`
  (LLM) — and does everything inside one microVM; the host only spawns it.

## Where it runs

[`boot/-main`](../src/boot.clj) ties it together: load `instance.edn`, `provision!`
every machine's identity, then deliver accesses and build + run. The `:sandboxed` flag
on each machine in `instance.edn` selects the host-vs-sandbox delivery shape.
