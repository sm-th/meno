(ns researcher.wiki-test
  (:require [clojure.test :refer [deftest is]]
            [researcher.wiki :as wiki]
            [clojure.string :as str]))

(deftest card-file-cases
  (is (= "Hello, World!" (wiki/card-file "Hello, World!")) "keeps readable chars; Quartz slugs the URL")
  (is (= "Least   Privilege" (wiki/card-file "  Least   Privilege  ")) "trims ends")
  (is (= "abc" (wiki/card-file "a/b/c")) "strips path separators")
  (is (= "Reproducible builds (reproducible-builds.org)"
         (wiki/card-file "Reproducible builds (reproducible-builds.org)")) "parens/dots kept"))

(deftest card-routing-by-type
  (is (= "content/concepts/x.md"    (wiki/card-rel :concept "x")))
  (is (= "content/references/y.md"  (wiki/card-rel :reference "y")))
  (is (= "content/connections/z.md" (wiki/card-rel :connection "z")))
  (is (= "content/meta/m.md"        (wiki/card-rel :meta "m")))
  (is (= "content/concepts/d.md"    (wiki/card-rel nil "d")) "defaults to concepts"))

(deftest render-concept
  (let [md (wiki/render {:title "Least privilege" :type :concept
                         :tags ["security" "access"]
                         :body "The principle. See [[Sandboxing]]."
                         :sources ["https://nist.gov/x"]})]
    (is (str/includes? md "title: Least privilege"))
    (is (str/includes? md "type: concept"))
    (is (str/includes? md "tags: [security, access]"))
    (is (str/includes? md "## Sources"))
    (is (str/includes? md "- https://nist.gov/x"))
    (is (not (str/includes? md "seed:")) "concept card has no seed")))

(deftest render-connection-with-seed
  (let [md (wiki/render {:title "LP in ephemeral agents" :type :connection
                         :body "[[Least privilege]] applies."
                         :seed "https://andysmith.ai/x"})]
    (is (str/includes? md "type: connection"))
    (is (str/includes? md "seed: https://andysmith.ai/x"))))

(deftest domain-of-cases
  (is (= "reproducible-builds.org" (wiki/domain-of "https://www.Reproducible-Builds.org/docs/")))
  (is (= "andysmith.ai" (wiki/domain-of "https://andysmith.ai/2026/x")))
  (is (= "unknown" (wiki/domain-of nil))))

(deftest reference-nests-under-domain-by-title
  (is (= "content/references/andysmith.ai/Ephemeral agents (andysmith.ai).md"
         (wiki/card-rel :reference "Ephemeral agents (andysmith.ai)" "andysmith.ai")))
  (is (= "content/concepts/Principle of least privilege.md"
         (wiki/card-rel :concept "Principle of least privilege")) "non-references flat, named by title"))
