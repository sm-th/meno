(ns researcher.corpus
  "Code-side corpus index + cheap tag similarity (legacy recall), plus full-text
   docs for dense embedding (real recall via Qdrant)."
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

;; ---- lightweight (titles/tags only) ----

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
  "Rank items by shared-tag overlap with target-tags (legacy recall candidates)."
  [target-tags items]
  (let [t (set target-tags)]
    (->> items
         (map #(assoc % :overlap (count (set/intersection t (set (:tags %))))))
         (filter #(pos? (:overlap %)))
         (sort-by (comp - :overlap)))))

;; ---- full docs (with body) for embeddings ----

(defn blog-docs [blog-root]
  (for [f (md-files (str blog-root "/src"))
        :when (str/ends-with? f "/index.md")]
    (let [{:keys [frontmatter body]} (note/parse-frontmatter (slurp f))
          rel (subs f (inc (count blog-root)))]
      {:kind "note"
       :slug (note/path->slug f)
       :url  (note/path->url rel)
       :title (:title frontmatter)
       :tags (:tags frontmatter)
       :body body})))

(defn wiki-docs [wiki-root content]
  (for [f (md-files (str wiki-root "/" content))]
    (let [{:keys [frontmatter body]} (note/parse-frontmatter (slurp f))]
      {:kind "page"
       :slug (str "wiki:" (.getName (io/file f)))
       :url  f
       :title (:title frontmatter)
       :tags (:tags frontmatter)
       :body body})))

(defn all-docs [cfg]
  (concat (blog-docs (get-in cfg [:blog :root]))
          (wiki-docs (get-in cfg [:wiki :root]) (get-in cfg [:wiki :content]))))
