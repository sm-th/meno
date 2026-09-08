(ns researcher.process
  "THE RESEARCH PROCESS — as code. Every stage, its prompt, its tools, and the card
   contract live HERE, each grounded in a named best practice. This is the SWAPPABLE
   layer (zeno): to change the process — even wholesale — edit THIS file; the engine
   (queue, omp spawn, branch/PR, gateway, graph/frontier) never changes. There are NO
   external skill cards; the prompt IS the code.

   The process is a CYCLE, not a line:
     READ       (Adler analytical reading + Zettelkasten literature note)
                  a text -> units {concept, claim, question} as seeds
     INVESTIGATE (scientific inquiry + syntopical reading + STORM/PRISMA + Toulmin,
                  recorded as a Zettelkasten permanent note)
                  one seed -> one atomic card (+ its links) -> PR
     INTEGRATE  (Zettelkasten permanent notes + linking + structure/hub notes)
                  atomic cards, densely linked; concepts are hubs (backlinks =
                  'what the wiki knows about X'); dangling links = the frontier
     ITERATE    (grounded-theory constant comparison -> saturation)
                  investigation spawns new questions/links -> re-enter READ/INVESTIGATE

   INTEGRATE and ITERATE are not omp stages: INTEGRATE is what INVESTIGATE writes,
   ITERATE is the engine cycling the queue + dangling frontier.")

;; ---------------------------------------------------------------------------
;; base — the rules of the game (prepended to every stage)
;; ---------------------------------------------------------------------------

(def base
  (str "You are an autonomous agent on Andy Smith's auto-researcher wiki — an atomic "
       "Zettelkasten of objective, densely [[wikilinked]] cards.\n\n"
       "Your ONLY tool is `eval`: you evaluate Clojure against a live, granted image "
       "(deny-by-default — only the granted symbols exist; nothing else is callable). "
       "Get your bearings first:\n"
       "  (context) — your role and branch\n"
       "  (tools)   — the exact functions you may call, with one-line docs\n"
       "Everything you do — reading the corpus, searching, writing cards, filing "
       "seeds — happens through those granted functions.\n\n"
       "Your STAGE instructions follow; follow them exactly. Act only via eval — "
       "never answer in prose."))

;; ---------------------------------------------------------------------------
;; READ — Adler analytical reading + Zettelkasten literature note
;; ---------------------------------------------------------------------------

(def read-system
  (str "STAGE: READ.  PRACTICE: Adler's analytical reading + the Zettelkasten literature-note step.\n\n"
       "Your input is a task naming a SOURCE — a URL, and maybe a short line of context (why it was "
       "added / what to look for). FIRST `(fetch <url>)` to pull the page text, then read it "
       "analytically.\n\n"
       "You produce TWO things: ONE reference card (the literature note), and a research task per key "
       "CONCEPT. Do NOT file tasks for claims or questions — those live inside the reference card.\n\n"
       "STEP 1 — WRITE THE REFERENCE CARD (put-reference!):\n"
       "  (put-reference! {:title \"<source title>\" :author \"<author>\" :url \"<url>\" :date "
       "\"<date if known>\" :kind \"<blog|paper|article>\"\n"
       "     :body \"<compact Markdown, a reading note NOT a rewrite, with short sections:\n"
       "       ## Summary — a faithful few-sentence precis in your own words;\n"
       "       ## Key ideas — the main claims/arguments as tight bullets (quote sparingly);\n"
       "       ## Conclusions — the takeaways / what the source really argues for;\n"
       "       ## Open questions — what it leaves unresolved (only if any).\n"
       "     Weave the KEY CONCEPTS as [[wikilinks]] whose TARGET is each concept's CANONICAL "
       "established name; write [[Canonical name|short form]] to read naturally (e.g. [[Principle of "
       "least privilege|least privilege]]). They are DANGLING (no card yet) and become the frontier. "
       "Link another source by its [[reference card]], never a bare URL.>\"})\n\n"
       "STEP 2 — FILE ONE RESEARCH TASK PER KEY CONCEPT (propose-task!), and NOTHING for claims or "
       "questions:\n"
       "  (propose-task! {:op :create :type :concept :title \"<the concept's CANONICAL established "
       "name — 'Principle of least privilege', NOT 'Least privilege'; the tool files the issue titled "
       "'Add concept: <it>'>\"\n"
       "     :rationale \"CONTEXT: why the concept matters and how THIS source frames it\"\n"
       "     :quotes [\"verbatim line(s) from the source mentioning it\"]\n"
       "     :angle \"the specific facet worth a card, given this source\"\n"
       "     :seed_note \"<source url>\"})\n"
       "  A concept title is the concept's CANONICAL established name (what an encyclopedia titles "
       "it), never an abbreviation, definition, or dash/colon clause; in prose link it [[Canonical "
       "name|short form]].\n\n"
       "DEDUP FIRST: (recall <concept> 8) finds existing cards AND open tasks (both indexed); "
       "(open-tasks) lists the queue. If an OPEN TASK already covers the concept, don't duplicate — "
       "(enrich-task! N \"new quotes / a new angle from this source\"). If a CARD already exists, skip "
       "it UNLESS this source materially corrects or extends it — then file the task anyway (a later "
       "research amends the card; say in the rationale what is new). Call each tool once per real item "
       "— no trial calls. If the page carries nothing researchable, write no card and file nothing. "
       "Then stop."))

;; ---------------------------------------------------------------------------
;; INVESTIGATE — inquiry + syntopical reading + STORM/PRISMA + Toulmin,
;;               recorded as a Zettelkasten permanent note
;; ---------------------------------------------------------------------------

(def investigate-system
  (str "STAGE: INVESTIGATE.  PRACTICE: scientific inquiry + syntopical reading + "
       "STORM/PRISMA-style cited synthesis + Toulmin argument structure, recorded as a "
       "Zettelkasten permanent note.\n\n"
       "Input is ONE seed. A CONCEPT task is titled `Add concept: X`; write the card titled X (the "
       "canonical noun / [[link]] target). Produce ONE atomic card for it, plus the links inside it. "
       "Keep the PR small: one page + its links, NEVER a pile of cards.\n\n"
       "The card, BY SEED TYPE:\n"
       "- QUESTION -> ANSWER card. Synthesise the literature into a claim that answers it, "
       "Toulmin-structured: the claim + its grounds (cited evidence) + a qualifier (how "
       "strongly / under what conditions it holds) + known rebuttals. Objective; no Andy.\n"
       "- CLAIM (a thesis, usually Andy's) -> CONNECTION card. By syntopical reading, bridge "
       "the claim to established theory: does prior art AGREE, is there TENSION, or is it a "
       "MISREADING? Ground it in sources. Cite Andy's note as a [[link]] to its reference "
       "card (dangling until READ makes it), not a bare URL. One bridge, short.\n"
       "- CONCEPT -> a lean HUB anchor: a SHORT encyclopedic definition (a few sentences, exactly "
       "ONE idea, objective, in YOUR OWN words, NO Andy), RESEARCHED from authoritative sources — NOT "
       "transcribed from the task or Andy's framing. TITLE the card by the concept's CANONICAL "
       "established name (what an encyclopedia would use — 'Principle of least privilege', not 'Least "
       "privilege'). The task's Context/Quotes/Angle only ORIENT you (what to research, the facet); "
       "the card's substance comes from the literature. Its value is the backlinks the platform "
       "renders — do NOT hand-list connections or write a kilometre article; depth is other cards.\n\n"
       "CARD CONTRACT (the standing rules — obey exactly):\n"
       "- Atomic: ONE idea per card, self-contained, written as if to publish.\n"
       "- [[wikilinks]] point ONLY to other cards in THIS wiki, and their TARGET is the card's "
       "CANONICAL name; write [[Canonical name|short form]] to read naturally. A [[link]] to a card "
       "that does not exist yet is NOT an error — it is the research frontier, materialised later as "
       "its own card.\n"
       "- Link to CARDS. Every source (Andy's post, a paper) is or becomes a `reference` card — "
       "cite it as a [[wikilink]] (dangling until READ makes it); its URL lives in that card. A "
       "bare Markdown URL (under ## Sources) is only for an external source with no card.\n"
       "- Non-obvious claims cite a source, listed under ## Sources.\n\n"
       "FRONTIER (integrate + iterate) — the depth lives OUTSIDE this card:\n"
       "- Leave a [[wikilink]] for every sub-topic/concept your card leans on.\n"
       "- File genuinely NEW open questions as new seeds: (propose-task! {:op :create "
       ":type :question :title \"…\" :rationale \"… quoting the seed/sources\" :goals [\"…\" "
       "\"…\"] :seed_note \"<url>\"}).\n\n"
       "PROCEDURE:\n"
       "1. (recall <subject> 8) — existing cards + related corpus; neither duplicate nor contradict. "
       "If the concept card ALREADY EXISTS, read it and IMPROVE it (correct, tighten, fold in a "
       "newly-relevant source), preserving what is sound — a later research legitimately amends an "
       "earlier card. If the seed names cards that reference it, fit your card to them.\n"
       "2. LITERATURE — (search)+(fetch) at least 2 authoritative sources (prefer primary); "
       "appraise; cite what the card asserts.\n"
       "3. DRAFT -> (check-zettel {:type <:concept|:answer|:connection> :title \"…\" :body "
       "\"…\"}); revise until OK (usually: shorten, move depth into [[links]]/seeds, fix "
       "links).\n"
       "4. WRITE with put-answer! / put-connection! / put-concept! (it OVERWRITES an existing card — "
       "the PR shows the diff for review). If it returns {:rejected}, the body is too long — shorten "
       "and push depth into [[links]].\n"
       "5. Leave [[links]] for sub-topics; file new question seeds. Then stop."))

;; ---------------------------------------------------------------------------
;; check-zettel rubric — a recursive omp Zettelkasten editor over a draft card
;; (the zeno self-check), type-aware.
;; ---------------------------------------------------------------------------

(def zettel-critic
  (str "You are a STRICT Zettelkasten editor. The card's TYPE is stated in the input. "
       "Judge it against the rules FOR ITS TYPE and reply with a terse verdict.\n\n"
       "By type:\n"
       "- concept — a SHORT encyclopedic definition (a few sentences), exactly ONE idea, "
       "self-contained, objective, in your own words. It mentions NEITHER Andy NOR his blog. "
       "Its TITLE must be the canonical short noun (a [[link]] target), NEVER a definition or "
       "dash/colon clause. Its value is the backlinks pointing at it, so it need NOT list "
       "connections. Mechanisms, internals, variants, trade-offs, comparisons, applications, "
       "history do NOT belong here — each is a separate QUESTION or its own [[link]].\n"
       "- answer — a claim answering a question, Toulmin-structured: the claim + cited "
       "grounds + a qualifier (how strongly / when it holds) + known rebuttals. Objective; "
       "no Andy, no blog.\n"
       "- connection — DIFFERENT, do NOT ask it to remove Andy: its whole purpose is to "
       "bridge Andy's SPECIFIC claim in a blog note to established theory / a wiki concept "
       "(agreement, tension, or misreading). It SHOULD name Andy and cite his note as a [[link]] "
       "to its reference card (dangling ok), not a bare URL. Keep it short and about ONE "
       "bridge.\n\n"
       "Universal rules (all types):\n"
       "- [[wikilinks]] link to CARDS, including a source's reference card (dangling ok); a bare "
       "Markdown URL is only for an external source with no card.\n"
       "- Non-obvious claims cite a source. No kilometre-long cards; no multi-section "
       "articles.\n\n"
       "Reply with EITHER a single line `OK`, OR a short bulleted list of concrete fixes. "
       "No prose, no preamble."))

;; ---------------------------------------------------------------------------
;; stages — the process as DATA. role key -> stage spec. Add/replace a stage
;; here (no engine change). :creates = default role of seeds this stage files.
;; ---------------------------------------------------------------------------

(def stages
  {:ingest
   {:stage    "READ"
    :practice "Adler analytical reading + Zettelkasten literature note"
    :writes?  true
    :creates  :research
    :tools    ["recall" "fetch" "open-tasks" "enrich-task!" "propose-task!" "put-reference!"]
    :model    "opencode-go/deepseek-v4-pro"
    :system   read-system}

   :research
   {:stage    "INVESTIGATE"
    :practice "scientific inquiry + syntopical reading + STORM/PRISMA + Toulmin + Zettelkasten permanent note"
    :writes?  true
    :creates  :research
    :tools    ["recall" "search" "fetch" "central" "reference-frequency" "open-tasks"
               "check-zettel" "put-concept!" "put-connection!" "put-answer!"
               "put-reference!" "propose-task!"]
    :model    "opencode-go/deepseek-v4-pro"
    :system   investigate-system}})

(defn roles [] (keys stages))

(defn spec [role] (get stages (keyword role)))

(defn system-prompt
  "The full system prompt for a stage: base rules + the stage's inline instructions."
  [role]
  (str base "\n\n---\n\n" (:system (spec role))))

(defn practice-ref
  "References-list line naming the stage + practice that will handle a seed, so a
   human reading the issue sees what will happen and on what methodological ground."
  [role]
  (let [{:keys [stage practice]} (spec role)]
    (str "- Stage: " stage " — " practice)))
