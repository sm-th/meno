(ns researcher.public-research-test
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [public-research :as public-research]
            [research :as research]
            [research.image :as image]
            [social.threads :as threads]
            [zeno.sandbox :as sandbox])
  (:import (java.nio.charset StandardCharsets)
           (java.util Base64)))

(deftest final-answer-is-one-segment-with-one-canonical-link
  (let [text (#'public-research/answer-text
              "Yes. Durable topic state preserves the conversation; source checks keep its conclusions auditable."
              {:pages ["site/conversation-memory.md"]}
              "site/conversation-memory.md")]
    (is (= (str "Yes. Durable topic state preserves the conversation; source checks keep its conclusions auditable."
                "\n\nhttps://smith.wiki/conversation-memory/")
           text))
    (is (= 1 (count (threads/segments text))))))

(deftest final-answer-rejects-every-extra-link-form
  (doseq [answer ["See https://example.com for details"
                  "See //example.com/path for details"
                  "See example.com/path for details"
                  "Email person@example.com for details"
                  "See [the evidence](/relative-target)"
                  "See [the evidence][source]"
                  "Use urn:isbn:9780140328721 for details"
                  "First paragraph.\n\nSecond paragraph."]]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"one URL-free paragraph"
         (#'public-research/answer-text
          answer {:pages ["site/conversation-memory.md"]}
          "site/conversation-memory.md"))
        answer)))

(deftest result-content-must-be-strings
  (doseq [answer [42 {:text "Looks plausible"}]]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"result values must be strings"
         (#'public-research/answer-text
          answer {:pages ["site/conversation-memory.md"]}
          "site/conversation-memory.md")))))

