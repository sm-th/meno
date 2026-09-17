(ns researcher.threads-test
  (:require [clojure.test :refer [deftest is]]
            [social.threads :as threads]))

(deftest question-ends-with-agent-mention
  ;; The Agent can reply to Andy's post only when its direct parent is the
  ;; explicit mention segment. A dependent post resolves to this final segment.
  (is (= (str "---\n"
              "actor: andy\n"
              "---\n"
              "Can evidence change this conclusion?\n"
              "---\n"
              "@agent.smith.wiki\n")
         (threads/post-file :andy nil nil nil "@agent.smith.wiki"
                            "Can evidence change this conclusion?"))))

(deftest segment-limit-counts-unicode-characters-not-utf8-bytes
  (let [full  (apply str (repeat 500 "😀"))
        extra (str full "😀")]
    (is (= [full] (threads/segments full)))
    (is (= [full "😀"] (threads/segments extra)))))
