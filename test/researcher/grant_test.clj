(ns researcher.grant-test
  (:require [clojure.test :refer [deftest is]]
            [researcher.grant]
            [clojure.string :as str]))

;; The per-type task-body builders are private: exercise their observable
;; contracts via the vars. HOW to write the card lives in the stage prompt in
;; code — the body only carries Context/Source and names the stage.
(def research-body #'researcher.grant/research-body)
(def ref-body #'researcher.grant/ref-body)

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
