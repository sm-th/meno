(ns researcher.test-runner
  "Deterministic regression gate: pure logic only (parsing, chunking, graph
   metrics, budget caps, http dispatch, ref extraction). No network, omp, or LLM —
   live integrations are exercised by smoke runs, not mocked here.
   Run: clojure -M:test"
  (:require [clojure.test :as t]
            researcher.note-test
            researcher.refs-test
            researcher.budget-test
            researcher.http-test
            researcher.chunk-test
            researcher.graph-test))

(defn -main [& _]
  (let [{:keys [fail error]}
        (t/run-tests 'researcher.note-test 'researcher.refs-test
                     'researcher.budget-test 'researcher.http-test
                     'researcher.chunk-test 'researcher.graph-test)]
    (shutdown-agents)
    (System/exit (if (pos? (+ (or fail 0) (or error 0))) 1 0))))
