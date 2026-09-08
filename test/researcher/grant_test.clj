(ns researcher.grant-test
  (:require [clojure.test :refer [deftest is]]
            [researcher.grant]
            [clojure.string :as str]))

;; The per-type task-body builders are private: exercise their observable
;; contracts via the vars. HOW to write the card lives in the stage prompt in
;; code — the body only carries Context/Source and names the stage.
(def concept-body #'researcher.grant/concept-body)
(def research-body #'researcher.grant/research-body)
(def ref-body #'researcher.grant/ref-body)

(deftest concept-body-context-quotes-angle-stage
  (let [s (concept-body {:rationale "least privilege is the security boundary here"
                         :quotes ["run in a sandbox" "least privilege first"]
                         :angle "the security-boundary sense"
                         :seed_note "https://x/y"})]
    (is (str/includes? s "## Context"))
    (is (str/includes? s "least privilege is the security boundary here"))
    (is (str/includes? s "## From the source"))
    (is (str/includes? s "> run in a sandbox"))
    (is (str/includes? s "## Angle"))
    (is (str/includes? s "the security-boundary sense"))
    (is (str/includes? s "- Source: https://x/y"))
    (is (str/includes? s "- Stage: INVESTIGATE"))
    ;; the card contract / Definition of Done never leaks into the task body
    (is (not (str/includes? s "## Definition of Done")))
    (is (not (str/includes? s "Acceptance")))))

(deftest concept-body-minimal-omits-empty-sections
  (let [s (concept-body {:rationale "Least privilege is invoked by the note" :seed_note "u"})]
    (is (str/includes? s "Least privilege is invoked by the note"))
    (is (str/includes? s "- Source: u"))
    (is (not (str/includes? s "## From the source")))
    (is (not (str/includes? s "## Angle")))))

(deftest research-body-renders-goals-and-stage
  ;; A research task carries the question's Context + Angle + Goals; INVESTIGATE writes the answer card.
  (let [s (research-body {:rationale "how to enforce least privilege when permissions are unknown"
                          :angle "the autonomy tension"
                          :goals ["survey approaches" "name the tradeoff"]
                          :seed_note "https://x/y"})]
    (is (str/includes? s "## Context"))
    (is (str/includes? s "how to enforce least privilege when permissions are unknown"))
    (is (str/includes? s "## Angle"))
    (is (str/includes? s "## Goals"))
    (is (str/includes? s "- survey approaches"))
    (is (str/includes? s "- Source: https://x/y"))
    (is (str/includes? s "- Stage: INVESTIGATE"))))

(deftest ref-body-source-context-read-stage
  ;; A reference task is a source to READ+ingest: Source + Context, naming the READ stage.
  (let [s (ref-body {:url "https://x/z" :context "worth reading for the sandbox model"})]
    (is (str/includes? s "## Source"))
    (is (str/includes? s "https://x/z"))
    (is (str/includes? s "## Context"))
    (is (str/includes? s "worth reading for the sandbox model"))
    (is (str/includes? s "- Stage: READ")))
  (let [s (ref-body {:url "https://x/z"})]
    (is (str/includes? s "_(none given"))))
