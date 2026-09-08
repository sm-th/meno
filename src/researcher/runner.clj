(ns researcher.runner
  "Generic ROLE runner. Every task is a GitHub issue tagged `role:<name>`. The
   runner loads that role's system prompt from the wiki (content/<meta>.md) and
   spawns ONE omp session whose only tool is `eval` (the role's grant, served by
   the living image at /mcp/<role>). Roles that :write get a per-issue wiki branch
   and open a PR; others just act through their tools (e.g. propose-concept!). Roles
   are DATA (config :roles + a meta card) — adding one needs no code."
  (:require [researcher.task :as task]
            [researcher.process :as process]
            [researcher.wiki :as wiki]
            [researcher.github :as gh]
            [researcher.projects :as projects]
            [researcher.budget :as budget]
            [researcher.graph :as graph]
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
  (str (when-let [n (:number issue)] (str "ISSUE #" n "\n"))
       "TITLE: " (:title issue) "\n\n" (:body issue)))

(defn- mined-cards
  "Card paths+titles this branch WROTE, mined from the eval trace (put-*! results)."
  [trace-str]
  (distinct (map (fn [[_ p ti]] (str "- `" p "` — " ti))
                 (re-seq #":path \"([^\"]*)\"[^}]*?:title \"([^\"]*)\"" trace-str))))

(defn- mined-filed
  "Follow-up tasks this run FILED, mined from the eval trace (propose-*! results)."
  [trace-str]
  (distinct (map (fn [[_ n ti]] (str "- #" n " " ti))
                 (re-seq #":filed (\d+),?\s*:title \"([^\"]*)\"" trace-str))))

(defn- work-summary
  "What the run DID — cards written + follow-up tasks filed — posted on the ISSUE (the
   task), NOT the PR. The PR describes only what it adds; the task carries the outcome."
  []
  (let [t     (str/join "\n" (map :result @task/trace))
        cards (mined-cards t)
        filed (mined-filed t)]
    (str (when (seq cards) (str "### Cards written\n" (str/join "\n" cards) "\n\n"))
         (when (seq filed) (str "### Follow-up tasks filed\n" (str/join "\n" filed) "\n\n"))
         (when (and (empty? cards) (empty? filed)) "_No cards written or tasks filed._"))))

(defn- pr-body
  "The PR describes only what it ADDS — the card(s) in the diff. What the run DID
   (follow-up tasks filed, the eval transcript) lives on the issue, not here."
  [issue]
  (let [cards (mined-cards (str/join "\n" (map :result @task/trace)))
        num   (:number issue)]
    (str (if num
           (str "Closes #" num "\n\n")
           (str "Materializes the missing card **[[" (:card issue) "]]**, requested by: "
                (str/join ", " (:refs issue)) ".\n\n"))
         (when (seq cards) (str "Adds to the wiki:\n\n" (str/join "\n" cards) "\n\n"))
         (if num
           "Review the card(s) in the diff below; merging accepts them and closes the issue."
           "Review the card(s) in the diff below; merging adds them to the wiki."))))

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

(defn- ingest-body [url context]
  (str "## Source\n\n" url "\n\n"
       "## Context\n\n"
       (let [c (str/trim (str context))]
         (if (str/blank? c) "_(none given — judge from the page itself)_" c)) "\n"))

(defn file-ingest-task!
  "File ONE `role:ingest` task into Backlog: just a URL (+ an optional short line of
   context). READ fetches and reads the page. Zero-arg defaults to the newest published
   post; pass a url (and context) to ingest any page by hand."
  ([cfg] (file-ingest-task! cfg nil nil))
  ([cfg url] (file-ingest-task! cfg url nil))
  ([cfg url context]
   (let [[title link] (if url
                        [(str url) url]
                        (let [blog (get-in cfg [:blog :root])
                              {:keys [sha]} (git/newest-publish blog)
                              rel (first (git/commit-post-files blog sha))
                              n   (note/load-note blog rel)]
                          [(:title n) (str (get-in cfg [:blog :url]) (:url n))]))
         issue (gh/create-issue cfg {:title  (str "Ingest: " title)
                                     :body   (ingest-body link context)
                                     :labels ["role:ingest"]})]
     (when-let [p (projects/find-project cfg)]
       (projects/add-to-backlog! cfg (get p "id") (get issue "node_id")))
     {:filed (get issue "number") :title (str "Ingest: " title) :role :ingest})))

(defn- code-fence [lang s]
  (let [s (str s)
        longest (->> (re-seq #"`+" s) (map count) (reduce max 0))
        f (apply str (repeat (max 3 (inc longest)) \`))]
    (str f lang "\n" s "\n" f)))

(defn- msg-text [content]
  (->> content (keep #(when (= "text" (get % "type")) (get % "text"))) (str/join "\n")))

(defn- fmt-int [n]
  (str/replace (str (long (or n 0))) #"\B(?=(\d{3})+(?!\d))" ","))

(defn- fmt-dur [ms]
  (let [s (quot (long (or ms 0)) 1000)]
    (if (>= s 60) (format "%dm %02ds" (quot s 60) (mod s 60)) (str s "s"))))

(defn- strip-tool [nm] (str/replace (str nm) #"^mcp__[^_]+_" ""))

(defn- run-stats
  "Totals for the omp research session, summed from its --mode=json transcript:
   cost + tokens (per-message usage is additive) + tool-call count."
  [jsonl]
  (let [as  (->> (str/split-lines (str jsonl))
                 (keep #(try (json/read-str %) (catch Exception _ nil)))
                 (filter #(and (= "message_end" (get % "type"))
                               (= "assistant" (get-in % ["message" "role"]))))
                 (map #(get % "message")))
        sum (fn [k] (reduce + 0 (keep #(get-in % ["usage" k]) as)))]
    {:cost  (reduce + 0.0 (keep #(get-in % ["usage" "cost" "total"]) as))
     :in    (sum "input") :out (sum "output")
     :cache-read (sum "cacheRead") :cache-write (sum "cacheWrite")
     :calls (reduce + 0 (for [m as] (count (filter #(= "toolCall" (get % "type")) (get m "content")))))}))

(defn- post-transcript!
  "Post the omp --mode=json transcript: ONE comment per step — assistant thinking,
   assistant prose, and each tool CALL paired with its RESULT in a SINGLE comment
   (code block + output block). Throttled to dodge GitHub's secondary rate limit."
  [cfg num jsonl]
  (let [msgs    (->> (str/split-lines (str jsonl))
                     (keep #(try (json/read-str %) (catch Exception _ nil)))
                     (filter #(= "message_end" (get % "type")))
                     (map #(get % "message")))
        results (into {} (for [m msgs :when (= "toolResult" (get m "role"))]
                           [(get m "toolCallId")
                            {:text (msg-text (get m "content")) :error? (get m "isError")}]))
        put!    (fn [body] (try (gh/comment-issue! cfg num body) (Thread/sleep 300)
                                (catch Throwable _ nil)))]
    (doseq [m msgs :when (= "assistant" (get m "role"))
            c (get m "content")]
      (case (get c "type")
        "thinking" (let [t (str/trim (str (or (get c "thinking") (get c "text"))))]
                     (when (seq t) (put! (str "### 🧠 thinking\n\n" t))))
        "text"     (let [t (str/trim (str (get c "text")))]
                     (when (seq t) (put! (str "### 💬 assistant\n\n" t))))
        "toolCall" (let [code (get-in c ["arguments" "code"] (json/write-str (get c "arguments")))
                         {:keys [text error?]} (get results (get c "id"))]
                     (put! (str "### 🔧 " (strip-tool (get c "name"))
                                (when-let [i (get c "intent")] (str " — _" i "_")) "\n\n"
                                (code-fence "clojure" code)
                                "\n\n" (if error? "❌ **error**" "✅ **result**") "\n\n"
                                (code-fence "" (or text "")))))
        nil))))

(defn- post-stats!
  "Final run-stats comment: model, wall time, the research session's cost/tokens (from the
   omp transcript) and the image-side embed/llm cost (from the budget meter)."
  [cfg num model dur-ms jsonl]
  (let [st (run-stats jsonl)
        b  (budget/snapshot)
        eu (get-in b [:embed :usd] 0.0)
        lu (get-in b [:llm :usd] 0.0)]
    (try (gh/comment-issue! cfg num
           (str "### 📊 run stats\n\n"
                "- model: `" model "`\n"
                "- time: " (fmt-dur dur-ms) "\n"
                "- research session: " (format "$%.4f" (:cost st)) " · "
                (fmt-int (:in st)) " in · " (fmt-int (:out st)) " out · "
                (fmt-int (:cache-read st)) " cache-read · " (fmt-int (:cache-write st))
                " cache-write · " (:calls st) " tool calls\n"
                "- image side: embed " (fmt-int (get-in b [:embed :tokens])) " tok / "
                (format "$%.5f" eu) " · llm " (fmt-int (get-in b [:llm :tokens])) " tok / "
                (format "$%.5f" lu) "\n"
                "- **total: " (format "$%.4f" (+ (:cost st) eu lu)) "**"))
         (catch Throwable _ nil))))

(defn run-issue
  "Run one issue under its role: load the role meta as the system prompt, spawn
   omp against /mcp/<role>, and — for :writes? roles — push a per-issue branch and
   open a PR closing the issue. `issue` may be a real GitHub issue (has
   :number/:item-id) or a bodied task with no :number. Returns a result map."
  [cfg issue]
  (let [role    (role-of issue)
        spec    (process/spec role)
        writes? (boolean (:writes? spec))
        system  (process/system-prompt role)
        num     (:number issue)
        base    (get-in cfg [:wiki :base] "main")
        branch  (when writes? (or (:branch issue)
                                  (str (get-in cfg [:worker :branch-prefix] "researcher/issue-")
                                       (or num (System/currentTimeMillis)))))
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
    (try
      (when num (try (gh/comment-issue! cfg num
                       (str "▶️ **" (:stage spec) "** started · model `"
                            (or (:model spec) (get-in cfg [:omp :model])) "`"))
                     (catch Throwable _ nil)))
      (let [t0  (System/currentTimeMillis)
            {:keys [exit out err]}
            (sh "omp" "-p" "--no-tools" "--no-session" "--no-title"
                "--mode=json" "--print-thoughts"
                "--model" (or (:model spec) (get-in cfg [:omp :model])) "--cwd" tmp
                "--system-prompt" system "--" prompt)
            dur (- (System/currentTimeMillis) t0)
            _ (do (println "=== omp" (name role) "exit" exit "===")
                  (when (seq err) (println "--- stderr ---\n" err)))
            _ (when num (post-transcript! cfg num out))
            result (cond
                     (and writes? (wiki/ahead? repo base branch))
                     (try
                       (wiki/push-branch! cfg repo branch)
                       (let [pr (gh/create-pr! cfg {:title (:title issue)
                                                    :head  branch :base base
                                                    :body  (pr-body issue)})]
                         (when (:item-id issue)
                           (try (projects/set-status! cfg (get (projects/find-project cfg) "id") (:item-id issue)
                                                      (get-in cfg [:projects :review-status] "Done"))
                                (catch Throwable _ nil)))
                         {:issue num :branch branch :pr (get pr "html_url")})
                       (catch Throwable e {:issue num :branch branch :error (.getMessage e)}))
                     writes? {:issue num :no-write true}
                     :else   {:issue num :done true})]
        (budget/report)
        (when num
          (try (gh/comment-issue! cfg num
                 (cond (:pr result)       (str "✅ done · PR " (:pr result) "\n\n" (work-summary))
                       (:error result)    (str "❌ push/PR failed: " (:error result))
                       (:no-write result) "ⓘ nothing written (no card changed)"
                       :else              (str "✅ done\n\n" (work-summary))))
               (catch Throwable _ nil)))
        (when num (post-stats! cfg num (or (:model spec) (get-in cfg [:omp :model])) dur out))
        (when (and num (:item-id issue) (not writes?) (nil? (:error result)))
          (try (projects/set-status! cfg (get (projects/find-project cfg) "id") (:item-id issue)
                                     (get-in cfg [:projects :done-status] "Done"))
               (catch Throwable _ nil)))
        result)
      (finally (reset! task/current nil)))))
