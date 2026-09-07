(ns researcher.main
  (:require [researcher.config :as config]
            [researcher.runner :as runner]
            [researcher.sandbox :as sandbox]
            [researcher.index :as index]
            [researcher.refs :as refs]
            [researcher.budget :as budget]
            [researcher.diag :as diag])
  (:gen-class))

(defn -main [& args]
  (let [cmd (or (first args) "plan")
        cfg (config/load-config)]
    (case cmd
      "plan"   (runner/run-issue cfg (runner/seed-issue cfg :plan))
      "work"   (let [i (runner/pick cfg (second args))]
                 (if i (runner/run-issue cfg i) (println "no approved (Todo) issue")))
      "launch" (sandbox/print-launch cfg (keyword (or (second args) "worker")) ["work" "N"])
      "index"  (do (budget/reset-run!)
                   (println "indexed" (index/build! cfg) "docs into Qdrant")
                   (budget/report))
      "recall" (do (budget/reset-run!)
                   (index/recall-newest cfg (Integer/parseInt (or (second args) "8")))
                   (budget/report))
      "ingest-refs" (do (budget/reset-run!)
                        (let [a (second args)]
                          (cond
                            (= a "all")                  (println "urls:" (refs/ingest-all! cfg))
                            (or (nil? a) (= a "newest")) (refs/ingest-newest! cfg)
                            :else                         (refs/ingest-slug! cfg a)))
                        (budget/report))
      "diag"   (diag/run)
      (println "usage: clojure -M -m researcher.main [plan|work|launch <p>|index|recall <k>|ingest-refs [all|newest|<slug>]|diag]"))
    (flush)
    nil))
