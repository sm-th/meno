(ns researcher.main
  (:require [researcher.config :as config]
            [researcher.planner :as planner]
            [researcher.worker :as worker]
            [researcher.sandbox :as sandbox]
            [researcher.index :as index]
            [researcher.refs :as refs]
            [researcher.budget :as budget]
            [researcher.diag :as diag]
            [researcher.loop :as lp])
  (:gen-class))

(defn -main [& args]
  (let [cmd (or (first args) "plan")
        cfg (config/load-config)]
    (case cmd
      "plan"   (lp/tick planner/steps {:cfg cfg})
      "work"   (worker/dry-run cfg (second args))
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
                            (= a "all")                   (println "urls:" (refs/ingest-all! cfg))
                            (or (nil? a) (= a "newest"))  (refs/ingest-newest! cfg)
                            :else                          (refs/ingest-slug! cfg a)))
                        (budget/report))
      "diag"   (diag/run)
      (println "usage: clojure -M -m researcher.main [plan|work|launch <p>|index|recall <k>|ingest-refs [all|newest|<slug>]]"))
    (flush)
    nil))
