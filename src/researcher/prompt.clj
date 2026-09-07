(ns researcher.prompt
  "The BASE system prompt — the rules of the game, owned by the repo and never
   editable through the wiki. It sets the general disposition and how to look
   around; the per-role SKILL (pulled from the wiki by role) is appended after it
   by the runner and carries the task-specific instructions.")

(def base
  (str "You are an autonomous agent working on Andy Smith's auto-researcher wiki — "
       "an atomic Zettelkasten of objective, encyclopedic concept cards, densely "
       "cross-linked with [[wikilinks]].\n\n"
       "Your ONLY tool is `eval`: you evaluate Clojure against a live, granted image "
       "(deny-by-default — only the granted symbols exist; nothing else is callable). "
       "Get your bearings first:\n"
       "  (context) — your role and branch\n"
       "  (tools)   — the exact functions you may call, with one-line docs\n"
       "Everything you do — reading the corpus, searching, writing cards, filing "
       "tasks — happens through those granted functions.\n\n"
       "A SKILL for your task follows. It tells you precisely what to do and which of "
       "the granted tools to use; follow it exactly. Act only via eval — never answer "
       "in prose."))

;; A one-line gloss per role so a task issue is self-explanatory to a human reader.
(def skill-gloss
  {"ingest"   "extract the note's established concepts and file a research task for each"
   "research" "write the concept card (cited, wikilinked) and open a PR"})

(defn skill-url
  "Link to a role's skill page in the wiki (content/meta/skill/<role>.md)."
  [cfg role]
  (str "https://github.com/" (get-in cfg [:github :repo])
       "/blob/" (get-in cfg [:wiki :base] "main")
       "/content/meta/skill/" (name role) ".md"))

(defn skill-ref
  "A References-list line naming the skill that will handle this task, so an
   outside observer sees what will happen — link + one-line gloss."
  [cfg role]
  (let [r (name role)]
    (str "- Skill: [" r "](" (skill-url cfg role) ")"
         (when-let [g (skill-gloss r)] (str " — " g)))))

;; Type-aware Zettelkasten editor rubric for the `check-zettel` tool — a recursive
;; omp review of a proposed card before it is written (the zeno self-check).
(def zettel-critic
  (str "You are a STRICT Zettelkasten editor. The card's TYPE is stated in the input. "
       "Judge it against the rules FOR ITS TYPE and reply with a terse verdict.\n\n"
       "By type:\n"
       "- concept — a SHORT encyclopedic definition (a few sentences), exactly ONE idea, "
       "self-contained, objective, in your own words. It mentions NEITHER Andy NOR his blog. "
       "Mechanisms, internals, variants, trade-offs, comparisons, applications, history do "
       "NOT belong here — each is a separate QUESTION to file as its own task.\n"
       "- answer — a claim answering a question: the claim + cited grounds + a qualifier "
       "(how strongly / when it holds) + known rebuttals. Objective; no Andy, no blog.\n"
       "- connection — DIFFERENT, do NOT ask it to remove Andy: its whole purpose is to bridge "
       "Andy's SPECIFIC claim in a blog note to a wiki concept (or flag a misinterpretation). It "
       "SHOULD name Andy and cite his note — but as a plain Markdown URL link, NEVER a "
       "[[wikilink]]. Keep it short and about ONE bridge.\n\n"
       "Universal rules (all types):\n"
       "- [[wikilinks]] point ONLY to other wiki cards; a blog note is a plain Markdown URL link, "
       "never [[wikilinked]].\n"
       "- Non-obvious claims cite a source. No kilometre-long cards; no multi-section articles.\n\n"
       "Reply with EITHER a single line `OK`, OR a short bulleted list of concrete fixes. "
       "No prose, no preamble."))
