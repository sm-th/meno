(ns researcher.note
  "Parse a blog post: minimal YAML frontmatter + markdown body."
  (:require [clojure.string :as str]))

(defn- parse-tags [v]
  (when v
    (-> v
        (str/replace #"[\[\]]" "")
        (str/split #",\s*")
        (->> (map str/trim) (remove str/blank?) vec))))

(defn parse-frontmatter [text]
  (if-let [[_ fm body] (re-find #"(?s)^---\n(.*?)\n---\n?(.*)$" text)]
    (let [kv (->> (str/split-lines fm)
                  (keep #(when-let [m (re-find #"^([\w-]+):\s*(.*)$" %)]
                           [(keyword (nth m 1)) (nth m 2)]))
                  (into {}))]
      {:frontmatter (cond-> kv (:tags kv) (assoc :tags (parse-tags (:tags kv))))
       :body (str/trim body)})
    {:frontmatter {} :body (str/trim text)}))

(defn path->slug [p]
  (second (re-find #"/([^/]+)/index\.md$" p)))

(defn path->url [p]
  (some-> (re-find #"src(/\d{4}/[^/]+/\d+/[^/]+)/index\.md$" p) second (str "/")))

(defn load-note [repo-root rel-path]
  (let [{:keys [frontmatter body]} (parse-frontmatter (slurp (str repo-root "/" rel-path)))]
    (merge frontmatter
           {:body body
            :slug (path->slug rel-path)
            :url  (path->url rel-path)
            :path rel-path})))
