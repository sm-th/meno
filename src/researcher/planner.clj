(ns researcher.planner
  "Stage 1 (PLANNER): launch an omp/Sonnet session whose ONLY tool is `eval`
   (planner grant) served over HTTP by the long-lived researcher image. From a
   seed note it recalls related corpus and files ONE objective CONCEPT issue via
   (propose-task! ...). WIP cap is enforced live in the grant."
  (:require [researcher.git :as git]
            [researcher.note :as note]
            [clojure.java.shell :refer [sh]]
            [clojure.data.json :as json]))

(def system-prompt
  (str "You are the PLANNING stage of an auto-researcher wiki built as an atomic Zettelkasten. "
       "A seed note by Andy Smith usually touches SEVERAL well-established concepts. Identify the "
       "SET of them and file a LEAN research task for EACH canonical concept worth its own page — "
       "several propose-task! calls are expected, not one.\n\n"
       "Your ONLY tool is `eval` (deny-by-default): (context), (recall query k), (fetch url), "
       "(central n), (reference-frequency), (open-tasks -> [{:number :title}]), "
       "(enrich-task! n \"markdown\" -> {:enriched n}), (propose-task! {...} -> {:filed n} | {:refused why}).\n\n"
       "Procedure:\n"
       "1. Read the note and list EVERY well-established concept it invokes. Distinguish CANONICAL "
       "established concepts from Andy's own coinage/framing — propose tasks ONLY for canonical ones; "
       "never invent 'established' status.\n"
       "2. (recall <concept>) to skip concepts already covered by an existing card; (open-tasks) to "
       "see what is already QUEUED. If a concept is already an open task, do NOT duplicate it - skip "
       "it, or (enrich-task! N \"a new quote/angle\") to append to that task instead.\n"
       "3. For EACH remaining canonical concept, file a LEAN task (do NOT outline the article, do NOT "
       "guess or supply sources - the worker researches). If a concept name is ambiguous (e.g. "
       "\"sandboxing\"), SCOPE the title to the exact sense the note uses. Title imperative, VERB FIRST:\n"
       "   (propose-task!\n"
       "     {:op :create :type :concept\n"
       "      :title \"Research OS-level process sandboxing\" ; verb-first; disambiguated sense\n"
       "      :rationale \"what the concept is in a line + why it matters HERE + the exact claim in the note that invoked it\"\n"
       "      :quote \"a short verbatim snippet copied from the note\"\n"
       "      :seed_note \"<seed url>\"})\n\n"
       "Every task MUST carry a substantive :rationale AND a :quote from the note - they are the human's "
       "only triage surface (a bare title is refused). The universal card contract (encyclopedic, "
       "objective, atomic, >=2 cited sources, densely [[wikilinked]]) is the worker's STANDING rule - do "
       "NOT restate it in tasks. Call propose-task! once per canonical concept; if it returns {:refused "
       "...}, stop. Act via eval; no prose answers."))

(defn- mcp-json [cfg grant]
  (json/write-str
   {:mcpServers
    {:researcher {:type "http"
            :url (str "http://" (get-in cfg [:gateway :host] "127.0.0.1")
                      ":" (get-in cfg [:gateway :port] 7777)
                      "/mcp/" grant)}}}))

(defn run [cfg]
  (let [blog (get-in cfg [:blog :root])
        {:keys [sha]} (git/newest-publish blog)
        rel  (first (git/commit-post-files blog sha))
        n    (note/load-note blog rel)
        tmp  (str (System/getProperty "java.io.tmpdir") "researcher-plan-" (System/currentTimeMillis))]
    (.mkdirs (java.io.File. (str tmp "/.omp")))
    (spit (str tmp "/.omp/mcp.json") (mcp-json cfg "planner"))
    (let [prompt (str "SEED NOTE\nTITLE: " (:title n)
                      "\nURL: " (str (get-in cfg [:blog :url]) (:url n)) "\n\n"
                      (:body n)
                      "\n\nUse the eval tool to propose one objective concept page.")
          {:keys [exit out err]}
          (sh "omp" "-p" "--no-tools" "--no-session" "--no-title"
              "--model" (get-in cfg [:omp :model])
              "--cwd" tmp
              "--system-prompt" system-prompt
              "--" prompt)]
      (println "=== omp exit" exit "===")
      (println out)
      (when (seq err) (println "--- stderr ---\n" err)))))
