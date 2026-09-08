(ns researcher.graph
  "Knowledge graph derived from the markdown wiki (content/*.md). This is the
   SINGLE place that parses wiki markdown; algorithms traverse this structure and
   never re-parse. Nodes = pages (concept/reference); edges = [[wikilinks]] and
   cited source URLs. Rebuildable from markdown (markdown = source of truth)."
  (:require [researcher.note :as note]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def ^:private wikilink-re #"\[\[([^\]|]+)(?:\|[^\]]+)?\]\]")
(def ^:private url-re       #"https?://[^\s)\]\"'>]+")

(defn- md-files [dir]
  (let [d (io/file dir)]
    (when (.exists d)
      (->> (file-seq d)
           (filter #(and (.isFile %) (str/ends-with? (.getName %) ".md")))
           (map #(.getPath %))))))

(defn- page [path]
  (let [{:keys [frontmatter body]} (note/parse-frontmatter (slurp path))]
    {:title   (:title frontmatter)
     :type    (:type frontmatter)
     :tags    (:tags frontmatter)
     :path    path
     :links   (set (map second (re-seq wikilink-re body)))
     :sources (vec (distinct (re-seq url-re body)))}))

(defn load-graph
  "Build the graph from the wiki content dir. Nodes keyed by title."
  [cfg]
  (let [dir (str (get-in cfg [:wiki :root]) "/" (get-in cfg [:wiki :content]))
        pages (map page (md-files dir))]
    {:nodes (into {} (map (juxt :title identity) pages))}))

;; ---- metrics (drive deepen / promote decisions) ----

(defn in-degree
  "How many pages link to `title` via [[wikilinks]]."
  [g title]
  (count (filter #(contains? (:links %) title) (vals (:nodes g)))))

(defn central
  "Top-n page titles by in-degree (what to deepen)."
  [g n]
  (->> (keys (:nodes g)) (sort-by #(- (in-degree g %))) (take n)))

(defn reference-frequency
  "Cited source URL -> count across all pages (promotion signal for study)."
  [g]
  (->> (mapcat :sources (vals (:nodes g)))
       frequencies
       (sort-by (comp - val))))

(defn orphans
  "Pages nothing links to."
  [g]
  (filterv #(zero? (in-degree g %)) (keys (:nodes g))))

(def content-types
  "Card types that carry knowledge (vs meta: skills, conventions, the index).
   Only these participate in the research frontier."
  #{"concept" "connection" "answer" "reference"})

(defn- content-node? [p]
  (contains? content-types (some-> (:type p) name)))

(defn dangling
  "[[wikilink]] targets with no card yet, ranked by how many CONTENT cards want
   one. Each: {:title target :refs n :referrers [page-titles]}. A dangling link is
   the wiki's own research request - a card someone linked to that does not exist -
   so this list IS the lazy-dereference frontier: to research the wiki, materialize
   the most-wanted missing card, one at a time. Links from meta cards
   (skills/conventions) are ignored - their [[wikilinks]] are prose examples."
  [g]
  (let [have (set (keys (:nodes g)))]
    (->> (vals (:nodes g))
         (filter content-node?)
         (mapcat (fn [p] (map (fn [t] [t (:title p)]) (:links p))))
         (remove (fn [[t _]] (contains? have t)))
         (group-by first)
         (map (fn [[t pairs]] {:title t :refs (count pairs)
                               :referrers (vec (sort (map second pairs)))}))
         (sort-by (juxt (comp - :refs) :title)))))
