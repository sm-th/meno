(ns researcher.bench
  "Request-level model bench with COMPARATIVE judging. Tests candidate models on
   the researcher's request TYPES (bench/probes.edn) as single bare omp
   completions (no tools/gateway) — cheap and model-isolated. All answers are
   generated first and SAVED; then each probe is judged by comparing every
   model's answer side-by-side (anonymized+shuffled) in one judge call, which
   discriminates far better than isolated 1-10 scores. Per-model limit
   consumption is read from `omp usage`. Re-judge saved answers cheaply with
   `rejudge` (no regeneration).

     (require 'researcher.bench)
     (researcher.bench/bench
       {:models [\"opencode-go/deepseek-v4-flash\" \"opencode-go/deepseek-v4-pro\"
                 \"opencode-go/glm-5.2\" \"opencode-go/kimi-k2.6\"
                 \"anthropic/claude-sonnet-4-5\"]})"
  (:require [clojure.java.shell :refer [sh]]
            [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.java.io :as io]))

(defn- omp-complete [model prompt]
  (str (:out (sh "omp" "-p" "--no-tools" "--no-session" "--no-title"
                 "--model" model "--" prompt))))

(defn load-probes
  ([] (load-probes "bench/probes.edn"))
  ([p] (edn/read-string (slurp p))))

(defn- extract-json [s]
  (let [s (str s) i (str/index-of s "{") j (str/last-index-of s "}")]
    (when (and i j (< i j)) (subs s i (inc j)))))

(defn- provider-of [model] (first (str/split model #"/")))

(defn- usage-max
  "Worst (largest) used-% across the provider's usage windows, or 0."
  [provider]
  (let [{:keys [out]} (sh "omp" "usage" "--provider" provider "--json")]
    (or (try (->> (get (json/read-str (str out)) "reports")
                  first (#(get % "limits"))
                  (map #(get-in % ["amount" "used"])) (remove nil?) (apply max))
             (catch Exception _ nil))
        0)))

(defn- generate
  "Generate every model's answer to every probe; measure each model's provider
   limit delta around its own generation. Returns {:answers {probe-id {model text}}
   :usage {model {:provider :delta}}}."
  [models probes]
  (let [answers (atom {}) usage (atom {})]
    (doseq [m models]
      (let [prov (provider-of m) u0 (usage-max prov)]
        (doseq [p probes]
          (println "▶ gen" m "/" (:id p))
          (swap! answers assoc-in [(:id p) m] (omp-complete m (:prompt p))))
        (swap! usage assoc m {:provider prov :delta (- (usage-max prov) u0)})))
    {:answers @answers :usage @usage}))

(defn- judge-probe
  "Comparative judging of all models' answers to one probe. Anonymized + shuffled
   to remove name/position bias. One judge call. Returns {:scores {model n}
   :best model :notes}."
  [judge-model probe m->a]
  (let [models     (shuffle (keys m->a))
        labels     (mapv #(str (char (+ 65 %))) (range (count models)))
        lab->model (zipmap labels models)
        block      (str/join "\n\n"
                             (map (fn [l] (str "--- ANSWER " l " ---\n" (get m->a (lab->model l)))) labels))
        prompt (str "Compare the candidate answers to the TASK. Score EACH answer 1-10 strictly by the "
                    "RUBRIC. Be discriminating: spread the scores, do NOT give everyone the same. Name the "
                    "best label.\n\nTASK:\n" (:prompt probe) "\n\nRUBRIC (all must hold for a high score):\n"
                    (str/join "\n" (map #(str "- " %) (:rubric probe))) "\n\n" block
                    "\n\nReturn ONLY JSON {" (str/join "," (map #(str "\"" % "\":<1-10 int>") labels))
                    ",\"best\":\"<label>\",\"notes\":\"<=45 words: why the best wins and the worst fails\"}.")
        parsed (try (json/read-str (extract-json (omp-complete judge-model prompt))) (catch Exception _ {}))]
    {:scores (into {} (map (fn [l] [(lab->model l) (get parsed l)]) labels))
     :best   (get lab->model (get parsed "best"))
     :notes  (get parsed "notes")}))

(defn- avg [xs]
  (let [xs (remove nil? xs)] (if (seq xs) (/ (reduce + 0.0 xs) (count xs)) 0.0)))

(defn- report-md [judge probes usage judged]
  (let [models    (sort (keys usage))
        types     (distinct (map :type probes))
        model-avg (fn [m] (avg (map #(get-in % [:scores m]) judged)))
        mt-avg    (fn [m t] (avg (map #(get-in % [:scores m])
                                      (filter #(= t (:type %)) judged))))]
    (str "# Model bench (request-level, comparative) — " (java.time.Instant/now) "\n\n"
         "Judge: `" judge "`  ·  probes: " (count probes) "  ·  models: " (count models) "\n"
         "Bare `omp -p --no-tools` completions; per-probe comparative scoring; "
         "Δ limit % = worst usage-window delta for the model's provider during its generation.\n\n"
         "## Ranking\n\n| model | avg /10 | Δ limit % | best-of-probe |\n|---|---|---|---|\n"
         (str/join "\n"
                   (for [m (sort-by (comp - model-avg) models)]
                     (str "| `" m "` | " (format "%.2f" (double (model-avg m)))
                          " | " (get-in usage [m :delta]) " (" (get-in usage [m :provider]) ")"
                          " | " (count (filter #(= m (:best %)) judged)) "/" (count judged) " |")))
         "\n\n## By request type (avg /10 — choose a model per class)\n\n"
         "| model | " (str/join " | " (map name types)) " |\n|---|"
         (str/join "" (repeat (count types) "---|")) "\n"
         (str/join "\n"
                   (for [m (sort-by (comp - model-avg) models)]
                     (str "| `" m "` | "
                          (str/join " | " (for [t types] (format "%.1f" (double (mt-avg m t))))) " |")))
         "\n\n## Per probe (score /10, ★ = judged best)\n\n"
         "| probe | type | " (str/join " | " models) " |\n|---|---|"
         (str/join "" (repeat (count models) "---|")) "\n"
         (str/join "\n"
                   (for [j judged]
                     (str "| " (:probe j) " | " (name (:type j)) " | "
                          (str/join " | " (for [m models]
                                            (str (or (get-in j [:scores m]) "—")
                                                 (when (= m (:best j)) " ★")))) " |")))
         "\n\n## Judge notes\n\n"
         (str/join "\n" (for [j judged]
                          (str "- **" (:probe j) "** best=`" (:best j) "` — "
                               (str/replace (str (:notes j)) "\n" " ")))))))

(defn- judge-all [judge probes answers]
  (doall (for [p probes]
           (do (println "⚖" (:id p))
               (assoc (judge-probe judge p (get answers (:id p)))
                      :probe (:id p) :type (:type p))))))

(defn- write-out [stamp judge probes usage judged]
  (io/make-parents (str "bench/results/" stamp ".md"))
  (spit (str "bench/results/" stamp ".md") (report-md judge probes usage judged))
  (spit (str "bench/results/" stamp ".edn") (pr-str judged))
  (println "\nwrote bench/results/" stamp ".md"))

(defn bench
  "Generate all answers (saved), then comparative-judge each probe. opts:
   {:models [str] :judge str :probes <path>}."
  [{:keys [models judge probes]}]
  (let [judge (or judge "anthropic/claude-sonnet-4-5")
        ps    (load-probes (or probes "bench/probes.edn"))
        stamp (str/replace (str (java.time.Instant/now)) #"[:.]" "-")
        {:keys [answers usage]} (generate models ps)
        _      (spit (str "bench/results/" stamp "-answers.edn")
                     (pr-str {:answers answers :usage usage}))
        judged (judge-all judge ps answers)]
    (write-out stamp judge ps usage judged)
    {:stamp stamp :judged judged :usage usage}))

(defn rejudge
  "Re-run comparative judging on saved answers (no regeneration / no candidate
   limit spend). `answers-path` = a bench/results/<ts>-answers.edn."
  [answers-path & {:keys [judge]}]
  (let [judge (or judge "anthropic/claude-sonnet-4-5")
        {:keys [answers usage]} (edn/read-string (slurp answers-path))
        ps     (load-probes)
        stamp  (str (str/replace (str (java.time.Instant/now)) #"[:.]" "-") "-rejudge")
        judged (judge-all judge ps answers)]
    (write-out stamp judge ps usage judged)
    {:stamp stamp :judged judged :usage usage}))
