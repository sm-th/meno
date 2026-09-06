(ns researcher.main
  (:require [researcher.config :as config]
            [researcher.planner :as planner]
            [researcher.worker :as worker]
            [researcher.sandbox :as sandbox]
            [researcher.index :as index]
            [researcher.loop :as lp])
  (:gen-class))

(defn -main [& args]
  (let [cmd (or (first args) "plan")
        cfg (config/load-config)]
    (case cmd
      "plan"   (lp/tick planner/steps {:cfg cfg})
      "work"   (worker/dry-run cfg (second args))
      "launch" (sandbox/print-launch cfg (keyword (or (second args) "worker")) ["work" "N"])
      "index"  (println "indexed" (index/build! cfg) "docs into Qdrant")
      "recall" (index/recall-newest cfg (Integer/parseInt (or (second args) "8")))
      (println "usage: clojure -M -m researcher.main [plan|work|launch <profile>|index|recall <k>]"))
    (flush)
    nil))
