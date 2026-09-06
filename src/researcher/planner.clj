(ns researcher.planner
  "Stage 1 (PLANNER), objective-wiki edition. From a seed note + dense-recall
   context, ask omp/Sonnet (headless, no tools) to propose ONE objective CONCEPT
   worth a wiki page, and file it as a GitHub issue — but only while the review
   queue is small (WIP cap) and at most :max-new-per-run per run."
  (:require [researcher.git :as git]
            [researcher.note :as note]
            [researcher.index :as index]
            [researcher.github :as gh]
            [researcher.budget :as budget]
            [clojure.java.shell :refer [sh]]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(def system-prompt
  (str "You are the PLANNING stage of an OBJECTIVE auto-researcher wiki. The wiki "
       "holds only common, well-established knowledge (concepts and theories), "
       "SELECTED for relevance to Andy Smith's notes — never his opinions or "
       "personal theses. From the seed note and related corpus, choose ONE "
       "googleable, objective CONCEPT worth its own wiki page and not obviously "
       "already covered. Do not research now; only name the concept and why it is "
       "relevant, objectively.\n\n"
       "Output ONLY an EDN map, nothing else:\n"
       "{:op :create :type :concept\n"
       " :title \"the concept name\"\n"
       " :rationale \"why it matters, objectively, and how the seed note points to it\"\n"
       " :acceptance [\"checkable done-conditions for the page\"]\n"
       " :seed_note \"<seed url>\"\n"
       " :suggested_sources [\"url\" ...]}\n"
       "If nothing new is worth adding, output exactly :skip"))

(defn- omp-complete
  "One headless omp completion (no tools, ephemeral). Returns stdout text."
  [cfg system user]
  (let [model (get-in cfg [:omp :model])
        {:keys [exit out err]}
        (sh "omp" "-p" "--no-tools" "--no-session" "--no-title"
            "--model" model "--system-prompt" system :in user)]
    (budget/add! :llm {:tokens 0 :usd 0.0})
    (budget/check! (:limits cfg))
    (if (zero? exit)
      out
      (throw (ex-info "omp failed" {:exit exit :err err})))))

(defn- extract-edn [s]
  (let [i (.indexOf s "{") j (.lastIndexOf s "}")]
    (when (and (>= i 0) (> j i)) (subs s i (inc j)))))

(defn- parse-task [out]
  (cond
    (re-find #":skip" out) :skip
    :else (try (some-> (extract-edn out) edn/read-string) (catch Exception _ nil))))

(defn- seed-context [cfg]
  (let [blog (get-in cfg [:blog :root])
        {:keys [sha]} (git/newest-publish blog)
        rel  (first (git/commit-post-files blog sha))
        n    (note/load-note blog rel)
        hits (index/recall cfg (str (:title n) "\n" (:body n)) 8)]
    {:note n :hits hits}))

(defn- render-user [note hits]
  (str "SEED NOTE\nTITLE: " (:title note) "\nURL: " (:url note) "\n\n" (:body note)
       "\n\nRELATED CORPUS (dense recall):\n"
       (str/join "\n" (map #(str "  - [" (get-in % ["payload" "kind"]) "] "
                                 (get-in % ["payload" "title"]))
                           hits))))

(defn- issue-body [task]
  (str (:rationale task) "\n\n"
       "Acceptance:\n"
       (str/join "\n" (map #(str "- " %) (:acceptance task))) "\n\n"
       "Seed: " (:seed_note task) "\n"
       "Suggested sources: " (str/join ", " (:suggested_sources task)) "\n\n"
       "`op: " (name (:op task)) " / type: " (name (:type task)) "`"))

(defn run [cfg]
  (budget/reset-run!)
  (let [open (gh/open-issues cfg)
        cap  (get-in cfg [:planner :wip-cap])]
    (if (>= (count open) cap)
      (println "queue full:" (count open) "open issues (cap" cap ") — staying silent")
      (let [{:keys [note hits]} (seed-context cfg)
            out  (omp-complete cfg system-prompt (render-user note hits))
            task (parse-task out)]
        (cond
          (= task :skip) (println "planner: nothing new worth adding")
          (map? task)
          (let [issue (gh/create-issue cfg {:title (:title task)
                                            :body (issue-body task)
                                            :labels ["stage:proposed" "type:concept"]})]
            (println "filed issue #" (get issue "number") "—" (:title task)))
          :else (println "planner: could not parse omp output:\n" out))
        (budget/report)))))
