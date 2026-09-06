(ns researcher.refs
  "Reference ingestion: URLs cited in notes -> chunk-embedded into Qdrant as
   kind=reference, so research ticks can ground on the primary sources, not only
   the notes. Text via Jina reader; nothing is written to Linkwarden."
  (:require [researcher.reader :as reader]
            [researcher.chunk :as chunk]
            [researcher.embed :as embed]
            [researcher.qdrant :as qdrant]
            [researcher.note :as note]
            [researcher.git :as git]
            [researcher.corpus :as corpus]
            [clojure.string :as str])
  (:import (java.util UUID)
           (java.security MessageDigest)))

(defn- uuid [s] (str (UUID/nameUUIDFromBytes (.getBytes (str s) "UTF-8"))))

(defn- sha256 [s]
  (->> (.digest (MessageDigest/getInstance "SHA-256") (.getBytes (str s) "UTF-8"))
       (map #(format "%02x" %)) (apply str)))

(def ^:private url-re #"https?://[^\s)\]\"'>]+")

(defn- external? [u]
  (and (not (re-find #"andysmith\.ai" u))
       (not (re-find #"(?i)\.(png|jpe?g|gif|webp|svg|pdf)(\?|$)" u))))

(defn note-urls [note]
  (->> (cons (:link note) (re-seq url-re (:body note)))
       (remove nil?)
       (map #(str/replace % #"[.,;]+$" ""))
       (filter #(re-find #"^https?://" %))
       (filter external?)
       distinct))

(defn ingest-note-refs!
  "Chunk-embed every external reference cited by `note` into Qdrant
   (kind=reference) so ticks can ground on primary sources. Returns url count."
  [cfg note]
  (let [urls (take (get-in cfg [:refs :max-refs-per-note] 10) (note-urls note))]
    (when (seq urls)
      (qdrant/ensure-collection! cfg (get-in cfg [:embed :dim])))
    (doseq [u urls]
      (println "  ref:" u)
      (if-let [text (reader/readable u)]
        (let [cs   (chunk/chunks text
                                 (get-in cfg [:refs :chunk-chars] 3200)
                                 (get-in cfg [:refs :chunk-overlap] 300))
              vecs (embed/embed-batched cfg cs)
              now  (str (java.time.Instant/now))
              pts  (map-indexed
                    (fn [i [c v]]
                      {:id (uuid (str u "#" i)) :vector v
                       :payload {:kind "reference" :source u :title (:title note)
                                 :chunk i :content_hash (sha256 c)
                                 :embed_model (get-in cfg [:embed :model]) :ingested_at now}})
                    (map vector cs vecs))]
          (doseq [b (partition-all 128 pts)] (qdrant/upsert! cfg b))
          (println "    chunks:" (count cs)))
        (println "    (no readable text)")))
    (count urls)))

(defn ingest-newest! [cfg]
  (let [blog (get-in cfg [:blog :root])
        {:keys [sha]} (git/newest-publish blog)
        rel (first (git/commit-post-files blog sha))
        n   (note/load-note blog rel)]
    (println "ingest-refs for:" (:title n))
    (ingest-note-refs! cfg n)))

(defn ingest-slug! [cfg slug]
  (let [blog (get-in cfg [:blog :root])
        post (first (filter #(= (:slug %) slug) (corpus/blog-posts blog)))]
    (if post
      (let [n (note/load-note blog (subs (:path post) (inc (count blog))))]
        (println "ingest-refs for:" (:title n))
        (ingest-note-refs! cfg n))
      (println "no such slug:" slug))))

(defn ingest-all! [cfg]
  (let [blog (get-in cfg [:blog :root])]
    (reduce (fn [acc post]
              (let [n (note/load-note blog (subs (:path post) (inc (count blog))))]
                (+ acc (ingest-note-refs! cfg n))))
            0
            (researcher.corpus/blog-posts blog))))
