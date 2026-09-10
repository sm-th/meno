(ns researcher.recall
  "Semantic similar-page search over the KB.

  Each page is atomic and short (one idea), so it embeds as ONE vector into a
  dedicated Qdrant collection (kept separate from the reference `corpus`). This
  is the dedup + linking signal literal `grep` cannot give: it finds a page that
  means the same thing under a different title."
  (:require [researcher.kb :as kb]
            [researcher.embed :as embed]
            [researcher.qdrant :as qdrant]
            [clojure.string :as str]))

(defn- kbcfg
  "cfg pointed at the KB (page) collection, distinct from the corpus one."
  [cfg]
  (assoc-in cfg [:qdrant :collection] (get-in cfg [:qdrant :kb-collection] "kb")))

(defn- pid
  "Deterministic point id from title, so re-indexing a page upserts in place."
  [title]
  (str (java.util.UUID/nameUUIDFromBytes (.getBytes (kb/slug title) "UTF-8"))))

(defn- strip-fm [md]
  (str/replace (str md) #"(?s)^---\n.*?\n---\n?" ""))

(defn- page-text [title body]
  (str title "\n\n" (str/trim (strip-fm body))))

(defn ensure! [cfg]
  (qdrant/ensure-collection! (kbcfg cfg) (get-in cfg [:embed :dim] 4096)))

(defn index-page!
  "Embed one page (title + body) and upsert it as a single point keyed by title."
  [cfg {:keys [title type body]}]
  (let [[v] (embed/embed-texts cfg [(page-text title body)])]
    (qdrant/upsert! (kbcfg cfg)
                    [{:id (pid title) :vector v
                      :payload {:title title :type (name (or type :concept))}}])
    {:indexed title}))

(defn similar
  "Nearest existing pages to `text`: [{:title :type :score}], best first.
   Excludes `self` (the page being written, if any)."
  ([cfg text] (similar cfg text 5 nil))
  ([cfg text k self]
   (let [[v] (embed/embed-texts cfg [text])
         hits (qdrant/search (kbcfg cfg) v (inc k))]
     (->> hits
          (map (fn [h] {:title (get-in h ["payload" "title"])
                        :type  (get-in h ["payload" "type"])
                        :score (get h "score")}))
          (remove #(= (:title %) self))
          (take k)
          vec))))

(defn reindex-all!
  "Backfill: (re)embed every KB page into Qdrant. Idempotent (upsert by title)."
  [cfg]
  (ensure! cfg)
  (let [idx    (kb/index cfg)
        texts  (mapv (fn [{:keys [title]}] (page-text title (kb/read-page cfg title))) idx)
        vecs   (embed/embed-batched cfg texts)
        points (mapv (fn [{:keys [title type]} v]
                       {:id (pid title) :vector v
                        :payload {:title title :type type}})
                     idx vecs)]
    (qdrant/upsert! (kbcfg cfg) points)
    {:reindexed (count points)}))
