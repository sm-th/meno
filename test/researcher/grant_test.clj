(ns researcher.grant-test
  (:require [clojure.test :refer [deftest is]]
            [researcher.grant]
            [clojure.string :as str]))

;; task-issue-body is private: exercise its observable contract via the var.
(def body #'researcher.grant/task-issue-body)

(def cfg {:github {:repo "agent-smith-wiki/smith-wiki"}
          :wiki   {:base "main" :conventions "conventions"}})

(deftest task-body-links-conventions-not-inlined
  ;; The global card contract lives in the wiki Conventions card; the task links
  ;; it and never inlines it, even if the planner model supplies :acceptance.
  (let [s (body cfg {:rationale "R" :type :concept :seed_note "u"
                     :acceptance ["encyclopedic and objective" "atomic"]})]
    (is (not (str/includes? s "Acceptance")))
    (is (not (str/includes? s "encyclopedic")))
    (is (str/includes? s "Cards follow the wiki conventions:"))
    (is (str/includes? s "/blob/main/content/conventions.md"))
    (is (str/includes? s "R"))
    (is (str/includes? s "Seed: u"))
    (is (str/includes? s "type: concept"))))

(deftest task-body-minimal
  (let [s (body cfg {:rationale "Least privilege is invoked by the note" :seed_note "u"})]
    (is (str/includes? s "Least privilege is invoked by the note"))
    (is (str/includes? s "Seed: u"))))
