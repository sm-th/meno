(ns researcher.chunk
  "Character-window chunking with overlap (v1; token-aware later if needed)."
  (:require [clojure.string :as str]))

(defn chunks
  ([text] (chunks text 3200 300))
  ([text size overlap]
   (let [t (str/trim (or text ""))]
     (cond
       (str/blank? t) []
       (<= (count t) size) [t]
       :else
       (loop [i 0 acc []]
         (if (>= i (count t))
           acc
           (recur (+ i (- size overlap))
                  (conj acc (subs t i (min (count t) (+ i size)))))))))))