(deftest final-answer-limit-counts-unicode-characters
  (let [page     "site/a.md"
        accepted {:pages [page]}
        url      "https://smith.wiki/a/"
        room     (- 500 2 (.codePointCount url 0 (.length url)))
        answer   (apply str (repeat room "😀"))
        text     (#'public-research/answer-text answer accepted page)]
    (is (= 500 (.codePointCount text 0 (.length text))))
    (is (= [text] (threads/segments text)))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"exceed 500 Threads characters"
         (#'public-research/answer-text (str answer "😀") accepted page)))))

(defn- stage-output [result pages]
  (str "RESEARCH_RESULT "
       (.encodeToString (Base64/getEncoder)
                        (.getBytes (json/write-str result) StandardCharsets/UTF_8))
       "\nRESEARCH_COMMIT abc123\n"
       (apply str (map #(str "RESEARCH_PAGE " % "\n") pages))))

(deftest stage-result-is-bound-to-the-pushed-commit
  (let [result (with-redefs [image/ensure! (fn [])
                             sandbox/ensure-volume! (fn [_])
                             sandbox/run
                             (fn [_]
                               {:exit 0
                                :out (stage-output
                                      {:page "site/conversation-memory.md"}
                                      ["site/conversation-memory.md"])})]
                 (public-research/run-stage!
                  {:wiki-base "main"}
                  {:stream "research" :topic "memory" :id 42
                   :question "Question" :stage :progress}))]
    (is (= "abc123" (:commit result)))
    (is (= "site/conversation-memory.md" (:page result)))))

(deftest stage-result-rejects-non-strings-and-pages-outside-the-pushed-diff
  (with-redefs [image/ensure! (fn [])
                sandbox/ensure-volume! (fn [_])]
    (with-redefs [sandbox/run
                  (fn [_]
                    {:exit 0 :out (stage-output {:page 42}
                                               ["site/conversation-memory.md"])})]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"result values must be strings"
           (public-research/run-stage!
            {:wiki-base "main"}
            {:stream "research" :topic "memory" :id 42
             :question "Question" :stage :progress}))))
    (with-redefs [sandbox/run
                  (fn [_]
                    {:exit 0
                     :out (stage-output {:page "site/other.md"}
                                        ["site/conversation-memory.md"])})]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"does not describe the pushed branch"
           (public-research/run-stage!
            {:wiki-base "main"}
            {:stream "research" :topic "memory" :id 42
             :question "Question" :stage :progress}))))))

(deftest final-task-pins-the-intermediate-canonical-page
  (let [task (#'public-research/stage-task
              :final "Question" "public-research/topic-42-final"
              "site/conversation-memory.md")]
    (is (re-find #"must remain exactly site/conversation-memory\.md" task))
    (is (re-find #"page value must be exactly site/conversation-memory\.md" task))))

(deftest retry-task-recovers-the-immutable-remote-result
  (let [task (#'public-research/retry-task
              :progress "Question" "public-research/topic-42-progress" nil)]
    (is (re-find #"checkout is pinned to that exact remote commit" task))
    (is (re-find #"Do not edit files, reset, commit, or push" task))
    (is (re-find #"result\.json describing what is already in that commit" task))))

(deftest public-replies-use-only-the-canonical-page-and-shared-final-text
  (let [state   (atom {})
        replies (atom [])
        intents (atom [])
        runs    (atom [])
        file    (java.io.File/createTempFile "public-research-" ".edn")
        source  {:react!    (fn [& _])
                 :reply!    (fn [_ text] (swap! replies conj text))
                 :mark-done! (fn [& _])}
        message {:id 42 :stream "research" :topic "memory" :content "question"}]
    (.delete file)
    (try
      (with-redefs [public-research/translate! (fn [& _] "Translated question")
                    public-research/run-stage!
                    (fn [_ {:keys [stage] :as request}]
                      (swap! runs conj request)
                      (if (= stage :progress)
                        {:branch "progress" :page "site/conversation-memory.md"}
                        {:branch "final" :page "site/conversation-memory.md"
                         :answer "Yes. The evidence supports it."}))
                    research/accept! (fn [& _]
                                       {:number 1 :url "https://github.test/commit"
                                        :pages ["site/conversation-memory.md"]})
                    research/changelog! (fn [& _])
                    threads/enqueue! (fn [_ intent]
                                       (swap! intents conj intent)
                                       {:url "https://github.test/commit"})]
        (public-research/process-message!
         {:state-file (.getPath file) :threads {}}
         source state message)
        (let [final-text (str "Yes. The evidence supports it."
                              "\n\nhttps://smith.wiki/conversation-memory/")]
          (is (= "https://smith.wiki/conversation-memory/" (nth @replies 1)))
          (is (= final-text (nth @replies 2)))
          (is (= final-text (:text (second @intents))))
          (is (= "site/conversation-memory.md"
                 (:canonical-page (second @runs))))))
      (finally
        (.delete file)))))


(defn- accepted-page []
  {:number 1 :url "https://github.test/commit"
   :pages ["site/conversation-memory.md"]})

(defn- checkpoint-state [target]
  (let [path [:messages 42]
        base {:translation "Translated question"
              :question-post {:url "https://github.test/question"}}
        progress (assoc base
                        :translation-echoed? true
                        :progress-run {:branch "progress"
                                       :page "site/conversation-memory.md"}
                        :progress (accepted-page))
        final (assoc progress
                     :progress-echoed? true
                     :final-run {:branch "final"
                                 :page "site/conversation-memory.md"
                                 :answer "Yes. The evidence supports it."}
                     :final (accepted-page)
                     :answer-post {:url "https://github.test/answer"})]
    (assoc-in {}
              path
              (case target
                :translation base
                :progress progress
                :final final))))

(defn- reply-kind [text]
  (cond
    (str/starts-with? text "**English translation") :translation
    (= text "https://smith.wiki/conversation-memory/") :progress
    :else :final))

(deftest zulip-replies-are-at-most-once-across-ambiguous-failures
  (doseq [target [:translation :progress :final]]
    (let [state    (atom (checkpoint-state target))
          attempts (atom [])
          file     (java.io.File/createTempFile "public-research-" ".edn")
          source   {:react! (fn [& _])
                    :reply! (fn [_ text]
                              (let [kind (reply-kind text)]
                                (swap! attempts conj kind)
                                (when (= target kind)
                                  (throw (ex-info "ambiguous Zulip failure" {})))))
                    :mark-done! (fn [& _])}
          message  {:id 42 :stream "research" :topic "memory" :content "question"}]
      (.delete file)
      (try
        (with-redefs [public-research/translate! (fn [& _] "Translated question")
                      public-research/run-stage!
                      (fn [_ {:keys [stage]}]
                        (if (= stage :progress)
                          {:branch "progress" :page "site/conversation-memory.md"}
                          {:branch "final" :page "site/conversation-memory.md"
                           :answer "Yes. The evidence supports it."}))
                      research/accept! (fn [& _] (accepted-page))
                      research/changelog! (fn [& _])
                      threads/enqueue! (fn [_ _] {:url "https://github.test/commit"})]
          (is (thrown-with-msg?
               clojure.lang.ExceptionInfo #"ambiguous Zulip failure"
               (public-research/process-message!
                {:state-file (.getPath file) :threads {}}
                source state message)))
          (public-research/process-message!
           {:state-file (.getPath file) :threads {}}
           source state message)
          (is (= 1 (count (filter #{target} @attempts))) (name target)))
        (finally
          (.delete file))))))

(deftest progress-run-and-topic-session-activation-share-a-checkpoint
  (let [state   (atom {})
        file    (java.io.File/createTempFile "public-research-" ".edn")
        source  {:react! (fn [& _])
                 :reply! (fn [& _])
                 :mark-done! (fn [& _])}
        message {:id 42 :stream "research" :topic "memory" :content "question"}]
    (.delete file)
    (try
      (with-redefs [public-research/translate! (fn [& _] "Translated question")
                    public-research/run-stage!
                    (fn [_ _] {:branch "progress"
                               :page "site/conversation-memory.md"})
                    research/accept! (fn [& _]
                                       (throw (ex-info "stop after progress checkpoint" {})))
                    threads/enqueue! (fn [_ _] {:url "https://github.test/commit"})]
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo #"stop after progress checkpoint"
             (public-research/process-message!
              {:state-file (.getPath file) :threads {}}
              source state message)))
        (is (= "site/conversation-memory.md"
               (get-in @state [:messages 42 :progress-run :page])))
        (is (true? (get-in @state [:topics ["research" "memory"]
                                    :session-started?]))))
      (finally
        (.delete file)))))

(deftest existing-progress-run-heals-topic-session-activation
  (let [state   (atom (checkpoint-state :progress))
        file    (java.io.File/createTempFile "public-research-" ".edn")
        source  {:react! (fn [& _])
                 :reply! (fn [& _] (throw (ex-info "stop after healing" {})))
                 :mark-done! (fn [& _])}
        message {:id 42 :stream "research" :topic "memory" :content "question"}]
    (.delete file)
    (try
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"stop after healing"
           (public-research/process-message!
            {:state-file (.getPath file) :threads {}}
            source state message)))
      (is (true? (get-in @state [:topics ["research" "memory"]
                                  :session-started?])))
      (finally
        (.delete file)))))

(deftest final-page-must-match-progress-before-publication
  (let [state   (atom (assoc-in
                       (checkpoint-state :progress)
                       [:messages 42 :final-run]
                       {:branch "final" :page "site/different.md"
                        :answer "No. The evidence differs."}))
        file    (java.io.File/createTempFile "public-research-" ".edn")
        accepts (atom 0)
        source  {:react! (fn [& _])
                 :reply! (fn [& _])
                 :mark-done! (fn [& _])}
        message {:id 42 :stream "research" :topic "memory" :content "question"}]
    (.delete file)
    (try
      (with-redefs [research/accept! (fn [& _] (swap! accepts inc))]
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo #"differs from intermediate"
             (public-research/process-message!
              {:state-file (.getPath file) :threads {}}
              source state message)))
        (is (zero? @accepts)))
      (finally
        (.delete file)))))