(ns researcher.runner
  "Generic ROLE runner. Every task is a GitHub issue tagged `role:<name>`. The
   runner loads that role's system prompt from the wiki (content/<meta>.md) and
   spawns ONE omp session whose only tool is `eval` (the role's grant, served by
   the living image at /mcp/<role>). Roles that :write get a per-issue wiki branch
   and open a PR; others just act through their tools (e.g. propose-task!). Roles
   are DATA (config :roles + a meta card) — adding one needs no code."
  (:require [researcher.task :as task]
            [researcher.prompt :as prompt]
            [researcher.wiki :as wiki]
            [researcher.github :as gh]
            [researcher.projects :as projects]
            [researcher.budget :as budget]
            [researcher.git :as git]
            [researcher.note :as note]
            [clojure.java.shell :refer [sh]]
            [clojure.data.json :as json]
            [clojure.string :as str]))

(defn mcp-json [cfg role]
  (json/write-str
   {:mcpServers
    {:researcher {:type "http"
                  :url (str "http://" (get-in cfg [:gateway :host] "127.0.0.1")
                            ":" (get-in cfg [:gateway :port] 7777) "/mcp/" (name role))}}}))

(defn- strip-frontmatter [s]
  (if (str/starts-with? (str s) "---")
    (let [after (subs s 3)
          end   (str/index-of after "\n---")]
      (if end (str/triml (subs after (+ end 4))) s))
    s))

(defn load-meta
  "The role's system prompt, read live from the wiki (content/<meta>.md);
   YAML frontmatter (if any) is stripped so only the prompt body is sent."
  [cfg role]
  (let [rel (get-in cfg [:roles (keyword role) :meta] (str "meta/" (name role)))
        f   (java.io.File. (str (get-in cfg [:wiki :root]) "/content/" rel ".md"))]
    (if (.exists f)
      (strip-frontmatter (slurp f))
      (throw (ex-info "missing role meta card" {:role role :path (.getPath f)})))))

