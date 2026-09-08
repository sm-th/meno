(ns researcher.grant-test
  (:require [clojure.test :refer [deftest is]]
            [researcher.grant]
            [clojure.string :as str]))

;; task-issue-body is private: exercise its observable contract via the var.
(def body #'researcher.grant/task-issue-body)

(def cfg {:github {:repo "agent-smith-wiki/smith-wiki"}
          :wiki   {:base "main" :conventions "conventions"}})

(deftest task-body-references-stage-not-inlines-contract
  ;; The card contract lives in the PROCESS (the INVESTIGATE prompt in code), NOT in
  ;; the issue body: the body names the stage/practice that will handle the seed and
  ;; never inlines the contract, even if the model supplies :acceptance.
  (let [s (body cfg {:rationale "R" :type :concept :seed_note "u"
                     :acceptance ["encyclopedic and objective" "atomic"]})]
    (is (not (str/includes? s "Acceptance")))
    (is (not (str/includes? s "encyclopedic")))
    (is (str/includes? s "## Why this matters"))
    (is (str/includes? s "- Stage: INVESTIGATE"))
    (is (str/includes? s "R"))
    (is (str/includes? s "- Seed: u"))
    (is (str/includes? s "type: concept"))))

(deftest task-body-minimal
  (let [s (body cfg {:rationale "Least privilege is invoked by the note" :seed_note "u"})]
    (is (str/includes? s "Least privilege is invoked by the note"))
    (is (str/includes? s "- Seed: u"))))
(deftest task-body-renders-goals-and-inline-quote
  ;; Quotes are woven inline in the rationale (no separate block); research goals
  ;; render as a checklist. :why is accepted as an alias for :rationale.
  (let [s (body cfg {:why "Confining untrusted code; the note says \"run in a sandbox\""
                     :goals ["the canonical, vendor-neutral definition" "how it applies to agents"]
                     :seed_note "u" :type :concept})]
    (is (str/includes? s "run in a sandbox"))
    (is (str/includes? s "## Definition of Done"))
    (is (str/includes? s "- [ ] the canonical, vendor-neutral definition"))
    (is (not (str/includes? s "## From the note")))
    (is (str/includes? s "- Seed: u"))))
