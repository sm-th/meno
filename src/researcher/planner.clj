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
  (str "You are the PLANNING stage of an OBJECTIVE auto-researcher wiki. The wiki "
       "holds only common, well-established knowledge (concepts/theories), SELECTED "
       "for relevance to Andy Smith's notes — never his opinions or personal theses.\n\n"
       "Your ONLY tool is `eval`: Clojure evaluated against a granted image "
       "(deny-by-default). Capabilities:\n"
       "  (context)             -> model/profile info\n"
       "  (recall query k)      -> [{:title :kind :source ...}] related corpus\n"
       "  (fetch url)           -> readable text of a page\n"
       "  (central n)           -> most-linked existing wiki concepts\n"
       "  (reference-frequency) -> cited sources by frequency\n"
       "  (propose-task! {...}) -> file ONE issue; returns {:filed n} or {:refused why}\n\n"
       "Procedure:\n"
       "1. (recall ...) on the seed to see related corpus and avoid duplicates.\n"
       "2. Choose ONE googleable, well-established CONCEPT worth its own wiki page, "
       "relevant to the seed, objective (not the author's opinion), not already covered.\n"
       "3. Call (propose-task! {:op :create :type :concept :title \"...\" "
       ":rationale \"objective; how the seed points to it\" :acceptance [\"...\"] "
       ":seed_note \"<seed url>\" :suggested_sources [\"url\"]}).\n"
       "Propose AT MOST ONE. If it returns {:refused ...}, stop. Act via eval; do "
       "not write prose answers."))

(defn- mcp-json [cfg grant]
  (json/write-str
   {:mcpServers
    {:door {:type "http"
            :url (str "http://" (get-in cfg [:door :host] "127.0.0.1")
                      ":" (get-in cfg [:door :port] 7777)
                      "/mcp/" grant)}}}))

(defn run [cfg]
  (let [blog (get-in cfg [:blog :root])
        {:keys [sha]} (git/newest-publish blog)
        rel  (first (git/commit-post-files blog sha))
        n    (note/load-note blog rel)
        tmp  (str (System/getProperty "java.io.tmpdir") "researcher-plan-" (System/currentTimeMillis))]
    (.mkdirs (java.io.File. (str tmp "/.omp")))
    (spit (str tmp "/.omp/mcp.json") (mcp-json cfg "planner"))
    (let [prompt (str "SEED NOTE\nTITLE: " (:title n) "\nURL: " (:url n) "\n\n"
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
