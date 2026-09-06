(ns researcher.worker
  "Stage 2 (WORKER) dry-run: one issue -> the LLM request omp would run, plus
   the microsandbox launch command that would host it. Fat code around thin omp:
   search/fetch/git/PR are code-mediated tools; the LLM only synthesizes prose."
  (:require [researcher.note :as note]
            [researcher.llm :as llm]
            [researcher.sandbox :as sandbox]
            [clojure.string :as str]))

;; Example task, representing what Stage 1 would file for the newest note.
(def example-issue
  {:number 1
   :op :create :scope :page
   :title "Least privilege for AI agents"
   :rationale (str "The note argues an agent with physical access to secrets will "
                   "eventually reach them, so least privilege must come first. "
                   "Connect this to the established least-privilege principle "
                   "(Saltzer & Schroeder, 1975) and to sandbox/capability models.")
   :acceptance ["Page defines least privilege and applies it to agent sandboxing"
                "Cites the source note and >= 2 external sources"
                "Links to [[Ephemeral agents]] and [[Sandboxing]]"]
   :source_refs ["/2026/Sep/6/ephemeral-agents/"]
   :suggested_links ["Ephemeral agents" "Sandboxing"]})

(defn- issue-block [i]
  (str "ISSUE #" (:number i) "  [op " (name (:op i)) " / scope " (name (:scope i)) "]\n"
       "TITLE: " (:title i) "\n"
       "RATIONALE: " (:rationale i) "\n"
       "ACCEPTANCE:\n" (str/join "\n" (map #(str "  - " %) (:acceptance i))) "\n"
       "SOURCE_REFS: " (str/join ", " (:source_refs i)) "\n"
       "SUGGESTED_LINKS: " (str/join ", " (:suggested_links i))))

(defn- note-block-safe [n]
  (str "TITLE: " (:title n) "\nURL: " (:url n) "\nTAGS: " (str/join ", " (:tags n))
       "\n\n" (:body n)))

(defn worker-req [issue note model]
  (llm/request
   "worker-synthesize"
   model
   (str "You are the WORKER stage of an auto-researcher. Execute ONE issue against the "
        "wiki. You have code-mediated tools only: search(query), fetch(url) (returns "
        "readable text + a citation id), read_wiki(path), write_wiki(path, md). You do "
        "NOT have raw internet. Rules: every claim is grounded in the source note "
        "(Andy's thinking) or a fetched source (cite it); keep those two voices "
        "distinct; English; one idea per page; use [[wikilinks]]; satisfy every "
        "acceptance item. When done, the surrounding code commits, opens a PR, and you "
        "may propose follow-up issues for gaps you found. Output the final Markdown page "
        "plus a list of {url, claim} citations and any follow-up task ideas.")
   (str (issue-block issue) "\n\n"
        "SOURCE NOTE (Andy's thinking):\n" (note-block-safe note) "\n\n"
        "WIKI NEIGHBORS: (none yet \u2014 empty garden)")))


(defn dry-run [cfg _issue-arg]
  (let [blog (get-in cfg [:blog :root])
        n    (note/load-note blog "src/2026/Sep/6/ephemeral-agents/index.md")
        gm   (get-in cfg [:llm :model :generate])]
    (println "\u25B6 Stage 2 WORKER dry-run for example issue #" (:number example-issue))
    (println)
    (llm/ask (worker-req example-issue n gm))
    (sandbox/print-launch cfg :worker ["work" (str (:number example-issue))])))
