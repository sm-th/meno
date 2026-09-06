(ns researcher.bench
  "Model bench — part of the researcher, not a separate project. Runs the REAL
   worker synthesis on fixed approved issues across a matrix of omp models, then
   judges the produced cards with a strong model against a rubric. Reuses the
   grant, prompts, gateway, and omp; the candidate models come through omp
   (`--model`). Sequential — one shared eval gateway/task at a time.

   MUST run inside the living image (the worker calls back into the gateway):
     (require 'researcher.bench)
     (researcher.bench/bench {:models [\"anthropic/claude-sonnet-4-5\"
                                       \"deepseek/deepseek-chat\"
                                       \"qwen/qwen-2.5-72b-instruct\"]
                              :issues [5]
                              :judge  \"anthropic/claude-sonnet-4-5\"})
   Writes bench/results/<ts>.md and returns {:path :rows}. Uses throwaway
   `eval/<model>/issue-N` branches; nothing is pushed."
  (:require [researcher.config :as config]
            [researcher.worker :as worker]
            [researcher.wiki :as wiki]
            [researcher.task :as task]
            [researcher.github :as gh]
            [clojure.java.shell :refer [sh]]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.java.io :as io]))

(defn- slug [s]
  (-> (str s) str/lower-case (str/replace #"[^a-z0-9]+" "-") (str/replace #"(^-+|-+$)" "")))

(def worker-rubric
  ["accuracy — claims correct, match established sources"
   "objectivity — concept card is established knowledge, no opinions or Andy"
   "citations — concrete authoritative sources cited"
   "atomicity — one idea per card, not a sprawling essay"
   "linking — dense, sensible [[wikilinks]]"
   "connection — if a connection card exists, it ties Andy's exact claim to the concept without distorting it"
   "instruction-following — followed the card contract; wrote cards, not prose"])

(defn- new-cards
  "Card files this run added on `branch` vs origin/base, with content."
  [repo base branch]
  (let [{:keys [out]} (sh "git" "-C" repo "diff" "--name-only" (str "origin/" base) branch)]
    (->> (str/split-lines (str out))
         (filter #(and (str/starts-with? % "content/") (str/ends-with? % ".md")))
         (mapv (fn [p] {:path p :content (try (slurp (str repo "/" p)) (catch Exception _ ""))})))))

(defn synthesize
  "Run the worker omp session for `issue` using `model`, writing cards to a
   throwaway per-model branch. Returns {:exit :out :cards}. No push/PR."
  [cfg issue model]
  (let [base   (get-in cfg [:wiki :base] "main")
        branch (str "eval/" (slug model) "/issue-" (:number issue))
        repo   (wiki/prepare-branch! cfg branch)
        tmp    (str (System/getProperty "java.io.tmpdir")
                    "researcher-eval-" (slug model) "-" (:number issue) "-" (System/currentTimeMillis))
        prompt (str (worker/issue-block issue)
                    "\n\nResearch the concept, then write the card(s) via (put-concept! ...).")]
    (.mkdirs (java.io.File. (str tmp "/.omp")))
    (spit (str tmp "/.omp/mcp.json") (worker/mcp-json cfg))
    (reset! task/current {:profile :worker :wiki-repo repo :branch branch :issue (:number issue)})
    (reset! task/trace [])
    (try
      (let [{:keys [exit out]}
            (sh "omp" "-p" "--no-tools" "--no-session" "--no-title"
                "--model" model "--cwd" tmp
                "--system-prompt" worker/system-prompt "--" prompt)]
        {:exit exit :out out :cards (new-cards repo base branch)})
      (finally (reset! task/current nil)))))

(defn- extract-json [s]
  (let [s (str s) i (str/index-of s "{") j (str/last-index-of s "}")]
    (when (and i j (< i j)) (subs s i (inc j)))))

(defn judge-cards
  "Score candidate cards for `issue` with a strong `judge-model` via omp (no tools)."
  [judge-model issue cards]
  (let [tmp (str (System/getProperty "java.io.tmpdir") "researcher-judge-" (System/currentTimeMillis))
        _   (.mkdirs (java.io.File. tmp))
        prompt (str "You are a strict, calibrated evaluator of an auto-researcher wiki card. "
                    "Score the candidate against each criterion 1-5 and give an overall 1-10. Be "
                    "harsh on inaccuracy, opinions on a concept card, missing citations, and prose "
                    "written instead of cards.\n\n"
                    "TASK (issue #" (:number issue) "): " (:title issue) "\n" (:body issue) "\n\n"
                    "CRITERIA:\n" (str/join "\n" (map #(str "- " %) worker-rubric)) "\n\n"
                    "CANDIDATE CARDS:\n"
                    (if (seq cards)
                      (str/join "\n\n---\n\n" (map #(str "FILE " (:path %) "\n" (:content %)) cards))
                      "(no cards were written)")
                    "\n\nReturn ONLY JSON: "
                    "{\"criteria\":{\"accuracy\":n,\"objectivity\":n,\"citations\":n,\"atomicity\":n,"
                    "\"linking\":n,\"connection\":n,\"instruction-following\":n},\"overall\":n,"
                    "\"notes\":\"<=40 words\"}.")
        {:keys [out]} (sh "omp" "-p" "--no-tools" "--no-session" "--no-title"
                          "--model" judge-model "--cwd" tmp "--" prompt)]
    (or (try (json/read-str (extract-json out)) (catch Exception _ nil))
        {"overall" 0 "notes" (str "unparseable judge output: "
                                  (subs (str out) 0 (min 160 (count (str out)))))})))

(defn- avg [rows]
  (if (seq rows) (/ (reduce + 0.0 (map #(or (:overall %) 0) rows)) (count rows)) 0.0))

(defn- report-md [judge issues rows]
  (str "# Model bench — " (java.time.Instant/now) "\n\n"
       "Judge: `" judge "`  ·  issues: " (vec issues) "  ·  runs: " (count rows) "\n\n"
       "## Aggregate (avg overall by model, 1-10)\n\n| model | avg | runs |\n|---|---|---|\n"
       (str/join "\n"
                 (for [[m rs] (sort-by (comp - avg second) (group-by :model rows))]
                   (str "| `" m "` | " (format "%.2f" (double (avg rs))) " | " (count rs) " |")))
       "\n\n## Per run\n\n| model | issue | overall | cards | notes |\n|---|---|---|---|---|\n"
       (str/join "\n"
                 (for [r (sort-by #(- (or (:overall %) 0)) rows)]
                   (str "| `" (:model r) "` | #" (:issue r) " | " (:overall r) " | "
                        (count (:cards r)) " | "
                        (str/replace (str (:notes r)) "|" "\\|") " |")))))

(defn bench
  "Matrix: each model x issue -> synthesize -> judge. opts:
     {:models [str] :issues [int] :judge str (default cfg :omp :model)}.
   Writes bench/results/<ts>.md and returns {:path :rows}."
  [{:keys [models issues judge]}]
  (let [cfg   (config/load-config)
        judge (or judge (get-in cfg [:omp :model]))
        rows  (doall
               (for [m models n issues]
                 (let [gi    (gh/get-issue cfg n)
                       issue {:number n :title (get gi "title") :body (get gi "body")}
                       _     (println "▶ synth" m "/ #" n)
                       syn   (synthesize cfg issue m)
                       _     (println "  cards" (mapv :path (:cards syn)) "— judging…")
                       score (judge-cards judge issue (:cards syn))]
                   (println "  overall" (get score "overall"))
                   {:model m :issue n :overall (get score "overall")
                    :criteria (get score "criteria") :notes (get score "notes")
                    :cards (mapv :path (:cards syn)) :exit (:exit syn)})))
        path  (str "bench/results/"
                   (str/replace (str (java.time.Instant/now)) #"[:.]" "-") ".md")]
    (io/make-parents path)
    (spit path (report-md judge issues rows))
    (println "\nwrote" path)
    {:path path :rows rows}))
