(ns researcher.worker
  "Stage 2 (WORKER): execute ONE approved issue. Launch an omp/Sonnet session
   whose ONLY tool is `eval` (worker grant) over the /mcp/worker endpoint of the
   living image. The grant is branch-scoped via researcher.task/current, so the
   write fns (put-concept!/put-reference!) commit to a per-issue branch of a
   managed wiki clone. After the session, push the branch and open a PR that
   closes the issue. MUST run inside the living image (the gateway reads the same
   task/current atom that this sets)."
  (:require [researcher.task :as task]
            [researcher.wiki :as wiki]
            [researcher.github :as gh]
            [researcher.projects :as projects]
            [clojure.java.shell :refer [sh]]
            [clojure.data.json :as json]
            [clojure.string :as str]))

(def system-prompt
  (str "You are the WORKER stage of an OBJECTIVE auto-researcher wiki. Execute ONE "
       "issue: research a well-established concept and write ONE objective wiki page "
       "selected for relevance to Andy Smith's notes.\n\n"
       "Your ONLY tool is `eval`: Clojure against a granted image (deny-by-default):\n"
       "  (context)                 -> your profile/branch\n"
       "  (recall query k)          -> related corpus + existing pages (for dedup + links)\n"
       "  (search query)            -> web results [{:title :url :snippet}]\n"
       "  (fetch url)               -> readable text of a source (also saves it as a citation)\n"
       "  (central n) (reference-frequency)\n"
       "  (put-concept! {:title :description :tags [..] :body \"markdown\" :sources [urls]})\n"
       "  (propose-task! {...})     -> file a follow-up issue for a gap you found (optional)\n\n"
       "Procedure:\n"
       "1. (recall <concept> 8) to see related corpus and existing pages — link to them,\n"
       "   never duplicate an existing page.\n"
       "2. (search ...) then (fetch <url>) 2+ authoritative sources. Cite what you fetch.\n"
       "3. Write ONE page with a single (put-concept! ...). Rules:\n"
       "   - Objective, well-established knowledge only; NO opinions or personal theses.\n"
       "   - Ground every claim in a fetched source (put its url in :sources); keep any\n"
       "     reference to Andy's seed note distinct from established fact.\n"
       "   - English. One idea per page. Use [[wikilinks]] to pages seen in recall.\n"
       "   - Satisfy EVERY acceptance item in the issue.\n"
       "Then stop. Act via eval only; no prose answers."))

(defn- mcp-json [cfg]
  (json/write-str
   {:mcpServers
    {:researcher {:type "http"
            :url (str "http://" (get-in cfg [:gateway :host] "127.0.0.1")
                      ":" (get-in cfg [:gateway :port] 7777) "/mcp/worker")}}}))

(defn- issue-block [issue]
  (str "ISSUE #" (:number issue) "\nTITLE: " (:title issue) "\n\n" (:body issue)))

(defn pick
  "Approved (Todo) issue by number, or the first in the queue. Enriches with the
   issue body. Returns nil if no such approved issue."
  [cfg number]
  (let [pid  (get (projects/find-project cfg) "id")
        todo (projects/todo-items cfg pid)
        it   (if number
               (first (filter #(= (Integer/parseInt (str number)) (:number %)) todo))
               (first todo))]
    (when it
      (assoc it :body (get (gh/get-issue cfg (:number it)) "body")))))

(defn run
  "Execute one approved issue map {:number :title :body :item-id?}. Launches the
   worker omp session, then pushes the branch and opens a PR. Returns
   {:issue n :branch b :pr url} or {:issue n :no-write true}."
  [cfg issue]
  (let [branch (str (get-in cfg [:worker :branch-prefix] "researcher/issue-") (:number issue))
        base   (get-in cfg [:wiki :base] "main")
        repo   (wiki/prepare-branch! cfg branch)
        neigh  (wiki/page-titles repo)
        tmp    (str (System/getProperty "java.io.tmpdir")
                    "researcher-work-" (:number issue) "-" (System/currentTimeMillis))]
    (.mkdirs (java.io.File. (str tmp "/.omp")))
    (spit (str tmp "/.omp/mcp.json") (mcp-json cfg))
    (reset! task/current {:profile :worker :wiki-repo repo :branch branch :issue (:number issue)})
    (try
      (let [prompt (str (issue-block issue)
                        "\n\nEXISTING WIKI PAGES (link, don't duplicate): "
                        (if (seq neigh) (str/join ", " neigh) "(none yet)")
                        "\n\nResearch the concept, then write ONE page with (put-concept! ...).")
            {:keys [exit out err]}
            (sh "omp" "-p" "--no-tools" "--no-session" "--no-title"
                "--model" (get-in cfg [:omp :model])
                "--cwd" tmp
                "--system-prompt" system-prompt
                "--" prompt)]
        (println "=== omp exit" exit "===")
        (println out)
        (when (seq err) (println "--- stderr ---\n" err))
        (if (wiki/ahead? repo base branch)
          (do
            (wiki/push-branch! cfg repo branch)
            (let [pr (gh/create-pr! cfg {:title (str "wiki: " (:title issue))
                                         :head  branch :base base
                                         :body  (str "Closes #" (:number issue)
                                                     "\n\nAuto-drafted by the researcher worker.")})]
              (when-let [item (:item-id issue)]
                (try (projects/set-status! cfg (get (projects/find-project cfg) "id") item "In Progress")
                     (catch Throwable _ nil)))
              {:issue (:number issue) :branch branch :pr (get pr "html_url")}))
          {:issue (:number issue) :no-write true}))
      (finally (reset! task/current nil)))))
