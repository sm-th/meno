# The publisher machine

A new note in the Zulip stream `#blog` becomes an English post on an
[11ty](https://11ty.dev) site **and** a Telegram repost. Both links are replied back
into the source thread; then the thread is resolved. It only ever sends **replies** —
it never edits the author's original note.

Source and structural config are in [`publisher.edn`](../publisher.edn); secrets come
from the environment (secretspec). The machine ships default adapters and deep-merges
overrides, so a consumer can swap any facade.

## The pipeline

One source post runs [`publisher.machine/publish-one!`](../src/publisher/machine.clj):

1. **translate** — [`translate.clj`](../src/publisher/translate.clj) turns the private
   note into its English version with **one** Anthropic-compatible `/v1/messages` call
   (no tools, no agent), via the Manifest gateway (`ANTHROPIC_BASE_URL` /
   `ANTHROPIC_API_KEY`). It is a *translation, not a rewrite*: carry the author's exact
   thoughts, voice, terseness, and links across into natural English. The prompt never
   names the source language. Output is `{:title :description :body}`.
2. **publish site** — [`site.clj`](../src/publisher/site.clj) writes
   `src/YYYY/Mon/D/<slug>/index.md` (with front matter) into the 11ty clone, commits
   (SSH-signed, identity baked in as `-c` flags), and pushes. The push auto-deploys; the
   returned URL is **day-level**, matching the path.
3. **reply — site** — the source adapter replies into the thread with the site URL plus
   the translated text in a Zulip `spoiler` block.
4. **repost** — [`telegram.clj`](../src/publisher/telegram.clj) sends the body + site URL
   to the channel via the Bot API (`sendMessage`), returning a `t.me` permalink.
5. **reply — Telegram** — a second reply into the thread: the `t.me` URL plus the body.
6. **done policy** — `on-published`. The default resolves the topic
   (`✔`-prefix, via `mark-done!`).

## Source: Zulip `#blog`

[`zulip.clj`](../src/publisher/zulip.clj) builds the generic `:source` port
`{:list-new :reply! :mark-done!}` over the Zulip REST API (Basic auth, bot email : key):

- `list-new` — list the stream's topics, drop any already carrying the `✔` done marker
  (and any `:skip`), and read each topic's **oldest** message (the note) as raw Markdown
  (`apply_markdown=false`).
- `reply!` — post a message into the topic.
- `mark-done!` — rename the topic with the `✔` prefix (Zulip's resolved-topic marker),
  propagating to the whole thread.

The done marker is what makes the poll idempotent: a resolved topic is never
republished. Any other bus (Discourse, …) can drive the machine by supplying a source
port of the same shape.

## Swapping facades

[`machine/build`](../src/publisher/machine.clj) `deep-merge`s `overrides` onto
`defaults`, so a consumer replaces:

- any **facade** under `:ports` — `:make-source` (a different source bus), `:channel`
  (a different repost target), `:site`, `:translate`, or `:on-published`;
- any per-adapter values under `:config`.

The **done policy** is a facade on purpose. The default resolves the thread here; a
different instance can make it a no-op (e.g. let the researcher resolve the thread only
after it has ingested the note).

## Running it

Standalone, the publisher polls forever under a supervised zeno loop (a crashing pass is
isolated, not fatal) — [`main.clj`](../src/publisher/main.clj): `-main` polls `#blog`
every `:poll-ms`; `once` publishes the current backlog and exits.

Under the instance, [`boot`](../src/boot.clj) folds the provisioned publisher bot and
delivered accesses into `build` overrides and runs one pass. Secrets reach it under
canonical names — `ZULIP_SITE` / `ZULIP_EMAIL` / `ZULIP_API_KEY` (source) and
`TELEGRAM_BOT_TOKEN` (channel); the publisher runs as a trusted host machine, so git
push uses its own SSH key rather than a delivered token. See
[`docs/provisioning.md`](provisioning.md).
