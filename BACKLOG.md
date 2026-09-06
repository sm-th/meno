# Researcher — deferred backlog

Parked while we build the model-evaluation harness (`~/sm-th/model-bench`).
Restart work from here.

## Orchestrator (autonomous loop)
- Poll the blog's git-delta → spawn the planner on new publish commits.
- Poll the board **Todo** column → spawn the worker per approved issue.
- Durable `last_sha` state (in the researcher repo) so restarts don't reprocess.
- Concurrency + WIP caps (one worker at a time; planner WIP cap already in grant).

## Card types
- **`question`** — open-inquiry card. Lifecycle:
  - *open*: the question + why it matters + `[[links]]`.
  - *answered*: add a concise **Answer** + `[[links]]` to the concept/reference cards
    that ground it; set `status: answered`.
  - The answer is synthesis + links, **not** a google dump — depth stays in
    concept/reference cards. Wire `put-question!`, section `questions/`, worker prompt.
- **`moc` / structure notes** — map-of-content hubs, once there are enough concepts to
  need navigation (best-practice trigger; not yet).

## Reference collection (Linkwarden) — likely its own task
- Save cited links into a Linkwarden collection (durable reading store).
- When several cards cite ONE source → replace the bare links with a single
  **summary page** (a reference/summary card) and put that in the collection.
- Promotion is **not** strictly ≥3 incoming links: a single link that looks important
  may justify creating a task/card on its own.
- Note: Linkwarden is currently unused (fetch moved to Jina; refs embed to Qdrant only).
  Either revive it for this task, or drop the ns/config/secret.

## End-to-end + cleanup
- Full autonomous run on the real blog.
- Remove scaffolding; write docs.
- Feed the **model-eval** result back: pick the worker/planner model (maybe cheaper than
  Sonnet — DeepSeek/Qwen class) in `config.edn` once the benchmark decides.
