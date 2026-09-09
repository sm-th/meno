(ns researcher.grant-test
  (:require [clojure.test :refer [deftest is]]
            [researcher.grant]
            [clojure.string :as str]))

;; The per-type task-body builders are private: exercise their observable
;; contracts via the vars. HOW to write the card lives in the stage prompt in
;; code — the body only carries Context/Source and names the stage.
(def ref-body #'researcher.grant/ref-body)

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
