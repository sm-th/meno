(ns researcher.planner
  "Stage 1 (PLANNER): one note -> proposed research tasks (issues).
   Code does ingest/index/similarity (recall); the LLM makes the meaning
   calls (perspective, connection judgment, task proposal). Dry-run only
   assembles and prints the LLM requests it would send."
  (:require [researcher.git :as git]
            [researcher.note :as note]
            [researcher.corpus :as corpus]
            [researcher.llm :as llm]
            [clojure.string :as str]))

;; ---------- prompt fragments (code-assembled context) ----------

(defn- note-block [n]
  (str "TITLE: " (:title n) "\n"
       "URL: " (:url n) "\n"
       "TAGS: " (str/join ", " (:tags n)) "\n"
       "DESCRIPTION: " (:description n) "\n\n"
       "BODY:\n" (:body n)))

(defn- cand-block [label items]
  (if (seq items)
    (str label ":\n"
         (str/join "\n"
                   (map #(str "  - " (:title %)
                              " [tags: " (str/join "," (:tags %)) "]"
                              "  (tag-overlap " (:overlap %) ")")
                        items)))
    (str label ": (none surfaced by code-side similarity)")))

(defn- page-list [wiki]
  (if (seq wiki)
    (str/join "\n" (map #(str "  - " (:title %)) wiki))
    "  (wiki is currently empty)"))

;; ---------- the three LLM requests ----------

(defn perspective-req [n sim-pages sim-posts model]
  (llm/request
   "perspective-select"
   model
   (str "You are the PLANNING stage of an auto-researcher that operates ONLY on "
        "Andy Smith's public notes. From ONE note, choose 2-4 research PERSPECTIVES "
        "worth investigating: known theories, adjacent fields, or prior art the note "
        "connects to. Do NOT research now; only name angles. The candidate related "
        "material below was surfaced by cheap code-side similarity (recall) \u2014 use "
        "judgment for relevance (precision). Output a short list: perspective + why it "
        "fits this note.")
   (str (note-block n) "\n\n"
        (cand-block "CANDIDATE related wiki pages" sim-pages) "\n\n"
        (cand-block "CANDIDATE related notes" sim-posts))))

(defn connection-req [n sim-posts model]
  (llm/request
   "connection-judge"
   model
   (str "You are the PLANNING stage (connection judgment). Given the note and CANDIDATE "
        "connections surfaced by code-side tag/lexical similarity, judge which are "
        "MEANINGFUL and NON-OBVIOUS enough to become a research task \u2014 links the note "
        "itself does not state (serendipity). Reject trivial, duplicate, or already-obvious "
        "links. Output kept connections, each with a one-line justification.")
   (str (note-block n) "\n\n"
        (cand-block "CANDIDATE connections (other notes)" sim-posts))))

(defn propose-req [n wiki max-tasks model]
  (llm/request
   "propose-tasks"
   model
   (str "You are the PLANNING stage (task proposal). Turn this ONE note into a SMALL, "
        "bounded set of ATOMIC research tasks for the wiki (max " max-tasks "). Each task "
        "becomes a GitHub issue. Rules: one idea per task; connect Andy's thinking to known "
        "theory / prior art; dedup against existing wiki pages; English artifacts. Only "
        "propose a task if it is genuinely worth asking (gate; fewer is better).\n\n"
        "Each task is an EDN map:\n"
        "{:op :create|:expand|:connect|:restructure  ; effect on the wiki graph\n"
        " :scope :page|:graph\n"
        " :title \"imperative, specific\"\n"
        " :rationale \"why, tied to the note\"\n"
        " :acceptance [\"checkable done-conditions\"]\n"
        " :source_refs [\"<note-url>\"]\n"
        " :suggested_links [\"existing page or concept\"]}\n\n"
        "Output ONLY an EDN vector of task maps.")
   (str (note-block n) "\n\n"
        "EXISTING wiki pages (dedup against these):\n" (page-list wiki) "\n\n"
        "EXISTING open issues: (none)\n\n"
        "max tasks: " max-tasks)))

;; ---------- loop steps: input -> handle -> record ----------

(defn input [{:keys [cfg] :as ctx}]
  (let [blog (get-in cfg [:blog :root])
        {:keys [sha subject] :as commit} (git/newest-publish blog)
        rel  (first (git/commit-post-files blog sha))
        n    (note/load-note blog rel)
        wiki (corpus/wiki-pages (get-in cfg [:wiki :root]) (get-in cfg [:wiki :content]))
        posts (remove #(= (:slug %) (:slug n)) (corpus/blog-posts blog))]
    (assoc ctx
           :commit commit
           :note n
           :wiki wiki
           :sim-pages (corpus/similar-by-tags (:tags n) wiki)
           :sim-posts (corpus/similar-by-tags (:tags n) posts))))

(defn handle [{:keys [cfg note wiki sim-pages sim-posts commit] :as ctx}]
  (let [jm (get-in cfg [:llm :model :judge])
        gm (get-in cfg [:llm :model :generate])
        mx (get-in cfg [:planner :max-tasks-per-note])]
    (println "\u25B6 Stage 1 PLANNER \u2014 ingesting ONE note (per publish commit)")
    (println "  commit:" (:sha commit) "\u2014" (:subject commit))
    (println "  note:  " (:title note) " " (:url note))
    (println "  tags:  " (str/join ", " (:tags note)))
    (println "  corpus: wiki-pages=" (count wiki)
             " tag-similar-notes=" (count sim-posts)
             " tag-similar-pages=" (count sim-pages))
    (println)
    (llm/ask (perspective-req note sim-pages sim-posts jm))
    (llm/ask (connection-req note sim-posts jm))
    (llm/ask (propose-req note wiki mx gm))
    ctx))

(defn record [ctx]
  (println (apply str (repeat 66 \=)))
  (println "RECORD  \u25B8 dry-run: LLM disabled \u2192 0 tasks materialized; no issues created.")
  (println "         The requests above are exactly what Stage 1 would send.")
  (println)
  ctx)

(def steps {:input input :handle handle :record record})
