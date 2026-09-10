# Meno — target design

> **Status: direction, not current state.** This records the design we're converging
> on. What's *built today* is described in [`process.md`](./process.md) and
> [`zeno.md`](./zeno.md) and differs substantially (GitHub Projects board, blog-as-input,
> typed Markdown cards, Qdrant index, auto-approve/merge). This document is where we're
> taking it; migration is future work. Items are tagged **[decided]**, **[proposed]**
> (a recommendation to confirm), or listed under Open questions.

## 1. What Meno is

A **universal, self-hostable auto-researcher.** One *private working layer* (you
thinking and asking) is turned into a set of *public artifacts*. It is not one product
wiki — anyone runs it on their own source. The knowledge base is an **intermediate**
value, but nothing stops the intermediate from being public.

## 2. Input and output

**Source of truth (the only input): your writing and thinking in a private
[Zulip](https://zulip.com).** Streams = areas/projects, topics = threads. Categories are
*shareable* or *private*; private content never leaves the working layer.

**The projections (the outputs)** — produced by **one service** (built on the zeno
pattern), not three separate bots:

- **Blog** — a faithful *reprint* of your posts (a projection that already exists as its
  own bot; it folds into the same service).
- **Wiki / KB** — *researched, linked* knowledge. This document's focus. Intermediate
  but public.
- **Social** — later; a *remix* of the KB (+ source) for an audience.

So: **input = a private working layer; output = public projections; the KB is the
substrate the social layer will draw from.** Private, non-shareable content stays in the
working layer; only the shareable projections face outward. **[decided]**

## 3. The engine (only what matters for the KB)

- **Stateless.** The service holds **no durable state**. On each tick / after a restart
  it reads Zulip + the repo (+ GitHub issues) and recomputes. Crash = rescan; nothing is
  lost, because nothing lived in the service. **[decided]**
- **All durable state lives in already-durable stores:**
  - **Zulip** — the conversation, plus coarse work markers (a reaction = "taken/done", a
    resolved topic = done).
  - **Repo (flat Markdown)** — the KB itself + git history (the record of what was done;
    the source of truth for dedup).
  - **GitHub issues/PRs** — *agent-only plumbing*, never human-triaged (that's Zulip):
    **issues = the externalized durable work queue** the service reads instead of holding
    a queue in memory; **PRs = the reviewable / revertible / CI-gated write pipeline** +
    audit trail. **[decided]**
- **Requirements this imposes:** actions are idempotent (a re-run converges); progress
  markers live in Zulip; long jobs checkpoint into the topic (or a draft file) so a crash
  doesn't burn work.
- **Processing is incremental/reactive:** each *material* Zulip message may mutate the KB;
  it is **not** gated on topic-resolve. Zulip topics ↔ KB files are **many-to-many**.
  Any proactive "frontier" work is **derived from the KB** on the fly, not a stored
  backlog. **[decided]**

## 4. The knowledge base — a discourse graph

The KB is a **flat directory of public Markdown files**, cross-linked, organized
**by idea, not by conversation.** Its model is a **[discourse graph](https://joelchan.me/assets/pdf/Discourse_Graphs_for_Augmented_Knowledge_Synthesis_What_and_Why.pdf)**
(Joel Chan) — "Luhmann for research": the central unit is the **claim**, related by
**typed edges**, with questions and evidence.

**Two layers kept distinct** (avoids the documented "provenance-role collapse" of
flattening everything into one card):

- **World (objective):**
  - **Concept** — a canonical short definition / handle everything links to.
  - **Source (evidence)** — a digested external reading, with bibliographic fields +
    provenance. The literature note: *what the source says.*
  - **Research** — a grounded, long-form synthesis answering a question.
- **You (subjective):**
  - **Take = a Claim** — your position/assertion, linked to the concept / known theory it
    instantiates, and — crucially — carrying a typed **`diverges-from`** edge where you
    depart from the canon. That divergence is *queryable* → the fuel for the social layer.
    **[proposed: "take = claim", to confirm]**

**Frontier / driver:**
- **Question** — its own page (Luhmann-style), state `open → answered`; **many-to-many
  with Research** (several researches per question and vice versa) via a typed edge.
  **[decided]**

**Cross-cutting properties:**
- **Typed relations, not just `[[links]]`:** `supports` / `opposes` / `informs` /
  `answers` / `instantiates` / `diverges-from`. In flat Markdown this is a small grammar
  (dedicated sections or annotated links). **[proposed]**
- **Provenance + citation-lock:** every claim carries origin + timestamp + an evidence
  pointer; the agent asserts only what it has grounds for, else **abstains**. Keeps the
  public wiki from hallucinating.
- **Navigation layer:** auto-generated **Maps of Content / hub notes** (entry points),
  ranked by **centrality** (≈ PageRank ≈ today's `central`). Needed both for readers and
  for the agent to orient before writing.
- **Evolution:** claim **status** (`draft` / `established` / `contested` / `retracted`) +
  `superseded-by`. Positions change; git history alone is not enough.
- **Identity & dedup:** the **canonical title is the address** (title-as-API, Matuschak);
  the same idea must map to **one** page, with an explicit merge/split policy.
- Notes are **atomic, concept-oriented, densely linked** (evergreen-notes discipline).

## 5. Decisions so far

- Subjective/objective split is first-class. **[decided]**
- Question = its own page (Luhmann). **[decided]**
- Research = its own page; many-to-many with questions. **[decided]**
- The idea↔theory mapping lives *inside* the take with a status — sharpened to a typed
  `diverges-from` edge. **[decided + proposed sharpening]**
- Take = a **Claim** (the discourse-graph unit), not a generic "idea". **[proposed]**
- Adopt the **discourse-graph** model (Question/Claim/Evidence + typed relations).
  **[proposed — full vs. lighter typing to confirm]**
- Zulip replaces the GitHub Projects board as the human bus; issues/PRs stay as agent-only
  plumbing. **[decided]**
- The engine is stateless; state lives in Zulip + repo + issues. **[decided]**

## 6. Open questions (blind spots to resolve)

- **Full discourse-graph typing vs. a lighter edge set.**
- The exact **link grammar** in flat Markdown (dedicated sections vs. annotated links).
- **Atomicity policy:** what counts as "one idea"; preventing over/under-splitting by a
  continuously-writing agent.
- **Identity / dedup mechanics**; merge and split.
- **Provenance granularity** (per-claim vs. per-page) and the exact citation-lock rule.
- **Question lifecycle** richer than open/answered (sub-questions, dependencies, reopened).
- **Raw vs. synthesized boundary:** keep the literature note (what a source *literally*
  says) separate from your reformulation — three roles: **evidence / claim / take**.
- **Privacy guard at distillation:** keeping private (non-shareable) content from leaking
  into a public claim.
- **Retrieval index:** a derived embedding cache, rebuildable from the repo — local vs.
  cloud.

## 7. Best-practice grounding

- **Discourse graphs** (Joel Chan et al.) — claim-centric, typed relations
  (supports/opposes/informs/answers); the research-grade Zettelkasten.
  [What & why (PDF)](https://joelchan.me/assets/pdf/Discourse_Graphs_for_Augmented_Knowledge_Synthesis_What_and_Why.pdf) ·
  [extension](https://oasis-lab.gitbook.io/roamresearch-discourse-graph-extension)
- **Evergreen notes** (Andy Matuschak) — atomic, concept-oriented, densely linked,
  titles-as-API. [notes.andymatuschak.org](https://notes.andymatuschak.org/Evergreen_notes)
- **Zettelkasten** (Luhmann) — fixed IDs + keyword index; hub / structure notes & Maps of
  Content for navigation (hub-ness ≈ PageRank). [zettelkasten.de](https://zettelkasten.de/introduction/)
- **Agent-memory canon (2025–26)** — provenance-aware, citation-locked, *typed* memory
  (separate raw evidence / claim / take to avoid provenance-role collapse); temporal
  knowledge graphs for supersession/conflict.
  [Agent Zero Memory](https://arxiv.org/abs/2608.29606)

## 8. Relationship to the current implementation

Today's code uses GitHub Projects as the human board, the blog as an *input*, typed
Markdown cards, a Qdrant index, and auto-approve/auto-merge (see
[`process.md`](./process.md)). This design **supersedes** that: Zulip becomes the human
layer, the board is dropped, the blog becomes a *derived output*, and the KB becomes a
discourse graph. Migration is future work, tracked separately.
