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
            [researcher.process :as process]
            [researcher.task :as task]
            [clojure.java.shell :refer [sh]]))

(defn- hit->clj [h]
  (let [p (get h "payload")]
    {:score (get h "score") :kind (get p "kind") :title (get p "title")
     :number (get p "number") :url (get p "url") :source (get p "source")}))

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
         (process/practice-ref role) "\n\n"
         "`op: " (name (or (:op task) :create))
         " · type: " (name (or (:type task) :seed)) "`")))

(defn- propose-task-fn [cfg child]
  (fn [task]
    (let [rationale (str/trim (str (or (:rationale task) (:why task))))
          goals (->> (:goals task) (map #(str/trim (str %))) (remove str/blank?))
          open  (count (gh/open-issues cfg))
          cap   (get-in cfg [:planner :wip-cap])]
      (cond
        (< (count rationale) 20)
        {:refused (str "seed needs a substantive :rationale — why this is worth researching, with the "
                       "note's words quoted inline (>=20 chars); a bare title is not fileable")}
        (empty? goals)
        {:refused (str "seed needs :goals — 2-4 concrete research questions that pin the subject and "
                       "what a good result must establish")}
        (>= open cap)
        {:refused (str "queue full: " open "/" cap " open issues — triage first")}
        :else
        (let [role  (keyword (or (:role task) child))
              issue (gh/create-issue cfg {:title (:title task)
                                          :body (task-issue-body cfg (assoc task :role role))
                                          :labels [(str "type:" (name (or (:type task) :seed)))
                                                   (str "role:" (name role))]})]
          (when-let [p (projects/find-project cfg)]
            (projects/add-to-backlog! cfg (get p "id") (get issue "node_id")))
          (try (index/index-task! cfg {:number (get issue "number") :title (:title task)
                                       :rationale rationale :url (get issue "html_url")})
               (catch Throwable _ nil))
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
   "put-answer!" "(put-answer! {:title :tags :body :seed :sources}) — write an answer card: a claim answering a question, with cited grounds"
   "put-reference!" "(put-reference! {:title :tags :body :sources}) — write a reference card"
   "check-zettel" "(check-zettel {:type :title :body}) — recursively run a Zettelkasten editor over a proposed card; returns OK or a list of fixes"})

(defn build
  "Build the SCI grant for a ROLE. world:
     :role      keyword/string — selects tools (cfg :roles) and labels context
     :wiki-repo :branch  enable write fns (branch-scoped)
     :creates   default role of tasks filed via propose-task! (else role's :creates)
     :dry?      true -> propose-task!/enrich-task! are no-ops (bench)."
  [cfg {:keys [role wiki-repo branch dry? creates] :or {role :research}}]
  (let [role  (keyword role)
        dry?  (or dry? @task/dry)
        w     (when (and wiki-repo branch) (wiki/writer cfg wiki-repo branch))
        child (or creates (:creates (process/spec role)) :research)
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
                           (fn [task]
                             (println (str "\n===== DRY propose-task! =====\nTITLE: " (:title task)
                                           "\nLABELS: type:" (name (or (:type task) :seed)) " role:" (name child)
                                           "\n----- BODY -----\n" (task-issue-body cfg (assoc task :role child))
                                           "\n=============================="))
                             (flush)
                             {:filed :dry :title (:title task)})
                           (propose-task-fn cfg child))
         "enrich-task!" (if dry?
                          (fn [n _] {:enriched :dry :number n})
                          (fn [n add]
                            (let [gi   (gh/get-issue cfg n)
                                  cur  (str (get gi "body"))
                                  note (str/trim (str add))]
                              (gh/update-issue! cfg n {:body (str cur "\n\n---\n*Researcher note:* " note)})
                              (try (index/index-task! cfg {:number n :title (get gi "title")
                                                           :rationale (str cur " " note) :url (get gi "html_url")})
                                   (catch Throwable _ nil))
                              {:enriched n})))
         "put-concept!" (when w
                          (fn [page]
                            (if dry?
                              (do (println (str "\n===== DRY put-concept! -> "
                                                (wiki/card-rel :concept (wiki/slugify (:title page))) " =====\n"
                                                (wiki/render (assoc page :type :concept))
                                                "\n==============================")) (flush)
                                  {:dry :concept :title (:title page)})
                              (let [slug (wiki/slugify (:title page))
                                    f    (java.io.File. (str wiki-repo "/" (wiki/card-rel :concept slug)))
                                    body (str (:body page))
                                    cap  (get-in cfg [:wiki :concept-body-max] 900)]
                                (cond
                                  (.exists f)
                                  {:skipped slug :reason "canonical concept card already exists — not rewritten"}
                                  (> (count body) cap)
                                  {:rejected slug :reason (str "concept body is " (count body) " chars > " cap
                                                               " — a concept card is a SHORT definition; cut it and move the depth into separate question tasks")}
                                  :else
                                  (wiki/put-page! w (assoc page :type :concept)))))))
         "put-connection!" (when w (fn [page] (if dry? (do (println (str "\n===== DRY put-connection! -> " (wiki/card-rel :connection (wiki/slugify (:title page))) " =====\n" (wiki/render (assoc page :type :connection)) "\n==============================")) (flush) {:dry :connection :title (:title page)}) (wiki/put-page! w (assoc page :type :connection)))))
         "put-answer!"     (when w (fn [page] (if dry? (do (println (str "\n===== DRY put-answer! -> " (wiki/card-rel :answer (wiki/slugify (:title page))) " =====\n" (wiki/render (assoc page :type :answer)) "\n==============================")) (flush) {:dry :answer :title (:title page)}) (wiki/put-page! w (assoc page :type :answer)))))
         "put-reference!"  (when w (fn [page] (if dry? (do (println (str "\n===== DRY put-reference! -> " (wiki/card-rel :reference (wiki/slugify (:title page))) " =====\n" (wiki/render (assoc page :type :reference)) "\n==============================")) (flush) {:dry :reference :title (:title page)}) (wiki/put-page! w (assoc page :type :reference)))))
         "check-zettel"    (fn [card]
                             (let [p (str "Proposed " (name (or (:type card) :concept)) " card.\n\nTITLE: "
                                          (:title card) "\n\nBODY:\n" (str (:body card)))
                                   r (sh "omp" "-p" "--no-tools" "--no-session" "--no-title"
                                         "--model" (get-in cfg [:omp :model])
                                         "--system-prompt" process/zettel-critic "--" p)]
                               (str/trim (str (:out r)))))}
        wanted (:tools (process/spec role))
        chosen (if (seq wanted) wanted (keys registry))
        granted (vec (for [t chosen :when (get registry t)] t))
        ns-map (into {'context (fn [] {:model (or (:model (process/spec role)) (get-in cfg [:omp :model])) :role (name role) :branch branch})
                      'tools   (fn [] (into ["(context) — your role and branch" "(tools) — this list"]
                                            (map #(get tool-docs % (str "(" % ")")) granted)))}
                     (for [t granted] [(symbol t) (get registry t)]))]
    (sci/init {:namespaces {'user ns-map}})))

(defn eval-ctx [ctx code] (sci/eval-string* ctx code))

(defn eval-str
  ([cfg code] (eval-str cfg {} code))
  ([cfg world code] (eval-ctx (build cfg world) code)))
