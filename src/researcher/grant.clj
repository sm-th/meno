(ns researcher.grant
  "The capability GRANT into the long-lived shared image (zeno-style). The agent
   reaches it through ONE `eval`; only GRANTED symbols exist (SCI deny-by-default).
   Grant is PROFILE-scoped (planner vs worker) and, for writes, BRANCH-scoped.
   Limits (WIP cap) are enforced live in code inside granted fns. The image
   outlives sessions; secrets/privilege live here, not in the omp session."
  (:require [sci.core :as sci]
            [clojure.string :as str]
            [researcher.index :as index]
            [researcher.reader :as reader]
            [researcher.search :as search]
            [researcher.graph :as graph]
            [researcher.github :as gh]
            [researcher.projects :as projects]
            [researcher.wiki :as wiki]))

(defn- hit->clj [h]
  (let [p (get h "payload")]
    {:score (get h "score") :kind (get p "kind") :title (get p "title")
     :url (get p "url") :source (get p "source")}))

(defn- conventions-url [cfg]
  (str "https://github.com/" (get-in cfg [:github :repo])
       "/blob/" (get-in cfg [:wiki :base] "main")
       "/content/" (get-in cfg [:wiki :conventions] "conventions") ".md"))

(defn- task-issue-body [cfg task]
  ;; The card contract lives once as the wiki Conventions card (linked in the
  ;; footer), not restated per task; acceptance is intentionally not rendered.
  ;; Structured for human triage: why the concept matters + a verbatim quote
  ;; from the seed note. Accept :rationale or :why (models vary).
  (let [rationale (str/trim (str (or (:rationale task) (:why task))))
        qt        (str/trim (str (or (:quote task) (:note_quote task))))
        seed      (str/trim (str (:seed_note task)))]
    (str "## Why this matters\n\n" rationale "\n"
         (when (seq qt)
           (str "\n## From the note\n\n> " (str/replace qt #"\n+" "\n> ") "\n"))
         "\n---\n\n"
         (when (seq seed) (str "**Seed:** " seed "  \n"))
         "**Conventions:** " (conventions-url cfg) "\n\n"
         "`op: " (name (or (:op task) :create))
         " · type: " (name (or (:type task) :concept)) "`")))

(defn- propose-task-fn [cfg]
  (fn [task]
    (let [rationale (str/trim (str (or (:rationale task) (:why task))))
          open (count (gh/open-issues cfg))
          cap  (get-in cfg [:planner :wip-cap])]
      (cond
        (< (count rationale) 20)
        {:refused (str "task needs a substantive :rationale — why the concept matters, "
                       "grounded in the note (>=20 chars); a bare title is not fileable")}
        (>= open cap)
        {:refused (str "queue full: " open "/" cap " open issues — triage first")}
        :else
        (let [issue (gh/create-issue cfg {:title (:title task)
                                          :body (task-issue-body cfg task)
                                          :labels [(str "type:" (name (or (:type task) :concept)))]})]
          (when-let [p (projects/find-project cfg)]
            (projects/add-to-backlog! cfg (get p "id") (get issue "node_id")))
          {:filed (get issue "number") :title (:title task)})))))

(defn build
  "Build the SCI grant context. world:
     :profile   :planner | :worker (default :worker)
     :wiki-repo :branch  enable writes (worker)
     :dry?      true -> propose-task! is a no-op (bench: never touch GitHub)."
  [cfg {:keys [profile wiki-repo branch dry?] :or {profile :worker}}]
  (let [w (when (and wiki-repo branch) (wiki/writer cfg wiki-repo branch))
        read-fns
        {'recall  (fn ([q] (mapv hit->clj (index/recall cfg q 8)))
                    ([q k] (mapv hit->clj (index/recall cfg q k))))
         'fetch   (fn [url] (reader/readable url))
         'search  (fn [q] (search/web cfg q))
         'central (fn [n] (graph/central (graph/load-graph cfg) n))
         'reference-frequency (fn [] (graph/reference-frequency (graph/load-graph cfg)))
         'context (fn [] {:model (get-in cfg [:omp :model]) :profile profile :branch branch})
         'open-tasks (fn [] (mapv (fn [i] {:number (get i "number") :title (get i "title")})
                                  (gh/open-issues cfg)))}
        plan-fns  {'propose-task! (if dry?
                                    (fn [task] {:filed :dry :title (:title task)})
                                    (propose-task-fn cfg))
                   'enrich-task! (if dry?
                                   (fn [n _] {:enriched :dry :number n})
                                   (fn [n add]
                                     (let [cur  (str (get (gh/get-issue cfg n) "body"))
                                           note (str/trim (str add))]
                                       (gh/update-issue! cfg n {:body (str cur "\n\n---\n*Researcher note:* " note)})
                                       {:enriched n})))}
        write-fns (when w
                    {'put-concept!
                     (fn [page]
                       (let [slug (wiki/slugify (:title page))
                             f    (java.io.File. (str wiki-repo "/" (wiki/card-rel :concept slug)))]
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
