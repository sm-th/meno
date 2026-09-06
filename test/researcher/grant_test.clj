(ns researcher.grant-test
  (:require [clojure.test :refer [deftest is]]
            [researcher.grant]
            [clojure.string :as str]))

;; task-issue-body is private: exercise its observable contract via the var.
(def body #'researcher.grant/task-issue-body)

(deftest task-body-with-concept-specific-acceptance
  (let [s (body {:rationale "R" :acceptance ["avoid confusing X with Y"]
                 :seed_note "u" :type :concept})]
    (is (str/includes? s "Acceptance (specific to this concept):"))
    (is (str/includes? s "- avoid confusing X with Y"))
    (is (str/includes? s "Seed: u"))
    (is (str/includes? s "type: concept"))))

(deftest task-body-omits-empty-acceptance
  (let [s (body {:rationale "R" :seed_note "u"})]
    (is (not (str/includes? s "Acceptance")) "no acceptance section when none given")
    (is (str/includes? s "R"))
    (is (str/includes? s "Seed: u"))))
