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

(deftest translate-folds-the-topic-into-the-microvm-input
  (let [captured (atom nil)
        reply-out (fn [text]
                    (json/write-str
                     {:type "message_end"
                      :message {:role "assistant"
                                :content [{:type "text" :text text}]}}))]
    (with-redefs [image/ensure! (fn [])
                  sandbox/run (fn [opts]
                                (reset! captured (get-in opts [:env "OMP_TASK"]))
                                {:exit 0 :out (reply-out "English question")})]
      (is (= "English question"
             (public-research/translate! {} "Memory topic" "cuerpo del mensaje")))
      ;; both the Zulip topic and the message body reach the translator.
      (is (str/includes? @captured "Memory topic"))
      (is (str/includes? @captured "cuerpo del mensaje"))
      ;; a blank body falls back to the topic alone.
      (public-research/translate! {} "Memory topic" "")
      (is (str/includes? @captured "Memory topic"))
      (is (not (str/includes? @captured "cuerpo del mensaje"))))))

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
                                      {:answer "Yes. The evidence supports it."
                                       :page "site/conversation-memory.md"}
                                      ["site/conversation-memory.md"])})]
                 (public-research/run-stage!
                  {:wiki-base "main"}
                  {:stream "research" :topic "memory" :id 42
                   :question "Question"}))]
    (is (= "abc123" (:commit result)))
    (is (= "site/conversation-memory.md" (:page result)))
    (is (= "Yes. The evidence supports it." (:answer result)))))

(deftest stage-result-rejects-non-strings-and-pages-outside-the-pushed-diff
  (with-redefs [image/ensure! (fn [])
                sandbox/ensure-volume! (fn [_])]
    (with-redefs [sandbox/run
                  (fn [_]
                    {:exit 0 :out (stage-output {:answer "Yes." :page 42}
                                               ["site/conversation-memory.md"])})]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"result values must be strings"
           (public-research/run-stage!
            {:wiki-base "main"}
            {:stream "research" :topic "memory" :id 42
             :question "Question"}))))
    (with-redefs [sandbox/run
                  (fn [_]
                    {:exit 0
                     :out (stage-output {:answer "Yes." :page "site/other.md"}
                                        ["site/conversation-memory.md"])})]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"does not describe the pushed branch"
           (public-research/run-stage!
            {:wiki-base "main"}
            {:stream "research" :topic "memory" :id 42
             :question "Question"}))))))

(deftest stage-result-requires-both-answer-and-page
  (with-redefs [image/ensure! (fn [])
                sandbox/ensure-volume! (fn [_])
                sandbox/run
                (fn [_]
                  {:exit 0 :out (stage-output {:page "site/conversation-memory.md"}
                                             ["site/conversation-memory.md"])})]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"invalid result.json"
         (public-research/run-stage!
          {:wiki-base "main"}
          {:stream "research" :topic "memory" :id 42
           :question "Question"})))))

