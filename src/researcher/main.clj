(ns researcher.main
  "meno CLI entry. For now the one command is `ingest <url>`: read one source and
   integrate it into the KB via a single spawn through the Zeno grant."
  (:require [researcher.ingest :as ingest]))

(defn -main [& args]
  (try
    (case (first args)
      "ingest" (let [r (ingest/run-post {:url (second args)})]
                 (println :exit (:exit r)))
      (println "usage: clojure -M:run ingest <url>"))
    (finally (shutdown-agents))))
