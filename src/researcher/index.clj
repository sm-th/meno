(ns researcher.index
  "Dense retrieval: embed the corpus into Qdrant, recall by kNN."
  (:require [researcher.corpus :as corpus]
            [researcher.embed :as embed]
            [researcher.qdrant :as qdrant]
            [researcher.note :as note]
            [researcher.git :as git]
            [clojure.string :as str])
  (:import (java.util UUID)))

(defn- uuid [s] (str (UUID/nameUUIDFromBytes (.getBytes (str s) "UTF-8"))))

(defn- doc-text [d]
  (str (:title d) "\n" (str/join " " (:tags d)) "\n" (:body d)))

(defn build!
  "Embed the whole corpus and upsert into Qdrant. Returns the doc count."
  [cfg]
  (let [docs (vec (corpus/all-docs cfg))
        vecs (embed/embed-batched cfg (map doc-text docs))
        pts  (map (fn [d v]
                    {:id (uuid (:slug d)) :vector v
                     :payload (select-keys d [:slug :title :url :kind :tags])})
                  docs vecs)]
    (qdrant/ensure-collection! cfg (get-in cfg [:embed :dim]))
    (doseq [batch (partition-all 128 pts)] (qdrant/upsert! cfg batch))
    (count pts)))

(defn recall
  "Top-k Qdrant hits for arbitrary query text."
  [cfg query-text k]
  (let [[qv] (embed/embed-texts cfg [query-text])]
    (qdrant/search cfg qv k)))

(defn recall-newest
  "Qualitative eyeball eval: recall for the newest published note."
  [cfg k]
  (let [blog (get-in cfg [:blog :root])
        {:keys [sha]} (git/newest-publish blog)
        rel  (first (git/commit-post-files blog sha))
        n    (note/load-note blog rel)
        hits (recall cfg (doc-text {:title (:title n) :tags (:tags n) :body (:body n)}) (inc k))]
    (println "recall for:" (:title n) "\n")
    (doseq [h hits
            :let [p (get h "payload")]
            :when (not= (get p "slug") (:slug n))]
      (println (format "  %.3f  [%s] %s"
                       (double (get h "score")) (get p "kind") (get p "title"))))))