(deftest stage-task-drives-a-single-publishing-pass
  (let [task (#'public-research/stage-task "Question" "public-research/topic-42")]
    (is (re-find #"USER QUESTION:\nQuestion" task))
    (is (re-find #"push origin HEAD:refs/heads/public-research/topic-42" task))
    (is (re-find #"exactly two keys: answer and page" task))))

(deftest retry-task-recovers-the-immutable-remote-result
  (let [task (#'public-research/retry-task "Question" "public-research/topic-42")]
    (is (re-find #"checkout is pinned to that exact remote commit" task))
    (is (re-find #"Do not edit files, reset, commit, or push" task))
    (is (re-find #"result\.json describing what is already in that commit" task))
    (is (re-find #"exactly two string keys, answer and page" task))))

(defn- accepted-page []
  {:number 1 :url "https://github.test/commit"
   :pages ["site/conversation-memory.md"]})

(deftest public-replies-share-the-single-pass-answer-text
  (let [state      (atom {})
        replies    (atom [])
        intents    (atom [])
        runs       (atom [])
        accepts    (atom 0)
        changelogs (atom 0)
        xlate      (atom nil)
        file       (java.io.File/createTempFile "public-research-" ".edn")
        source     {:react!    (fn [& _])
                    :reply!    (fn [_ text] (swap! replies conj text))
                    :mark-done! (fn [& _])}
        message    {:id 42 :stream "research" :topic "memory" :content "pregunta"}]
    (.delete file)
    (try
      (with-redefs [public-research/translate!
                    (fn [_ topic content]
                      (reset! xlate [topic content])
                      "Translated question")
                    public-research/run-stage!
                    (fn [_ request]
                      (swap! runs conj request)
                      {:branch "research" :page "site/conversation-memory.md"
                       :answer "Yes. The evidence supports it."})
                    research/accept! (fn [& _]
                                       (swap! accepts inc)
                                       {:number 1 :url "https://github.test/commit"
                                        :pages ["site/conversation-memory.md"]})
                    research/changelog! (fn [& _] (swap! changelogs inc))
                    threads/enqueue! (fn [_ intent]
                                       (swap! intents conj intent)
                                       {:url "https://github.test/commit"})]
        (public-research/process-message!
         {:state-file (.getPath file) :threads {}}
         source state message)
        (let [final-text (str "Yes. The evidence supports it."
                              "\n\nhttps://smith.wiki/conversation-memory/")]
          ;; the Zulip topic is folded into the translator input.
          (is (= ["memory" "pregunta"] @xlate))
          ;; exactly one research pass; the translation is its sole question.
          (is (= 1 (count @runs)))
          (is (= "Translated question" (:question (first @runs))))
          ;; one publish, one changelog entry.
          (is (= 1 @accepts))
          (is (= 1 @changelogs))
          ;; two Zulip replies: the translation echo and the shared answer.
          (is (= 2 (count @replies)))
          (is (str/starts-with? (first @replies) "**English translation"))
          (is (= final-text (second @replies)))
          ;; two Threads intents: the question post and the identical answer post.
          (is (= 2 (count @intents)))
          (is (= final-text (:text (second @intents))))))
      (finally
        (.delete file)))))

(defn- checkpoint-state [target]
  (let [path [:messages 42]
        base {:translation "Translated question"
              :question-post {:url "https://github.test/question"}}
        research (assoc base
                        :translation-echoed? true
                        :research-run {:branch "research"
                                       :page "site/conversation-memory.md"
                                       :answer "Yes. The evidence supports it."}
                        :accepted (accepted-page)
                        :answer-post {:url "https://github.test/answer"})]
    (assoc-in {} path
              (case target
                :translation base
                :answer research))))

(defn- reply-kind [text]
  (if (str/starts-with? text "**English translation")
    :translation
    :answer))

(deftest zulip-replies-are-at-most-once-across-ambiguous-failures
  (doseq [target [:translation :answer]]
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
                      (fn [_ _]
                        {:branch "research" :page "site/conversation-memory.md"
                         :answer "Yes. The evidence supports it."})
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

(deftest research-run-and-topic-session-activation-share-a-checkpoint
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
                    (fn [_ _] {:branch "research"
                               :page "site/conversation-memory.md"
                               :answer "Yes. The evidence supports it."})
                    research/accept! (fn [& _]
                                       (throw (ex-info "stop after research checkpoint" {})))
                    threads/enqueue! (fn [_ _] {:url "https://github.test/commit"})]
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo #"stop after research checkpoint"
             (public-research/process-message!
              {:state-file (.getPath file) :threads {}}
              source state message)))
        ;; the research run and the topic session flag land in one checkpoint.
        (is (= "site/conversation-memory.md"
               (get-in @state [:messages 42 :research-run :page])))
        (is (true? (get-in @state [:topics ["research" "memory"]
                                    :session-started?]))))
      (finally
        (.delete file)))))

(deftest topic-session-continues-across-messages
  (let [state   (atom {})
        conts   (atom [])
        file    (java.io.File/createTempFile "public-research-" ".edn")
        source  {:react! (fn [& _])
                 :reply! (fn [& _])
                 :mark-done! (fn [& _])}]
    (.delete file)
    (try
      (with-redefs [public-research/translate! (fn [& _] "Translated question")
                    public-research/run-stage!
                    (fn [_ request]
                      (swap! conts conj (:continue? request))
                      {:branch "research" :page "site/conversation-memory.md"
                       :answer "Yes. The evidence supports it."})
                    research/accept! (fn [& _] (accepted-page))
                    research/changelog! (fn [& _])
                    threads/enqueue! (fn [_ _] {:url "https://github.test/commit"})]
        (public-research/process-message!
         {:state-file (.getPath file) :threads {}}
         source state
         {:id 42 :stream "research" :topic "memory" :content "first"})
        (public-research/process-message!
         {:state-file (.getPath file) :threads {}}
         source state
         {:id 43 :stream "research" :topic "memory" :content "second"})
        ;; first message opens the session; later messages continue it.
        (is (= [false true] @conts)))
      (finally
        (.delete file)))))
