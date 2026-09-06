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
            [researcher.budget :as budget]
            [clojure.java.shell :refer [sh]]
            [clojure.data.json :as json]
            [clojure.string :as str]))

(def system-prompt
  (str "You maintain an auto-researcher wiki as an atomic Zettelkasten. Cards are minimal "
       "(ONE idea each) and densely cross-linked with [[wikilinks]]. Two kinds:\n"
       "  - concept: an ENCYCLOPEDIC, objective, canonical explanation of a well-established "
       "concept. Evergreen; created ONCE and reused; NO mention of Andy or his notes.\n"
       "  - connection: a SEPARATE small card bridging Andy's specific claim in a note to a "
       "concept, or flagging a possible misinterpretation — judged objectively against the "
       "concept.\n\n"
       "Your ONLY tool is `eval` (deny-by-default):\n"
       "  (context)                  -> your profile/branch\n"
       "  (recall query k)           -> related corpus + EXISTING cards (check before writing)\n"
       "  (search query) (fetch url) -> web results / readable source text (fetch also cites it)\n"
       "  (central n) (reference-frequency)\n"
       "  (put-concept! {:title :description :tags [..] :body \"md\" :sources [urls]})\n"
       "       -> writes the canonical concept card; if it already exists returns {:skipped}\n"
       "          — do NOT try to rewrite it.\n"
       "  (put-connection! {:title :tags [..] :body \"md with [[Concept]] links\" :seed \"url\" :sources [urls]})\n"
       "  (propose-task! {...})      -> file a follow-up concept the note also needs (optional)\n\n"
       "Procedure for the issue's concept X:\n"
       "1. (recall X 8) to see existing cards + related corpus.\n"
       "2. CONCEPT CARD: unless X already exists, (search)+(fetch) 2+ authoritative sources, then\n"
       "   (put-concept! ...) — encyclopedic, objective, atomic, every claim cited, NO Andy. If\n"
       "   it returns {:skipped}, the canonical card stands; move on.\n"
       "3. CONNECTION CARD (optional): only if you have something substantive — link Andy's exact\n"
       "   claim in the seed note to X, or flag a likely misinterpretation. One small\n"
       "   (put-connection! ...) with [[X]] and the seed url. If nothing substantive, skip it.\n"
       "Keep every card atomic and objective. Then stop. Act via eval only; no prose answers."))

(defn mcp-json [cfg]
  (json/write-str
   {:mcpServers
    {:researcher {:type "http"
            :url (str "http://" (get-in cfg [:gateway :host] "127.0.0.1")
                      ":" (get-in cfg [:gateway :port] 7777) "/mcp/worker")}}}))

(defn issue-block [issue]
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

(defn- trunc [s n]
  (let [s (str s)] (if (> (count s) n) (str (subs s 0 n) " …[+" (- (count s) n) "]") s)))

(defn- trace-md []
  (let [steps @task/trace]
    (str "**eval calls:** " (count steps) "\n\n"
         (str/join "\n"
                   (map-indexed
                    (fn [i s]
                      (str (inc i) ". `[" (if (:ok? s) "ok" "ERR") " " (:ms s) "ms]`\n"
                           "```clojure\n" (trunc (:code s) 700) "\n```\n"
                           "→ " (trunc (:result s) 400) "\n"))
                    steps)))))

(defn- run-comment
  "Live/final audit for the issue: status, cost, eval trace so far, and (at the
   end) the omp output and PR/branch outcome."
  [issue {:keys [status out result]}]
  (let [b (budget/snapshot)]
    (str "## 🤖 Worker — issue #" (:number issue) "  "
         (case status :running "⏳ running…" :done "✅ done" (str status)) "\n\n"
         (when result
           (cond (:pr result)    (str "**PR:** " (:pr result) "  ·  branch `" (:branch result) "`\n\n")
                 (:error result) (str "**push/PR failed:** " (:error result)
                                      "  ·  cards committed on `" (:branch result) "`\n\n")
                 :else           "**No page written** — the branch had no commits.\n\n"))
         "**embed cost:** $" (format "%.5f" (double (get-in b [:embed :usd])))
         " (" (get-in b [:embed :tokens]) " tok)  ·  Claude on subscription quota\n\n"
         "<details" (when (= status :done) " open") "><summary>eval trace</summary>\n\n"
         (trace-md)
         "\n</details>"
         (when out (str "\n\n<details><summary>omp final output</summary>\n\n```\n"
                        (trunc out 3000) "\n```\n</details>")))))

(defn run
  "Execute one approved issue map {:number :title :body :item-id?}. Streams the
   eval trace to a live-updated issue comment, then pushes the branch and opens a
   PR. Returns {:issue n :branch b :pr url} | {:error ...} | {:no-write true}."
  [cfg issue]
  (let [branch (str (get-in cfg [:worker :branch-prefix] "researcher/issue-") (:number issue))
        base   (get-in cfg [:wiki :base] "main")
        repo   (wiki/prepare-branch! cfg branch)
        neigh  (wiki/page-titles repo)
        tmp    (str (System/getProperty "java.io.tmpdir")
                    "researcher-work-" (:number issue) "-" (System/currentTimeMillis))
        prompt (str (issue-block issue)
                    "\n\nEXISTING WIKI CARDS (link, don't duplicate): "
                    (if (seq neigh) (str/join ", " neigh) "(none yet)")
                    "\n\nResearch the concept, then write the card(s) via (put-concept! ...).")]
    (.mkdirs (java.io.File. (str tmp "/.omp")))
    (spit (str tmp "/.omp/mcp.json") (mcp-json cfg))
    (reset! task/current {:profile :worker :wiki-repo repo :branch branch :issue (:number issue)})
    (reset! task/trace [])
    (budget/reset-run!)
    (let [cid     (try (get (gh/comment-issue! cfg (:number issue)
                                               (run-comment issue {:status :running})) "id")
                       (catch Throwable _ nil))
          running (atom true)
          updater (when cid
                    (future
                      (while @running
                        (Thread/sleep 6000)
                        (try (gh/update-comment! cfg cid (run-comment issue {:status :running}))
                             (catch Throwable _ nil)))))]
      (try
        (let [{:keys [exit out err]}
              (sh "omp" "-p" "--no-tools" "--no-session" "--no-title"
                  "--model" (get-in cfg [:omp :model]) "--cwd" tmp
                  "--system-prompt" system-prompt "--" prompt)
              _ (do (println "=== omp exit" exit "===") (println out)
                    (when (seq err) (println "--- stderr ---\n" err)))
              result (if (wiki/ahead? repo base branch)
                       (try
                         (wiki/push-branch! cfg repo branch)
                         (let [pr (gh/create-pr! cfg {:title (str "wiki: " (:title issue))
                                                      :head  branch :base base
                                                      :body  (str "Closes #" (:number issue)
                                                                  "\n\nAuto-drafted by the researcher worker.")})]
                           (when-let [item (:item-id issue)]
                             (try (projects/set-status! cfg (get (projects/find-project cfg) "id") item "In Progress")
                                  (catch Throwable _ nil)))
                           {:issue (:number issue) :branch branch :pr (get pr "html_url")})
                         (catch Throwable e
                           {:issue (:number issue) :branch branch :error (.getMessage e)}))
                       {:issue (:number issue) :no-write true})]
          (reset! running false)
          (budget/report)
          (when cid
            (try (gh/update-comment! cfg cid (run-comment issue {:status :done :out out :result result}))
                 (catch Throwable e (println "final comment failed:" (.getMessage e)))))
          result)
        (finally (reset! running false) (reset! task/current nil))))))
