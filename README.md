# Meno

A **universal, self-hostable auto-researcher.** Point it at *your* notes (any blog/git
repo); it grows a densely `[[wikilinked]]` Zettelkasten research wiki from them —
entirely by AI, versioned in git, published by [Quartz](https://quartz.jzhao.xyz).
**[smith.wiki](https://smith.wiki) is one instance** — Andy Smith's public deployment.
Run Meno on your own notes and you get your own.

> Named after Plato's *Meno* — learning as *anamnesis*, drawing out knowledge already
> latent in you. Meno doesn't write your blog (that stays your hand-written thinking);
> it *researches* it.

## The loop, in one breath

Four steps, with **you** as the single approval gate:

1. **Plan** — Meno decides *what to research* and files each unit of work as a GitHub
   issue on a Projects board (the Backlog).
2. **Approve** — you drag a card to **Todo** (the only required human step; an optional
   auto-approve drip can do it when idle).
3. **Research & write** — an autonomous worker researches the task and writes one
   atomic, cited card.
4. **Review** — it opens a PR that closes the issue; you review and merge; the wiki
   rebuilds and publishes.

Public-in / public-out, all recorded in git — auditable and revertible.

## Docs

- **[`docs/design.md`](docs/design.md)** — **the target design** (Zulip working layer ·
  discourse-graph KB · stateless engine). Direction, not current state.
- **[`docs/process.md`](docs/process.md)** — **the research process** (what it reads,
  how it decides, how you approve, how a page is written and reviewed, the methods).
  Start here.
- [`docs/zeno.md`](docs/zeno.md) — the execution engine / runtime (how agents are
  spawned and sandboxed). General pattern, parked.
- [`docs/auth.md`](docs/auth.md) — the two-token auth model.

## Run your own instance

```sh
nix develop                                  # clojure + jdk + secretspec
secretspec run -- clojure -M:mcp             # start Meno
```

Point `:blog` at your notes and `:wiki`/`:github`/`:projects` at your output repo and
board in [`config.edn`](config.edn); declare secrets in
[`secretspec.toml`](secretspec.toml). Tests (deterministic, no network):
`clojure -M:test`.
