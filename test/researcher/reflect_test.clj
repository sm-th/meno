(ns researcher.reflect-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [researcher.reflect :as reflect]))

(deftest normalize-url-strips-noise
  (is (= "https://x.com/a" (reflect/normalize-url "https://x.com/a/")))
  (is (= "https://x.com/a" (reflect/normalize-url "https://x.com/a#sec")))
  (is (= "https://x.com/a" (reflect/normalize-url "https://x.com/a.")))
  (is (= "https://x.com" (reflect/normalize-url "https://x.com"))))

(deftest extract-urls-finds-and-dedups
  (let [md "see https://a.com/x and [lbl](https://b.org/y), also https://a.com/x/ again"]
    (is (= #{"https://a.com/x" "https://b.org/y"} (set (reflect/extract-urls md))))))

(deftest relink-text-natural-wikilink
  (is (= "[[T]]" (reflect/relink-text "https://a.com/x" "https://a.com/x" "T")))
  (is (= "[[T]]" (reflect/relink-text "[label](https://a.com/x)" "https://a.com/x" "T")))
  (is (= "[[T]]" (reflect/relink-text "https://a.com/x/" "https://a.com/x" "T")))
  (is (= "see [[T]] now" (reflect/relink-text "see https://a.com/x now" "https://a.com/x" "T"))))

(deftest relink-text-never-clobbers-a-longer-url
  (is (= "https://a.com/xy" (reflect/relink-text "https://a.com/xy" "https://a.com/x" "T"))))

(deftest index-and-frequency-over-a-tree
  (let [dir (str (System/getProperty "java.io.tmpdir") "/reflect-test-" (System/currentTimeMillis))
        w   (fn [rel s] (let [f (io/file dir rel)] (io/make-parents f) (spit f s)))]
    (w "content/references/a.com/foo.md" "---\ntitle: Foo (a.com)\nurl: https://a.com/foo\n---\nbody")
    (w "content/concepts/bar.md" "---\ntitle: Bar\n---\ncites https://a.com/foo and https://b.org/x")
    (w "content/concepts/baz.md" "body https://a.com/foo again, and https://b.org/x")
    (let [idx  (reflect/reference-index dir)
          freq (reflect/source-frequency dir)]
      (is (= "Foo (a.com)" (get-in idx ["https://a.com/foo" :title])))
      (is (contains? idx "https://a.com/foo"))
      (is (= 3 (count (get freq "https://a.com/foo"))))   ; the card + two notes
      (is (= 2 (count (get freq "https://b.org/x")))))))

(deftest concept-frequency-counts-dangling-wikilinks
  (let [dir (str (System/getProperty "java.io.tmpdir") "/reflect-concept-" (System/currentTimeMillis))
        w   (fn [rel s] (let [f (io/file dir rel)] (io/make-parents f) (spit f s)))]
    (w "content/concepts/Sandbox.md" "---\ntitle: Sandbox\n---\nUses [[Multi-agent system]] and [[Least privilege]].")
    (w "content/concepts/Orchestrator.md" "---\ntitle: Orchestrator\n---\nA [[Multi-agent system]] coordinator; see [[Sandbox]].")
    (let [freq (reflect/concept-frequency dir)]
      (is (= 2 (count (get-in freq ["multi-agent-system" :files]))) "dangling concept linked by 2 cards")
      (is (= "Multi-agent system" (get-in freq ["multi-agent-system" :title])))
      (is (nil? (get freq "sandbox")) "resolves to an existing card -> not a candidate")
      (is (= 1 (count (get-in freq ["least-privilege" :files]))) "dangling, only 1 card"))))

(deftest open-questions-cleans-and-dedups
  (let [dir (str (System/getProperty "java.io.tmpdir") "/reflect-oq-" (System/currentTimeMillis))
        w   (fn [rel s] (let [f (io/file dir rel)] (io/make-parents f) (spit f s)))]
    (w "content/references/a.md"
       "---\ntitle: A\n---\nbody\n\n## Open questions\n\n- **Trust anchor**: does DDC bootstrap trust?\n- Why does `Y` matter?\n\n## Sources\n\n- https://a.com")
    (w "content/concepts/b.md"
       "---\ntitle: B\n---\ntext\n\n## Open questions\n- does DDC bootstrap trust?\n")
    (let [qs (reflect/open-questions dir)]
      (is (= #{"does DDC bootstrap trust?" "Why does Y matter?"} (set qs))
          "drops the **label**: prefix + inline markdown; dedups across cards")
      (is (not-any? #(re-find #"[*`]" %) qs) "no markdown survives")
      (is (not-any? #(str/includes? % "https://a.com") qs) "stops at the next heading")
      (is (= #{"content/references/a.md" "content/concepts/b.md"}
             (get (reflect/question-sources dir) "does DDC bootstrap trust?"))
          "aggregates every source card that raises a question"))))
