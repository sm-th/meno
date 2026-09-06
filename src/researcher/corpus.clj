(ns researcher.corpus
  "Code-side corpus index + cheap similarity (recall). This is a stand-in for
   embedding kNN: it surfaces CANDIDATES; an LLM later judges meaning (precision)."
  (:require [researcher.note :as note]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]))

(defn- md-files [root]
  (let [d (io/file root)]
    (when (.exists d)
      (->> (file-seq d)
           (filter #(and (.isFile %) (str/ends-with? (.getName %) ".md")))
           (map #(.getPath %))))))

(defn wiki-pages [wiki-root content]
  (for [f (md-files (str wiki-root "/" content))]
    (let [{:keys [frontmatter]} (note/parse-frontmatter (slurp f))]
      {:title (:title frontmatter) :tags (:tags frontmatter) :path f})))

(defn blog-posts [blog-root]
  (for [f (md-files (str blog-root "/src"))
        :when (str/ends-with? f "/index.md")]
    (let [{:keys [frontmatter]} (note/parse-frontmatter (slurp f))]
      {:title (:title frontmatter)
       :tags (:tags frontmatter)
       :slug (note/path->slug f)
       :path f})))

(defn similar-by-tags
  "Rank items by shared-tag overlap with target-tags (recall candidates)."
  [target-tags items]
  (let [t (set target-tags)]
    (->> items
         (map #(assoc % :overlap (count (set/intersection t (set (:tags %))))))
         (filter #(pos? (:overlap %)))
         (sort-by (comp - :overlap)))))
