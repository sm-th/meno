(ns researcher.budget-test
  (:require [clojure.test :refer [deftest is]]
            [researcher.budget :as budget]))

(deftest meter-accumulates
  (budget/reset-run!)
  (budget/add! :embed {:tokens 100 :usd 0.01})
  (budget/add! :embed {:tokens 50 :usd 0.005})
  (budget/add! :llm {:tokens 20 :usd 0.5})
  (let [s (budget/snapshot)]
    (is (= 150 (get-in s [:embed :tokens])))
    (is (= 2 (get-in s [:embed :calls])))
    (is (= 0.5 (get-in s [:llm :usd])))))

(deftest caps-throw-when-exceeded
  (budget/reset-run!)
  (budget/add! :embed {:tokens 100 :usd 0.01})
  (budget/add! :llm {:tokens 20 :usd 0.5})
  (is (nil? (budget/check! {:embed-usd 1.0})) "under cap passes")
  (is (thrown? clojure.lang.ExceptionInfo (budget/check! {:embed-usd 0.001})))
  (is (thrown? clojure.lang.ExceptionInfo (budget/check! {:total-usd 0.1}))
      "total = embed + llm exceeds"))
