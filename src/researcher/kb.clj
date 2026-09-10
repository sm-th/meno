(ns researcher.kb
  "meno domain — the knowledge base.

  A flat local directory of discourse-graph Markdown pages (one idea per file:
  concept / source / question / claim / research). These files are the source of
  truth (git-versioned); everything else (published HTML, indexes) is derived.
  The ingest agent reads and writes the KB only through these fns (granted at the
  grant); `grep` computes the frontier without a build."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(defn root
  "The KB directory. `:kb :root` from config (default \"kb\"); a leading ~ expands
   to the user's home."
  [cfg]
  (let [r (or (get-in cfg [:kb :root]) "kb")]
    (if (str/starts-with? r "~")
      (str (System/getProperty "user.home") (subs r 1))
      r)))

(defn slug [title]
  (-> (str title) str/lower-case str/trim
      (str/replace #"[^a-z0-9]+" "-")
      (str/replace #"^-+|-+$" "")))

(defn- page-file [cfg title] (io/file (root cfg) (str (slug title) ".md")))

(defn- fm [text field]
  (some-> (re-find (re-pattern (str "(?m)^" field ":\\s*(.+)$")) (str text))
          second str/trim (str/replace #"^\"|\"$" "")))

(defn pages [cfg]
  (let [d (io/file (root cfg))]
    (if (.isDirectory d)
      (->> (file-seq d)
           (filter #(and (.isFile %) (str/ends-with? (.getName %) ".md")))
           vec)
      [])))

(defn index
  "Existing pages as [{:title :type :file}] — for dedup and orientation."
  [cfg]
  (vec (for [f (pages cfg) :let [t (slurp f)]]
         {:title (or (fm t "title") (.getName f))
          :type  (or (fm t "type") "?")
          :file  (.getName f)})))

(defn read-page
  "Markdown of the page titled `title`, or nil."
  [cfg title]
  (let [f (page-file cfg title)] (when (.exists f) (slurp f))))

(defn write-page!
  "Write/overwrite a page. page = {:title :type :body}. Returns {:wrote :title}.
   The title is YAML-quoted so colons/specials in it can't break frontmatter."
  [cfg {:keys [title type body]}]
  (let [f       (page-file cfg title)
        fmatter (str "---\ntitle: " (pr-str (str title))
                     "\ntype: " (name (or type :concept)) "\n---\n\n")
        existed (.exists f)]
    (io/make-parents f)
    (spit f (str fmatter (str/trim (str body)) "\n"))
    {:wrote (.getName f) :title title :amended existed}))

(defn grep
  "Case-insensitive substring search across pages; returns matching titles."
  [cfg q]
  (let [ql (str/lower-case (str q))]
    (vec (for [f (pages cfg) :let [t (slurp f)]
               :when (str/includes? (str/lower-case t) ql)]
           (or (fm t "title") (.getName f))))))
