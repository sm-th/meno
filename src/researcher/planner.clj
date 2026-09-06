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
       "From a seed note by Andy Smith, identify ONE well-established, objective CONCEPT the note "
       "leans on, and file a task to research it as a canonical ENCYCLOPEDIC card — what the "
       "concept IS, objectively, independent of Andy. (The worker may later add a SEPARATE small "
       "card connecting Andy's claim to it; that is not your concern here.)\n\n"
       "Your ONLY tool is `eval` (deny-by-default): (context), (recall query k), (fetch url), "
       "(central n), (reference-frequency), (propose-task! {...} -> {:filed n} | {:refused why}).\n\n"
       "Procedure:\n"
       "1. (recall ...) on the seed to see existing cards and avoid duplicates. If a canonical "
       "card for the concept already exists, do not re-propose it.\n"
       "2. Choose ONE atomic, well-established concept the note invokes.\n"
       "3. File a LEAN task. Do NOT outline the article, do NOT guess or supply sources (the "
       "worker researches). Title MUST be imperative, VERB FIRST:\n"
       "   (propose-task!\n"
       "     {:op :create :type :concept\n"
       "      :title \"Research the principle of least privilege\"   ; verb-first imperative\n"
       "      :rationale \"what the concept is + which note invoked it (brief), so the worker can "
       "also add a connection card\"\n"
       "      :acceptance [\"encyclopedic and objective; canonical definition and core distinctions\"\n"
       "                   \"atomic: one idea per card\"\n"
       "                   \">=2 sources the worker finds and cites\"\n"
       "                   \"densely linked with [[wikilinks]] to related cards\"]\n"
       "      :seed_note \"<seed url>\"})\n\n"
       "acceptance = outcome/quality criteria ONLY, never a content outline. Propose AT MOST ONE. "
       "If {:refused ...}, stop. Act via eval; no prose answers."))

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
