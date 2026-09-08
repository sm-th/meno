(ns researcher.grant-test
  (:require [clojure.test :refer [deftest is]]
            [researcher.grant]
            [clojure.string :as str]))

;; task-issue-body is private: exercise its observable contract via the var.
(def body #'researcher.grant/task-issue-body)

(def cfg {:github {:repo "agent-smith-wiki/smith-wiki"}
          :wiki   {:base "main" :conventions "conventions"}})

(deftest task-body-context-and-stage-not-contract
  ;; The card contract/structure lives in the PROCESS (stage prompt in code), NOT the
  ;; issue body: the body gives Context + names the stage/practice + Source, and never
  ;; inlines the contract or a Definition of Done.
  (let [s (body cfg {:rationale "R" :type :concept :seed_note "u"
                     :acceptance ["encyclopedic and objective" "atomic"]})]
    (is (not (str/includes? s "Acceptance")))
    (is (not (str/includes? s "encyclopedic")))
    (is (not (str/includes? s "## Definition of Done")))
    (is (str/includes? s "## Context"))
    (is (str/includes? s "- Stage: INVESTIGATE"))
    (is (str/includes? s "R"))
    (is (str/includes? s "- Source: u"))
    (is (str/includes? s "type: concept"))))

(deftest task-body-minimal
  (let [s (body cfg {:rationale "Least privilege is invoked by the note" :seed_note "u"})]
    (is (str/includes? s "Least privilege is invoked by the note"))
    (is (str/includes? s "- Source: u"))))

(deftest task-body-renders-quotes-and-angle
  ;; A concept research task carries verbatim quotes (blockquoted) and an angle;
  ;; the card's structure/DoD lives in the stage prompt, not the task. :why aliases :rationale.
  (let [s (body cfg {:why "how the source frames it"
                     :quotes ["run in a sandbox" "least privilege first"]
                     :angle "the security-boundary sense"
                     :seed_note "u" :type :concept})]
    (is (str/includes? s "## Context"))
    (is (str/includes? s "## From the source"))
    (is (str/includes? s "> run in a sandbox"))
    (is (str/includes? s "## Angle"))
    (is (str/includes? s "the security-boundary sense"))
    (is (not (str/includes? s "## Definition of Done")))
    (is (str/includes? s "- Source: u"))))
