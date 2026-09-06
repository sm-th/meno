(ns researcher.grant
  "The capability GRANT into the long-lived shared image (zeno-style). The agent
   reaches it through ONE `eval`; only GRANTED symbols exist (SCI deny-by-default).
   Grant is PROFILE-scoped (planner vs worker) and, for writes, BRANCH-scoped.
   Limits (WIP cap) are enforced live in code inside granted fns. The image
   outlives sessions; secrets/privilege live here, not in the omp session."
  (:require [sci.core :as sci]
            [clojure.string :as str]
            [researcher.index :as index]
            [researcher.linkwarden :as lw]
            [researcher.search :as search]
            [researcher.graph :as graph]
            [researcher.github :as gh]
            [researcher.projects :as projects]
            [researcher.wiki :as wiki]))

(defn- hit->clj [h]
  (let [p (get h "payload")]
    {:score (get h "score") :kind (get p "kind") :title (get p "title")
     :url (get p "url") :source (get p "source")}))

(defn- task-issue-body [task]
  (str (:rationale task) "\n\n"
       (when (seq (:acceptance task))
         (str "Acceptance (specific to this concept):\n"
              (str/join "\n" (map #(str "- " %) (:acceptance task))) "\n\n"))
       "Seed: " (:seed_note task) "\n"
       "\n`op: " (name (or (:op task) :create)) " / type: " (name (or (:type task) :concept)) "`"))

(defn- propose-task-fn [cfg]
  (fn [task]
    (let [open (count (gh/open-issues cfg))
          cap  (get-in cfg [:planner :wip-cap])]
      (if (>= open cap)
        {:refused (str "queue full: " open "/" cap " open issues — triage first")}
        (let [issue (gh/create-issue cfg {:title (:title task)
                                          :body (task-issue-body task)
                                          :labels ["stage:proposed"
                                                   (str "type:" (name (or (:type task) :concept)))]})]
          (when-let [p (projects/find-project cfg)]
            (let [item (projects/add-issue! cfg (get p "id") (get issue "node_id"))]
              (projects/clear-status! cfg (get p "id") item)))
          {:filed (get issue "number") :title (:title task)})))))

(defn build
  "Build the SCI grant context. world:
     :profile   :planner | :worker (default :worker)
     :wiki-repo :branch  enable writes (worker)."
  [cfg {:keys [profile wiki-repo branch] :or {profile :worker}}]
  (let [w (when (and wiki-repo branch) (wiki/writer cfg wiki-repo branch))
        read-fns
        {'recall  (fn [q k] (mapv hit->clj (index/recall cfg q k)))
         'fetch   (fn [url] (:text (lw/fetch-readable! cfg url {:tags ["research"]})))
         'search  (fn [q] (search/web cfg q))
         'central (fn [n] (graph/central (graph/load-graph cfg) n))
         'reference-frequency (fn [] (graph/reference-frequency (graph/load-graph cfg)))
         'context (fn [] {:model (get-in cfg [:omp :model]) :profile profile :branch branch})}
        plan-fns  {'propose-task! (propose-task-fn cfg)}
        write-fns (when w
                    {'put-concept!
                     (fn [page]
                       (let [slug (wiki/slugify (:title page))
                             f    (java.io.File. (str wiki-repo "/content/" slug ".md"))]
                         (if (.exists f)
                           {:skipped slug :reason "canonical concept card already exists — not rewritten"}
                           (wiki/put-page! w (assoc page :type :concept)))))
                     'put-connection!
                     (fn [page] (wiki/put-page! w (assoc page :type :connection)))
                     'put-reference!
                     (fn [page] (wiki/put-page! w (assoc page :type :reference)))})
        ns-map (case profile
                 :planner (merge read-fns plan-fns)
                 (merge read-fns plan-fns write-fns))]
    (sci/init {:namespaces {'user ns-map}})))

(defn eval-ctx [ctx code] (sci/eval-string* ctx code))

(defn eval-str
  ([cfg code] (eval-str cfg {} code))
  ([cfg world code] (eval-ctx (build cfg world) code)))
