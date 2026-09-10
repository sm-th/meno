# How Meno researches — the process

Meno continuously turns notes into a linked research wiki. This document describes the
**process as it is implemented today** — the operational loop it runs, the research
method it applies at each step, and the rules that govern both. It is not a description
of the output, nor of the code; it is what actually happens, in order, and by what
rule. (The runtime that executes it — the always-on image, how agents are spawned — is
separate, in [`zeno.md`](./zeno.md).)

Meno is universal: it reads whatever notes and writes to whatever wiki `config.edn`
names. [smith.wiki](https://smith.wiki) is one running instance.

---

## Principles

The whole process follows from a few commitments, and every step below is an
expression of one of them:

- **Atomic units.** One task produces one small, single-idea card. Meno never "writes
  an article" — it reads *one* source, defines *one* concept, or answers *one*
  question. Small units are reviewable and compose into a graph.
- **The corpus decides what's next.** Meno does not work from a hand-written roadmap.
  It reads its next work off the *gaps in the wiki itself* — a missing concept, an
  unread but recurring source, an unanswered question — and the trigger is
  **recurrence**: a gap earns a task once *several* cards independently point at it.
- **Named methods.** Each kind of work is handled by an explicit, established research
  practice (analytical & syntopical reading, the Zettelkasten note discipline, Toulmin
  argument, PRISMA/PRISMA-P survey protocol, Strong Inference, FINER/PCC question
  scoping) — so the quality bar is legible and can be argued with.
- **Ground everything.** Before writing, Meno recalls what the wiki already knows (to
  avoid duplicating and to link), and it cites the sources it actually read.
- **Bounded queue.** The backlog is capped and ranked by how many cards depend on a
  thing, so the most connective gaps go first and the queue never runs away from a
  reviewer.
- **Git is the record.** Every change is a commit and a pull request, logged in a
  changelog, and reversible by reverting that PR. The wiki is its own audit trail.

---

## The loop, step by step

Meno runs continuously and, in the current configuration, largely **unattended**
(auto-approval and auto-merge are on — see "Where the human stands" below). One cycle:

**1 — Pull in new material.** *(hourly)* For each newest published note not yet
ingested, Meno files a **READ** task. Newest-first, a few per tick.

**2 — Surface the frontier.** *(hourly, and immediately after every merge)* Meno scans
the whole corpus and turns recurring gaps into tasks: a concept that ≥ 2 cards link but
that doesn't exist yet becomes an **INVESTIGATE** task; a source URL that ≥ 2 cards
cite but that hasn't been read becomes a **READ** task. All candidates are ranked by
how many cards depend on them, and only enough are filed to keep the board at or below
its cap (currently 50 open issues total). Nobody writes this backlog by hand; the wiki
reports its own missing pieces.

**3 — Scope a research question.** The other frontier is the set of **open questions**
every card leaves behind (each card ends by naming what it didn't resolve). The
**PLAN** step selects the single most interesting, still-uncovered question and drafts
a rigorous research proposal for it. *(In the current implementation this step is run
on demand rather than on the timer, handles one question at a time, and is skipped
while a research write-up is already in flight — so questions aren't re-picked.)*

**4 — Propose.** Every unit of work is filed as an **issue** on a kanban board, in the
Backlog. The issue states the question, the stage/method that will handle it, and the
existing cards it touches — enough for a person to judge it before it runs.

**5 — Approve.** A card moving to **Todo** is the signal to work it. A person can drag
it there or reject it. By default, an auto-approval step promotes the *most
depended-on* Backlog card whenever the board is idle — one at a time, at a fixed
interval — so the loop advances without a person present.

**6 — Execute (the research method).** The orchestrator takes **one** approved task at
a time and runs it under its stage. This is where the intellectual process lives:

  - **READ** — fetch the source and read it *analytically* (Adler): write a faithful
    literature note in your own words (summary, key ideas, conclusions, open
    questions). Leave the concepts it leans on as links and the questions it raises as
    open questions; do **not** file follow-up tasks by hand — recurrence (steps 2–3)
    will promote the ones that matter.
  - **INVESTIGATE** — define **one** concept by reading *across* the notes and their
    sources (syntopical reading), grounded by recalling existing cards and searching /
    fetching the web, and checked against the Zettelkasten discipline (one idea,
    densely linked, cited) before it is written.
  - **RESEARCH** — take one open question and run the full cycle: frame why it matters,
    survey the literature, weigh competing hypotheses, and write it up with citations.
  - **PLAN** — the research-lead move (step 3): choose and scope the next question by
    named methods (FINER for selection; PCC for scoping; Strong Inference for competing
    hypotheses; PRISMA-P for the survey protocol). It *frames*, it does not answer.

  Throughout, the agent acts only through a fixed vocabulary — recall, web search,
  fetch, "what are the most central pages", "what are the most-cited sources", the
  card writers, and the self-check — grounding each claim and de-duplicating against
  what already exists.

**7 — Publish & record.** The result is committed to its own branch and opened as a
**pull request that closes the issue**. In the current configuration it auto-merges to
`main`, the site rebuilds, and the board item is removed. The closed issue and the PR
remain as the record.

**8 — Reconcile.** Right after any merge (and hourly): bare source URLs that now have a
reference card are rewritten into links, and the changelog — every merged change,
newest first, each with a one-click revert path — is regenerated.

Then the cycle repeats.

---

## Where the human stands (current implementation)

Two human checkpoints exist in the design — *which questions get researched* (step 5)
and *what gets published* (step 7). **In the config as it runs today, both are
optional**: auto-approval promotes Backlog cards on its own, and PRs auto-merge. So
Meno runs as a standing, unattended process, and the human role is:

- **ahead of time** — reject or reprioritize items sitting in the Backlog before the
  auto-approval reaches them;
- **after the fact** — review the changelog of merged changes and revert any PR that
  shouldn't have landed.

Turning off auto-approval and/or auto-merge converts those into *blocking* gates
(nothing advances or publishes without a person), with no other change to the process.

---

## How this differs from the usual

Automated research writers (STORM/Co-STORM, GPT-Researcher, AutoSurvey/PaperQA2)
typically run **once per article**: plan the perspectives, retrieve, synthesize a
long piece, cite. Meno's process is instead a **standing, frontier-driven loop over
one person's public notes**: it works one atomic card at a time, decides its own next
step from the wiki's recurring gaps rather than a given topic, applies a named research
method per step, gates on a human (optionally), and records every change in git so the
whole thing is reviewable and reversible.
