(ns researcher.wiki-test
  (:require [clojure.test :refer [deftest is]]
            [researcher.wiki :as wiki]
            [clojure.string :as str]))

(deftest slugify-cases
  (is (= "hello-world" (wiki/slugify "Hello, World!")))
  (is (= "least-privilege" (wiki/slugify "  Least   Privilege  ")))
  (is (= "a-b-c" (wiki/slugify "a/b/c"))))

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
