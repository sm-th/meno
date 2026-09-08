(ns researcher.graph-test
  (:require [clojure.test :refer [deftest is]]
            [researcher.graph :as graph]
            [clojure.java.io :as io]))

(def g
  {:nodes {"A" {:title "A" :links #{"B" "C"} :sources ["https://s1"]}
           "B" {:title "B" :links #{"C"}     :sources ["https://s1" "https://s2"]}
           "C" {:title "C" :links #{}         :sources []}}})

(deftest metrics
  (is (= 0 (graph/in-degree g "A")))
  (is (= 1 (graph/in-degree g "B")))
  (is (= 2 (graph/in-degree g "C")))
  (is (= ["C" "B"] (take 2 (graph/central g 2))) "most-linked first")
  (is (= ["A"] (graph/orphans g)) "nothing links to A")
  (is (= [["https://s1" 2] ["https://s2" 1]] (mapv vec (graph/reference-frequency g)))))

(deftest load-graph-from-markdown
  (let [dir     (str (System/getProperty "java.io.tmpdir") "/rgraph-" (System/currentTimeMillis))
        content (str dir "/content")]
    (.mkdirs (io/file content))
    (spit (str content "/a.md") "---\ntitle: A\ntype: concept\n---\n\nlinks [[B]] and https://s1")
    (spit (str content "/b.md") "---\ntitle: B\ntype: concept\n---\n\nnothing here")
    (let [gg (graph/load-graph {:wiki {:root dir :content "content"}})]
      (is (= #{"A" "B"} (set (keys (:nodes gg)))))
      (is (= #{"B"} (:links (get-in gg [:nodes "A"]))) "parses [[wikilinks]]")
      (is (= ["https://s1"] (:sources (get-in gg [:nodes "A"]))) "parses cited urls"))))

(deftest dangling-frontier
  (let [g {:nodes {"A" {:title "A" :type "concept" :links #{"B" "X"}}
                   "B" {:title "B" :type "concept" :links #{"X" "Y"}}}}]
    (is (= [{:title "X" :refs 2 :referrers ["A" "B"]}
            {:title "Y" :refs 1 :referrers ["B"]}]
           (graph/dangling g))
        "links with no card, most-wanted first; existing target B excluded")))
