(ns researcher.kb
  "meno domain — the knowledge base.

  A flat local directory of discourse-graph Markdown pages (one idea per file:
  concept / source / question / claim / research). These files are the source of
  truth (git-versioned); everything else (published HTML, indexes) is derived.
  Bibliographic fields (url/author/date) live in the frontmatter so they're
  machine-parsable and renderable — never prose in the body. The ingest agent
  reads and writes the KB only through these fns; `grep` computes the frontier."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(defn root
  "The KB directory. `:kb :root` from config (default \"kb\"); a leading ~ expands."
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

(defn- yfield
  "A quoted YAML frontmatter line, or nil when the value is blank/absent."
  [k v]
  (when (and v (not (and (string? v) (str/blank? v))))
    (str (name k) ": " (pr-str (str v)) "\n")))

(defn- strip-leading-fm
  "Drop a leading YAML frontmatter block. write-page! generates its own
   frontmatter, so if a caller round-trips a read-page value into :body we must
   not double it."
  [s]
  (str/replace (str s) #"(?s)\A---\r?\n.*?\r?\n---\r?\n?" ""))

(defn write-page!
  "Write/overwrite a page. page = {:title :type :body :url :author :date :tags}.
   Bibliographic fields go in the frontmatter (quoted); body is prose only."
  [cfg {:keys [title type body url author date tags by status]}]
  (let [f       (page-file cfg title)
        fmatter (str "---\n"
                     (yfield :title title)
                     "type: " (name (or type :concept)) "\n"
                     (yfield :by (or by "Andy Smith"))
                     (when (or status (= "claim" (name (or type :concept))))
                       (yfield :status (or status "tentative")))
                     (yfield :url url)
                     (yfield :author author)
                     (yfield :date date)
                     (when (seq tags)
                       (str "tags: [" (str/join ", " (map #(pr-str (str %)) tags)) "]\n"))
                     "---\n\n")
        existed (.exists f)]
    (io/make-parents f)
    (spit f (str fmatter (str/trim (strip-leading-fm body)) "\n"))
    {:wrote (.getName f) :title title :amended existed}))

(defn set-meta!
  "Set frontmatter fields (map field->value) on an existing page, leaving the body
   intact. For backfilling :by / :status without rewriting content."
  [cfg title kvs]
  (let [f (page-file cfg title)]
    (when (.exists f)
      (let [t (slurp f)
            [_ fmb body] (re-find #"(?s)\A---\n(.*?)\n---\n?(.*)\z" t)
            drop? (set (map name (keys kvs)))
            kept  (remove (fn [ln] (some #(str/starts-with? ln (str % ":")) drop?))
                          (str/split-lines (or fmb "")))
            added (for [[k v] kvs :when (some? v)] (str (name k) ": " (pr-str (str v))))]
        (spit f (str "---\n" (str/join "\n" (concat kept added)) "\n---\n\n"
                     (str/trim (or body "")) "\n"))
        {:set (.getName f)}))))

(defn grep
  "Case-insensitive substring search across pages; returns matching titles."
  [cfg q]
  (let [ql (str/lower-case (str q))]
    (vec (for [f (pages cfg) :let [t (slurp f)]
               :when (str/includes? (str/lower-case t) ql)]
           (or (fm t "title") (.getName f))))))

(defn domain
  "Host of a URL without a leading www. — disambiguates :source titles."
  [url]
  (some-> (re-find #"https?://([^/]+)" (str url)) second (str/replace #"^www\." "")))

(defn source-title
  "Canonical :source title = 'Name (domain)', so a reading never collides with a
   concept of the same name. Idempotent: an existing (domain) suffix is left alone."
  [title url]
  (let [d (domain url)]
    (if (and d (not (re-find #"\([^)]*\.[^)]*\)\s*$" (str title))))
      (str title " (" d ")")
      title)))

(defn migrate-source-titles!
  "One-off reconcile: retitle every :source page to `source-title`, rename its file
   to the new slug, and relink references as [[new|old]] (display text preserved).
   Returns {:renamed [[old new] ...]}."
  [cfg]
  (let [renames (vec (for [f (pages cfg)
                           :let [t (slurp f)
                                 title (fm t "title")
                                 new (when title (source-title title (fm t "url")))]
                           :when (and (= (fm t "type") "source") title (not= title new))]
                       [title new]))]
    (doseq [[old new] renames]
      (let [oldf (page-file cfg old)
            body (slurp oldf)
            body' (str/replace body #"(?m)^title:.*$" (fn [_] (str "title: " (pr-str new))))]
        (spit (page-file cfg new) body')
        (when (not= (.getPath oldf) (.getPath (page-file cfg new))) (.delete oldf))))
    (doseq [f (pages cfg) :let [t (slurp f)]]
      (let [t' (reduce (fn [s [old new]]
                         (-> s
                             (str/replace (str "[[" old "|") (str "[[" new "|"))
                             (str/replace (str "[[" old "]]") (str "[[" new "|" old "]]"))))
                       t renames)]
        (when (not= t t') (spit f t'))))
    {:renamed renames}))
