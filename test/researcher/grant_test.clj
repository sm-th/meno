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
    (is (str/includes? s "## Why this matters"))
    (is (str/includes? s "**Conventions:**"))
    (is (str/includes? s "/blob/main/content/conventions.md"))
    (is (str/includes? s "R"))
    (is (str/includes? s "**Seed:** u"))
    (is (str/includes? s "type: concept"))))

(deftest task-body-minimal
  (let [s (body cfg {:rationale "Least privilege is invoked by the note" :seed_note "u"})]
    (is (str/includes? s "Least privilege is invoked by the note"))
    (is (str/includes? s "**Seed:** u"))))

(deftest task-body-accepts-why-alias-and-renders-quote
  ;; Follow-up proposals arrive with :why (not :rationale) and a verbatim :quote
  ;; from the seed note; both must survive into the triage-facing issue body.
  (let [s (body cfg {:why "Sandboxing isolates each agent per task"
                     :quote "you can build an image ... run the agent in it"
                     :seed_note "u" :type :concept})]
    (is (str/includes? s "Sandboxing isolates each agent per task"))
    (is (str/includes? s "## From the note"))
    (is (str/includes? s "> you can build an image ... run the agent in it"))
    (is (str/includes? s "**Seed:** u"))))
