(ns researcher.chunk-test
  (:require [clojure.test :refer [deftest is]]
            [researcher.chunk :as chunk]))

(deftest blank-and-short
  (is (= [] (chunk/chunks "   ")))
  (is (= ["short"] (chunk/chunks "short" 100 10))))

(deftest long-text-overlapping-windows
  (let [text (apply str (repeat 50 "0123456789"))   ; 500 chars
        cs   (chunk/chunks text 100 20)]             ; step = 80
    (is (> (count cs) 1))
    (is (every? #(<= (count %) 100) cs) "no chunk exceeds size")
    (is (= (subs text 0 100) (first cs)))
    (is (= (subs (first cs) 80 100) (subs (second cs) 0 20)) "20-char overlap")))
