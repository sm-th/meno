(ns researcher.refs-test
  (:require [clojure.test :refer [deftest is]]
            [researcher.refs :as refs]))

(deftest note-urls-extract
  (let [n {:link "https://example.com/a"
           :body (str "See https://en.wikipedia.org/wiki/X, and "
                      "https://andysmith.ai/2026/ (own site, excluded) "
                      "img https://cdn.foo/pic.png (excluded) "
                      "dup https://example.com/a again.")}
        urls (refs/note-urls n)]
    (is (some #{"https://en.wikipedia.org/wiki/X"} urls) "trailing comma stripped")
    (is (some #{"https://example.com/a"} urls) ":link is included")
    (is (not-any? #(re-find #"andysmith\.ai" %) urls) "own site excluded")
    (is (not-any? #(re-find #"\.png" %) urls) "images excluded")
    (is (= (count urls) (count (distinct urls))) "deduped")))
