(ns researcher.note-test
  (:require [clojure.test :refer [deftest is]]
            [researcher.note :as note]))

(deftest parse-frontmatter-basic
  (let [{:keys [frontmatter body]}
        (note/parse-frontmatter "---\ntitle: Hello\ntags: [a, b]\n---\n\nBody text\nmore")]
    (is (= "Hello" (:title frontmatter)))
    (is (= ["a" "b"] (:tags frontmatter)))
    (is (= "Body text\nmore" body))))

(deftest parse-frontmatter-none
  (let [{:keys [frontmatter body]} (note/parse-frontmatter "just a body")]
    (is (= {} frontmatter))
    (is (= "just a body" body))))

(deftest path-helpers
  (is (= "ephemeral-agents" (note/path->slug "src/2026/Sep/6/ephemeral-agents/index.md")))
  (is (= "/2026/Sep/6/ephemeral-agents/"
         (note/path->url "src/2026/Sep/6/ephemeral-agents/index.md")))
  (is (nil? (note/path->url "not/a/post.md"))))
