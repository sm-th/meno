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

(defn- concept-body
  "Body for an `Add concept: X` task — only what is SPECIFIC to the source that
   raised it: Context (why/how it frames the concept), verbatim Quotes, Angle.
   HOW to write the card lives in the INVESTIGATE prompt, not here."
  [{:keys [rationale why quotes angle seed_note]}]
  (let [ctx (str/trim (str (or rationale why)))
        qs  (->> quotes (map #(str/trim (str %))) (remove str/blank?))
        ang (str/trim (str angle))
        src (str/trim (str seed_note))]
    (str "## Context\n\n" ctx "\n"
         (when (seq qs)
           (str "\n## From the source\n\n" (str/join "\n" (map #(str "> " %) qs)) "\n"))
         (when-not (str/blank? ang) (str "\n## Angle\n\n" ang "\n"))
         "\n## References\n\n"
         (when-not (str/blank? src) (str "- Source: " src "\n"))
         (process/practice-ref :research))))

(defn- research-body
  "Body for a `Research: <question>` task — the open question to investigate:
   Context (why it matters / how the source raises it), Angle, optional Goals."
  [{:keys [rationale context why angle goals seed_note]}]
  (let [ctx (str/trim (str (or rationale context why)))
        ang (str/trim (str angle))
        gs  (->> goals (map #(str/trim (str %))) (remove str/blank?))
        src (str/trim (str seed_note))]
    (str "## Context\n\n" ctx "\n"
         (when-not (str/blank? ang) (str "\n## Angle\n\n" ang "\n"))
         (when (seq gs) (str "\n## Goals\n\n" (str/join "\n" (map #(str "- " %) gs)) "\n"))
         "\n## References\n\n"
         (when-not (str/blank? src) (str "- Source: " src "\n"))
         (process/practice-ref :research))))

(defn- ref-body
  "Body for an `Ingest: <url>` task — a source worth READ+ingest. Same Source/Context
   shape as a hand-filed ingest task, plus the stage line."
  [{:keys [url seed_note context rationale why]}]
  (let [src (str/trim (str (or url seed_note)))
        ctx (str/trim (str (or context rationale why)))]
    (str "## Source\n\n" src "\n\n"
         "## Context\n\n"
         (if (str/blank? ctx) "_(none given — judge from the page itself)_" ctx) "\n\n"
         (process/practice-ref :ingest))))

(defn- file-task!
  "Create ONE Backlog task (or, under dry?, print it). type/role set the labels;
   index-text feeds the dedup embedding. Enforces the WIP cap live in code."
  [cfg dry? {:keys [title type role body index-text]}]
  (let [labels [(str "type:" (name type)) (str "role:" (name role))]]
    (if dry?
      (do (println (str "\n===== DRY " (name type) " task =====\nTITLE: " title
                        "\nLABELS: " (str/join " " labels)
                        "\n----- BODY -----\n" body
                        "\n=============================="))
          (flush)
          {:filed :dry :title title})
      (let [open (count (gh/open-issues cfg))
            cap  (get-in cfg [:planner :wip-cap])]
        (if (>= open cap)
          {:refused (str "queue full: " open "/" cap " open issues — triage first")}
          (let [issue (gh/create-issue cfg {:title title :body body :labels labels})]
            (when-let [p (projects/find-project cfg)]
              (projects/add-to-backlog! cfg (get p "id") (get issue "node_id")))
            (try (index/index-task! cfg {:number (get issue "number") :title title
                                         :rationale (str index-text) :url (get issue "html_url")})
                 (catch Throwable _ nil))
            {:filed (get issue "number") :title title}))))))

(def ^:private tool-docs
  {"recall" "(recall q [k]) — semantic search across the corpus and existing cards"
   "fetch" "(fetch url) — readable text of an external web page (also cites it)"
   "search" "(search q) — web search; returns candidate source URLs"
   "central" "(central n) — the n most-linked pages in the wiki graph"
   "reference-frequency" "(reference-frequency) — most-cited source URLs"
   "open-tasks" "(open-tasks) — [{:number :title}] tasks already queued"
   "propose-concept!" "(propose-concept! {:title :rationale :quotes :angle :seed_note}) — file a task to write a concept card (title is the canonical name)"
   "propose-research!" "(propose-research! {:question :rationale :angle :goals :seed_note}) — file a task to research an open question; INVESTIGATE writes a cited answer card"
   "propose-reference!" "(propose-reference! {:url :context}) — file a task to READ+ingest a source into a reference card"
   "enrich-task!" "(enrich-task! n md) — append a note to an open task"
   "put-concept!" "(put-concept! {:title :description :tags :body :sources}) — write the canonical concept card"
   "put-connection!" "(put-connection! {:title :tags :body :seed :sources}) — write a connection card"
   "put-answer!" "(put-answer! {:title :tags :body :seed :sources}) — write an answer card: a claim answering a question, with cited grounds"
   "put-reference!" "(put-reference! {:title :url :author :date :kind :tags :body :sources}) — write a reference card; :url (required), :author, :date, :kind land in the frontmatter for later parsing"
   "check-zettel" "(check-zettel {:type :title :body}) — recursively run a Zettelkasten editor over a proposed card; returns OK or a list of fixes"})

(defn build
  "Build the SCI grant for a ROLE. world:
     :role      keyword/string — selects tools (cfg :roles) and labels context
     :wiki-repo :branch  enable write fns (branch-scoped)
     :dry?      true -> the propose-*!/enrich-task! writers print instead (bench)."
  [cfg {:keys [role wiki-repo branch dry?] :or {role :research}}]
  (let [role  (keyword role)
        dry?  (or dry? @task/dry)
        w     (when (and wiki-repo branch) (wiki/writer cfg wiki-repo branch))
        registry
        {"recall"  (fn ([q] (mapv hit->clj (index/recall cfg q 8)))
                     ([q k] (mapv hit->clj (index/recall cfg q k))))
         "fetch"   (fn [url] (reader/readable url))
         "search"  (fn [q] (search/web cfg q))
         "central" (fn [n] (graph/central (graph/load-graph cfg) n))
         "reference-frequency" (fn [] (graph/reference-frequency (graph/load-graph cfg)))
         "open-tasks" (fn [] (mapv (fn [i] {:number (get i "number") :title (get i "title")})
                                   (gh/open-issues cfg)))
         "propose-concept!"
         (fn [m]
           (let [ctx (str/trim (str (or (:rationale m) (:why m))))]
             (if (< (count ctx) 20)
               {:refused (str "a concept task needs a substantive :rationale (>=20 chars): why this "
                              "concept deserves a card and how the source frames it")}
               (file-task! cfg dry? {:title (str "Add concept: " (str/trim (str (:title m))))
                                     :type :concept :role :research
                                     :body (concept-body m) :index-text ctx}))))
         "propose-research!"
         (fn [m]
           (let [q   (str/trim (str (or (:question m) (:title m))))
                 ctx (str/trim (str (or (:rationale m) (:context m) (:why m))))]
             (if (or (str/blank? q) (< (count ctx) 20))
               {:refused (str "a research task needs a :question and a substantive :rationale "
                              "(>=20 chars): why it matters and how the source raises it")}
               (file-task! cfg dry? {:title (str "Research: " q)
                                     :type :research :role :research
                                     :body (research-body m) :index-text (str q " " ctx)}))))
         "propose-reference!"
         (fn [m]
           (let [url (str/trim (str (or (:url m) (:seed_note m))))]
             (if (str/blank? url)
               {:refused "a reference task needs a :url to ingest"}
               (file-task! cfg dry? {:title (str "Ingest: " url)
                                     :type :reference :role :ingest
                                     :body (ref-body m)
                                     :index-text (str url " " (or (:context m) (:rationale m)))}))))
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
                              (let [slug    (wiki/slugify (:title page))
                                    f       (java.io.File. (str wiki-repo "/" (wiki/card-rel :concept slug)))
                                    existed (.exists f)
                                    body    (str (:body page))
                                    cap     (get-in cfg [:wiki :concept-body-max] 900)]
                                (if (> (count body) cap)
                                  {:rejected slug :reason (str "concept body is " (count body) " chars > " cap
                                                               " — a concept card is a SHORT definition; cut it and move the depth into separate question tasks")}
                                  ;; overwrite allowed: a later research legitimately amends an earlier
                                  ;; card; the PR shows the diff for human review.
                                  (assoc (wiki/put-page! w (assoc page :type :concept)) :amended existed))))))
         "put-connection!" (when w (fn [page] (if dry? (do (println (str "\n===== DRY put-connection! -> " (wiki/card-rel :connection (wiki/slugify (:title page))) " =====\n" (wiki/render (assoc page :type :connection)) "\n==============================")) (flush) {:dry :connection :title (:title page)}) (wiki/put-page! w (assoc page :type :connection)))))
         "put-answer!"     (when w (fn [page] (if dry? (do (println (str "\n===== DRY put-answer! -> " (wiki/card-rel :answer (wiki/slugify (:title page))) " =====\n" (wiki/render (assoc page :type :answer)) "\n==============================")) (flush) {:dry :answer :title (:title page)}) (wiki/put-page! w (assoc page :type :answer)))))
         "put-reference!"  (when w (fn [page]
                             (if (str/blank? (str (:url page)))
                               {:rejected (:title page) :reason "a reference card needs a :url — it goes in the frontmatter so the source is machine-parsable"}
                               (if dry?
                                 (do (println (str "\n===== DRY put-reference! -> " (wiki/card-rel :reference (wiki/slugify (:title page))) " =====\n" (wiki/render (assoc page :type :reference)) "\n==============================")) (flush) {:dry :reference :title (:title page)})
                                 (wiki/put-page! w (assoc page :type :reference))))))
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
