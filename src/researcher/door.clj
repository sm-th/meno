(ns researcher.door
  "zeno-style SCI 'door': the agent reaches the shared image through ONE eval;
   only GRANTED symbols exist (SCI deny-by-default). Grant is PROFILE-scoped
   (planner vs worker) and, for writes, BRANCH-scoped. The agent composes our
   capabilities in Clojure (CodeAct) rather than one tool-call per step.
   Limits (WIP cap, per-run) are enforced in code inside the granted fns."
  (:require [sci.core :as sci]
            [clojure.string :as str]
            [researcher.index :as index]
            [researcher.reader :as reader]
            [researcher.graph :as graph]
            [researcher.github :as gh]
            [researcher.wiki :as wiki]))

(defn- hit->clj [h]
  (let [p (get h "payload")]
    {:score (get h "score") :kind (get p "kind") :title (get p "title")
     :url (get p "url") :source (get p "source")}))

(defn- task-issue-body [task]
  (str (:rationale task) "\n\n"
       "Acceptance:\n" (str/join "\n" (map #(str "- " %) (:acceptance task))) "\n\n"
       "Seed: " (:seed_note task) "\n"
       (when (seq (:suggested_sources task))
         (str "Suggested sources: " (str/join ", " (:suggested_sources task)) "\n"))
       "\n`op: " (name (or (:op task) :create)) " / type: " (name (or (:type task) :concept)) "`"))

(defn grant
  "Build the SCI context. world:
     :profile   :planner | :worker (default :worker)
     :wiki-repo :branch  enable writes (worker)."
  [cfg {:keys [profile wiki-repo branch] :or {profile :worker}}]
  (let [run-count (atom 0)
        w (when (and wiki-repo branch) (wiki/writer cfg wiki-repo branch))
        propose-task!
        (fn [task]
          (let [open (count (gh/open-issues cfg))
                cap  (get-in cfg [:planner :wip-cap])
                maxn (get-in cfg [:planner :max-new-per-run])]
            (cond
              (>= open cap)        {:refused (str "queue full: " open "/" cap " open issues")}
              (>= @run-count maxn) {:refused (str "per-run limit reached: " maxn)}
              :else
              (let [issue (gh/create-issue cfg {:title (:title task)
                                                :body (task-issue-body task)
                                                :labels ["stage:proposed"
                                                         (str "type:" (name (or (:type task) :concept)))]})]
                (swap! run-count inc)
                {:filed (get issue "number") :title (:title task)}))))
        read-fns
        {'recall  (fn [q k] (mapv hit->clj (index/recall cfg q k)))
         'fetch   (fn [url] (reader/readable url))
         'central (fn [n] (graph/central (graph/load-graph cfg) n))
         'reference-frequency (fn [] (graph/reference-frequency (graph/load-graph cfg)))
         'context (fn [] {:model (get-in cfg [:omp :model]) :profile profile :branch branch})}
        plan-fns  {'propose-task! propose-task!}
        write-fns (when w
                    {'put-concept!   (fn [page] (wiki/put-page! w (assoc page :type :concept)))
                     'put-reference! (fn [page] (wiki/put-page! w (assoc page :type :reference)))})
        ns-map (case profile
                 :planner (merge read-fns plan-fns)
                 (merge read-fns plan-fns write-fns))]
    (sci/init {:namespaces {'user ns-map}})))

(defn eval-ctx
  "Evaluate agent code against a prebuilt grant context (reused across calls so
   per-run counters persist)."
  [ctx code]
  (sci/eval-string* ctx code))

(defn eval-str
  ([cfg code] (eval-str cfg {} code))
  ([cfg world code] (eval-ctx (grant cfg world) code)))

(defn demo [cfg]
  (println "context =>" (eval-str cfg "(context)"))
  (println "recall  =>" (eval-str cfg "(mapv :title (recall \"microVM sandbox\" 3))"))
  (println "slurp   =>"
           (try (eval-str cfg "(slurp \"/etc/passwd\")")
                (catch Throwable t (str "BLOCKED: " (.getMessage t))))))
