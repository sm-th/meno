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
                                      (filter #(= t (:type %)) judged))))
        best-of   (fn [f] (first (sort-by (comp - f) models)))
        overall   (best-of model-avg)
        per-type  (into {} (for [t types] [t (best-of #(mt-avg % t))]))
        split?    (> (count (distinct (vals per-type))) 1)]
    (str "# Model bench (request-level, comparative)\n\n"
         "_Updated " (java.time.Instant/now) "._  Judge: `" judge "` · probes: " (count probes)
         " · models: " (count models) ". Bare `omp -p --no-tools` completions; per-probe comparative "
         "scoring; Δ limit % = worst usage-window delta for the model's provider during generation.\n\n"
         "## Conclusion\n\n"
         "- **Overall best:** `" overall "` (" (format "%.2f" (double (model-avg overall))) "/10).\n"
         "- **Best per request type:** "
         (str/join ", " (for [t types] (str (name t) " → `" (get per-type t) "`"))) ".\n"
         "- **Recommendation:** "
         (if split?
           (str "split by class for cost — "
                (str/join "; " (for [t types] (str (name t) ": `" (get per-type t) "`")))
                ". Single-model fallback: `" overall "`.")
           (str "one model — `" overall "` — wins every class."))
         "\n\n## Ranking\n\n| model | avg /10 | Δ limit % | best-of-probe |\n|---|---|---|---|\n"
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

(defn- write-out [judge probes usage judged]
  (io/make-parents "bench/REPORT.md")
  (spit "bench/REPORT.md" (report-md judge probes usage judged))
  (spit "bench/judged.edn" (pr-str judged))
  (println "\nwrote bench/REPORT.md"))

(defn bench
  "Generate all answers (saved to bench/answers.edn), then comparative-judge each
   probe and write the single bench/REPORT.md. opts: {:models [str] :judge :probes}."
  [{:keys [models judge probes]}]
  (let [judge (or judge "anthropic/claude-sonnet-4-5")
        ps    (load-probes (or probes "bench/probes.edn"))
        {:keys [answers usage]} (generate models ps)
        _      (spit "bench/answers.edn" (pr-str {:answers answers :usage usage}))
        judged (judge-all judge ps answers)]
    (write-out judge ps usage judged)
    {:judged judged :usage usage}))

(defn rejudge
  "Re-judge saved answers (bench/answers.edn) — no regeneration / no candidate
   limit spend — and rewrite bench/REPORT.md."
  [& {:keys [judge]}]
  (let [judge (or judge "anthropic/claude-sonnet-4-5")
        {:keys [answers usage]} (edn/read-string (slurp "bench/answers.edn"))
        ps     (load-probes)
        judged (judge-all judge ps answers)]
    (write-out judge ps usage judged)
    {:judged judged :usage usage}))

(defn render
  "Rebuild bench/REPORT.md from saved bench/answers.edn + bench/judged.edn with no
   model calls (use after tweaking the report format)."
  [& _]
  (let [{:keys [usage]} (edn/read-string (slurp "bench/answers.edn"))
        judged (edn/read-string (slurp "bench/judged.edn"))
        ps     (load-probes)]
    (write-out "anthropic/claude-sonnet-4-5" ps usage judged)))
