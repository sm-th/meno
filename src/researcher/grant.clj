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
            [researcher.wiki :as wiki]
            [researcher.prompt :as prompt]))

(defn- hit->clj [h]
  (let [p (get h "payload")]
    {:score (get h "score") :kind (get p "kind") :title (get p "title")
     :url (get p "url") :source (get p "source")}))

(defn- conventions-url [cfg]
  (str "https://github.com/" (get-in cfg [:github :repo])
       "/blob/" (get-in cfg [:wiki :base] "main")
       "/content/" (get-in cfg [:wiki :conventions] "conventions") ".md"))

(defn- task-issue-body [cfg task]
  ;; A task issue is a self-contained spec — for human triage AND the agent: why
  ;; it matters (with the note's words quoted inline) and a Definition of Done: the
  ;; concrete, checkable criteria (the :goals). The HOW lives in the role's skill;
  ;; the standing card contract lives in the Conventions card (linked in References).
  (let [rationale (str/trim (str (or (:rationale task) (:why task))))
        goals     (->> (:goals task) (map #(str/trim (str %))) (remove str/blank?))
        seed      (str/trim (str (:seed_note task)))
        role      (keyword (or (:role task) :research))]
    (str "## Why this matters\n\n" rationale "\n"
         (when (seq goals)
           (str "\n## Definition of Done\n\n"
                (str/join "\n" (map #(str "- [ ] " %) goals)) "\n"))
         "\n## References\n\n"
         (when (seq seed) (str "- Seed: " seed "\n"))
         (prompt/skill-ref cfg role) "\n"
         "- Conventions: " (conventions-url cfg) "\n\n"
         "`op: " (name (or (:op task) :create))
         " · type: " (name (or (:type task) :concept)) "`")))

(defn- propose-task-fn [cfg child]
  (fn [task]
    (let [rationale (str/trim (str (or (:rationale task) (:why task))))
          goals (->> (:goals task) (map #(str/trim (str %))) (remove str/blank?))
          open  (count (gh/open-issues cfg))
          cap   (get-in cfg [:planner :wip-cap])]
      (cond
        (< (count rationale) 20)
        {:refused (str "task needs a substantive :rationale — why the concept matters, with the "
                       "note's words quoted inline (>=20 chars); a bare title is not fileable")}
        (empty? goals)
        {:refused (str "task needs :goals — 2-4 concrete research questions that pin the subject and "
                       "what the card must establish about it")}
        (>= open cap)
        {:refused (str "queue full: " open "/" cap " open issues — triage first")}
        :else
        (let [role  (keyword (or (:role task) child))
              issue (gh/create-issue cfg {:title (:title task)
                                          :body (task-issue-body cfg (assoc task :role role))
                                          :labels [(str "type:" (name (or (:type task) :concept)))
                                                   (str "role:" (name role))]})]
          (when-let [p (projects/find-project cfg)]
            (projects/add-to-backlog! cfg (get p "id") (get issue "node_id")))
          {:filed (get issue "number") :title (:title task)})))))

(def ^:private tool-docs
  {"recall" "(recall q [k]) — semantic search across the corpus and existing cards"
   "fetch" "(fetch url) — readable text of an external web page (also cites it)"
   "search" "(search q) — web search; returns candidate source URLs"
   "central" "(central n) — the n most-linked pages in the wiki graph"
   "reference-frequency" "(reference-frequency) — most-cited source URLs"
   "open-tasks" "(open-tasks) — [{:number :title}] tasks already queued"
   "propose-task!" "(propose-task! {:title :rationale :goals :seed_note :role}) — file a task"
   "enrich-task!" "(enrich-task! n md) — append a note to an open task"
   "put-concept!" "(put-concept! {:title :description :tags :body :sources}) — write the canonical concept card"
   "put-connection!" "(put-connection! {:title :tags :body :seed :sources}) — write a connection card"
   "put-reference!" "(put-reference! {:title :tags :body :sources}) — write a reference card"})

(defn build
  "Build the SCI grant for a ROLE. world:
     :role      keyword/string — selects tools (cfg :roles) and labels context
     :wiki-repo :branch  enable write fns (branch-scoped)
     :creates   default role of tasks filed via propose-task! (else role's :creates)
     :dry?      true -> propose-task!/enrich-task! are no-ops (bench)."
  [cfg {:keys [role wiki-repo branch dry? creates] :or {role :research}}]
  (let [role  (keyword role)
        w     (when (and wiki-repo branch) (wiki/writer cfg wiki-repo branch))
        child (or creates (get-in cfg [:roles role :creates]) "research")
        registry
        {"recall"  (fn ([q] (mapv hit->clj (index/recall cfg q 8)))
                     ([q k] (mapv hit->clj (index/recall cfg q k))))
         "fetch"   (fn [url] (reader/readable url))
         "search"  (fn [q] (search/web cfg q))
         "central" (fn [n] (graph/central (graph/load-graph cfg) n))
         "reference-frequency" (fn [] (graph/reference-frequency (graph/load-graph cfg)))
         "open-tasks" (fn [] (mapv (fn [i] {:number (get i "number") :title (get i "title")})
                                   (gh/open-issues cfg)))
         "propose-task!" (if dry?
                           (fn [task] {:filed :dry :title (:title task)})
                           (propose-task-fn cfg child))
         "enrich-task!" (if dry?
                          (fn [n _] {:enriched :dry :number n})
                          (fn [n add]
                            (let [cur  (str (get (gh/get-issue cfg n) "body"))
                                  note (str/trim (str add))]
                              (gh/update-issue! cfg n {:body (str cur "\n\n---\n*Researcher note:* " note)})
                              {:enriched n})))
         "put-concept!" (when w
                          (fn [page]
                            (let [slug (wiki/slugify (:title page))
                                  f    (java.io.File. (str wiki-repo "/" (wiki/card-rel :concept slug)))]
                              (if (.exists f)
                                {:skipped slug :reason "canonical concept card already exists — not rewritten"}
                                (wiki/put-page! w (assoc page :type :concept))))))
         "put-connection!" (when w (fn [page] (wiki/put-page! w (assoc page :type :connection))))
         "put-reference!"  (when w (fn [page] (wiki/put-page! w (assoc page :type :reference))))}
        wanted (get-in cfg [:roles role :tools])
        chosen (if (seq wanted) wanted (keys registry))
        granted (vec (for [t chosen :when (get registry t)] t))
        ns-map (into {'context (fn [] {:model (get-in cfg [:omp :model]) :role (name role) :branch branch})
                      'tools   (fn [] (into ["(context) — your role and branch" "(tools) — this list"]
                                            (map #(get tool-docs % (str "(" % ")")) granted)))}
                     (for [t granted] [(symbol t) (get registry t)]))]
    (sci/init {:namespaces {'user ns-map}})))

(defn eval-ctx [ctx code] (sci/eval-string* ctx code))

(defn eval-str
  ([cfg code] (eval-str cfg {} code))
  ([cfg world code] (eval-ctx (build cfg world) code)))