(defn role-of
  "Role of an issue from its `role:<name>` label; falls back to :role, then :research."
  [issue]
  (or (some->> (:labels issue)
               (map #(if (map? %) (get % "name") %))
               (some #(when (str/starts-with? (str %) "role:") (subs (str %) 5)))
               keyword)
      (some-> (:role issue) keyword)
      :research))

(defn issue-block [issue]
  (str "ISSUE #" (:number issue) "\nTITLE: " (:title issue) "\n\n" (:body issue)))

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

(defn- run-comment [role issue {:keys [status out result]}]
  (let [b (budget/snapshot)]
    (str "## 🤖 " (str/capitalize (name role)) " — issue #" (:number issue) "  "
         (case status :running "⏳ running…" :done "✅ done" (str status)) "\n\n"
         (when result
           (cond (:pr result)    (str "**PR:** " (:pr result) "  ·  branch `" (:branch result) "`\n\n")
                 (:error result) (str "**push/PR failed:** " (:error result)
                                      "  ·  cards committed on `" (:branch result) "`\n\n")
                 :else           ""))
         "**embed cost:** $" (format "%.5f" (double (get-in b [:embed :usd])))
         " (" (get-in b [:embed :tokens]) " tok)  ·  Claude on subscription quota\n\n"
         "<details" (when (= status :done) " open") "><summary>eval trace</summary>\n\n"
         (trace-md) "\n</details>"
         (when out (str "\n\n<details><summary>omp final output</summary>\n\n```\n"
                        (trunc out 3000) "\n```\n</details>")))))

(defn pick
  "First approved (Todo) issue of ANY role (or a specific number), enriched with
   its body, labels, and parsed role. nil if none."
  [cfg number]
  (let [pid  (get (projects/find-project cfg) "id")
        todo (projects/todo-items cfg pid)
        it   (if number
               (first (filter #(= (Integer/parseInt (str number)) (:number %)) todo))
               (first todo))]
    (when it
      (let [gi (gh/get-issue cfg (:number it))]
        (assoc it :body   (get gi "body")
                  :labels (get gi "labels")
                  :role   (role-of {:labels (get gi "labels")}))))))

(defn next-todo
  "First approved (Todo) issue whose number is NOT in `exclude`, enriched with body,
   labels, and role. Lets the orchestrator launch several without re-picking one
   that is already running. nil if none."
  [cfg exclude]
  (let [pid (get (projects/find-project cfg) "id")
        it  (first (remove #(contains? (set exclude) (:number %)) (projects/todo-items cfg pid)))]
    (when it
      (let [gi (gh/get-issue cfg (:number it))]
        (assoc it :body   (get gi "body")
                  :labels (get gi "labels")
                  :role   (role-of {:labels (get gi "labels")}))))))

(defn requeue-orphans!
  "On image startup, any issue still In Progress has no live run (the process just
   came up), so move it back to the approved (Todo) status to re-run — recovers a
   run interrupted by a crash/restart. Returns the numbers re-queued."
  [cfg]
  (let [pid  (get (projects/find-project cfg) "id")
        ip   (get-in cfg [:projects :in-progress-status] "In Progress")
        todo (get-in cfg [:projects :approved-status] "Todo")]
    (->> (projects/items cfg pid)
         (keep (fn [it]
                 (when (= ip (get-in it ["status" "name"]))
                   (try (projects/set-status! cfg pid (get it "id") todo)
                        (get-in it ["content" "number"])
                        (catch Throwable _ nil)))))
         vec)))

(defn- fenced-md
  "Wrap text in a ```md fence long enough to survive any backtick run inside it,
   so the embedded note never bleeds into the surrounding task instructions."
  [s]
  (let [longest (->> (re-seq #"`+" (str s)) (map count) (reduce max 0))
        fence   (apply str (repeat (max 3 (inc longest)) \`))]
    (str fence "md\n" s "\n" fence)))

(defn- ingest-body [cfg n url]
  (str "## Objective\n\n"
       "Ingest this newly published note: extract the established concepts it leans on "
       "and file a `research` task for each. If it carries no established concepts, file nothing.\n\n"
       "## Definition of Done\n\n"
       "- [ ] Note read; canonical concepts identified (vs Andy's own coinage) — or judged as none\n"
       "- [ ] One `role:research` task filed per canonical concept not already carded or queued (deduped)\n"
       "- [ ] Nothing filed if the note carries no established concepts\n\n"
       "## References\n\n"
       (prompt/skill-ref cfg :ingest) "\n"
       "- Seed: " url "\n\n"
       "## Note\n\n"
       "**[" (:title n) "](" url ")**\n\n" (fenced-md (:body n))))

(defn file-ingest-task!
  "Per new article (call when a post is published): deterministically — no LLM —
   file ONE `role:ingest` task into Backlog for triage. `run!` later runs the
   ingest role on it (light triage → files a plan task). `slug` optional; defaults
   to the newest published post."
  ([cfg] (file-ingest-task! cfg nil))
  ([cfg _slug]
   (let [blog (get-in cfg [:blog :root])
         {:keys [sha]} (git/newest-publish blog)
         rel  (first (git/commit-post-files blog sha))
         n    (note/load-note blog rel)
         url  (str (get-in cfg [:blog :url]) (:url n))
         body (ingest-body cfg n url)
         issue (gh/create-issue cfg {:title  (str "Ingest: " (:title n))
                                     :body   body
                                     :labels ["role:ingest"]})]
     (when-let [p (projects/find-project cfg)]
       (projects/add-to-backlog! cfg (get p "id") (get issue "node_id")))
     {:filed (get issue "number") :title (str "Ingest: " (:title n)) :role :ingest})))

(defn run-issue
  "Run one issue under its role: load the role meta as the system prompt, spawn
   omp against /mcp/<role>, and — for :writes? roles — push a per-issue branch and
   open a PR closing the issue. `issue` may be a real GitHub issue (has
   :number/:item-id) or a bodied task with no :number. Returns a result map."
  [cfg issue]
  (let [role    (role-of issue)
        spec    (get-in cfg [:roles role])
        writes? (boolean (:writes? spec))
        system  (str prompt/base "\n\n---\n\n" (load-meta cfg role))
        num     (:number issue)
        base    (get-in cfg [:wiki :base] "main")
        branch  (when writes? (str (get-in cfg [:worker :branch-prefix] "researcher/issue-")
                                   (or num (System/currentTimeMillis))))
        repo    (when writes? (wiki/prepare-branch! cfg branch))
        tmp     (str (System/getProperty "java.io.tmpdir")
                     "researcher-" (name role) "-" (or num "seed") "-" (System/currentTimeMillis))
        prompt  (issue-block issue)]
    (.mkdirs (java.io.File. (str tmp "/.omp")))
    (spit (str tmp "/.omp/mcp.json") (mcp-json cfg role))
    (reset! task/current {:role role :wiki-repo repo :branch branch :issue num})
    (reset! task/trace [])
    (budget/reset-run!)
    (when (and num (:item-id issue))
      (try (projects/set-status! cfg (get (projects/find-project cfg) "id") (:item-id issue)
                                 (get-in cfg [:projects :in-progress-status] "In Progress"))
           (catch Throwable _ nil)))
    (let [cid     (when num
                    (try (get (gh/comment-issue! cfg num (run-comment role issue {:status :running})) "id")
                         (catch Throwable _ nil)))
          running (atom true)
          _upd    (when cid
                    (future (while @running
                              (Thread/sleep 6000)
                              (try (gh/update-comment! cfg cid (run-comment role issue {:status :running}))
                                   (catch Throwable _ nil)))))]
      (try
        (let [{:keys [exit out err]}
              (sh "omp" "-p" "--no-tools" "--no-session" "--no-title"
                  "--model" (get-in cfg [:omp :model]) "--cwd" tmp
                  "--system-prompt" system "--" prompt)
              _ (do (println "=== omp" (name role) "exit" exit "===") (println out)
                    (when (seq err) (println "--- stderr ---\n" err)))
              result (cond
                       (and writes? (wiki/ahead? repo base branch))
                       (try
                         (wiki/push-branch! cfg repo branch)
                         (let [pr (gh/create-pr! cfg {:title (str "wiki: " (:title issue))
                                                      :head  branch :base base
                                                      :body  (str "Closes #" num
                                                                  "\n\nAuto-drafted by the researcher (" (name role) ").")})]
                           (when (:item-id issue)
                             (try (projects/set-status! cfg (get (projects/find-project cfg) "id") (:item-id issue)
                                                        (get-in cfg [:projects :review-status] "In Review"))
                                  (catch Throwable _ nil)))
                           {:issue num :branch branch :pr (get pr "html_url")})
                         (catch Throwable e {:issue num :branch branch :error (.getMessage e)}))
                       writes? {:issue num :no-write true}
                       :else   {:issue num :done true})]
          (reset! running false)
          (budget/report)
          (when cid
            (try (gh/update-comment! cfg cid (run-comment role issue {:status :done :out out :result result}))
                 (catch Throwable e (println "final comment failed:" (.getMessage e)))))
          (when (and num (not writes?) (nil? (:error result)))
            ;; non-writing roles (ingest) produce their output as new tasks; close
            ;; the issue on success so it does not linger In Progress.
            (try (gh/close-issue! cfg num) (catch Throwable _ nil)))
          result)
        (finally (reset! running false) (reset! task/current nil))))))
