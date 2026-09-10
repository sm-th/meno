# Meno

A **universal, self-hostable auto-researcher.** Point it at *your* notes (any blog/git
repo) and *your* wiki repo; it grows a densely `[[wikilinked]]` Zettelkasten research
wiki from your notes — entirely by AI, versioned in git, published by
[Quartz](https://quartz.jzhao.xyz). **[smith.wiki](https://smith.wiki) is one
instance** — Andy Smith's public deployment. Run Meno on your own notes and you get
your own.

> Named after Plato's *Meno* — learning as *anamnesis*, drawing out knowledge already
> latent in you. Meno doesn't write your blog (that stays your hand-written thinking);
> it *researches* it. Code namespaces are `researcher.*`.

## The idea: two decoupled layers ("zeno")

- **① The process — as Lisp.** The research method is *data plus a granted
  vocabulary*: stages (`researcher.process`), the fns the agent may `eval`
  (`researcher.grant`), the reflex jobs (`researcher.reflect`). The agent has no tool
  menu — its only capability is evaluating Clojure against a deny-by-default image.
  Rewrite the process without touching the engine.
- **② The execution architecture — the engine.** An always-on "living image"
  (`researcher.mcp`) that supervises loops, **spawns `omp` agent sessions**, routes
  their one `eval` tool to a capability-scoped grant, and checkpoints everything
  through **git / PRs / a kanban board**. Domain-agnostic: it executes whatever ①
  defines, for whatever notes ↔ wiki `config.edn` names.

## The loop, in one breath

1. **Planner** decides *what to research* and files each unit as a GitHub issue on a
   Projects board (the Backlog).
2. **You triage** — drag a card to **Todo** to approve it (or let the auto-approve drip
   do it when idle).
3. **Worker** takes one approved issue, researches it, writes a wiki card, opens a PR
   that closes the issue, and auto-merges. Quartz rebuilds the site.

**→ Full walkthrough with diagrams: [`docs/architecture.md`](docs/architecture.md).**
Auth/token model: [`docs/auth.md`](docs/auth.md).

## Run your own instance

```sh
nix develop                                  # clojure + jdk + secretspec
secretspec run -- clojure -M:mcp             # the living image (gateway + nREPL + REPL)
```

Point `:blog` at your notes and `:wiki`/`:github`/`:projects` at your output repo and
board in [`config.edn`](config.edn); declare secrets in
[`secretspec.toml`](secretspec.toml). Tests (deterministic, no network):
`clojure -M:test`.
