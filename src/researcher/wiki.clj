(ns researcher.wiki
  "Branch-scoped write side of the wiki. A writer is bound to {:repo :branch};
   every put commits straight to that branch (bot identity, unsigned). Markdown
   is the source of truth; the graph connector reads it back."
  (:require [clojure.java.shell :refer [sh]]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(defn slugify [title]
  (-> title str/lower-case
      (str/replace #"[^a-z0-9]+" "-")
      (str/replace #"(^-+|-+$)" "")))

(defn- git! [repo & args]
  (let [r (apply sh "git" "-C" repo "-c" "safe.directory=*" args)]
    (when-not (zero? (:exit r))
      (throw (ex-info "git failed" {:args args :err (:err r)})))
    r))

(defn render
  "Render a concept/reference page to Markdown. page: :title :type :description
   :tags :body :sources [url] :collection-url."
  [{:keys [title type description tags body sources collection-url seed]}]
  (str "---\n"
       "title: " title "\n"
       "type: " (name (or type :concept)) "\n"
       (when description (str "description: " description "\n"))
       (when (seq tags) (str "tags: [" (str/join ", " tags) "]\n"))
       (when seed (str "seed: " seed "\n"))
       "---\n\n"
       (str/trim (or body "")) "\n"
       (when (seq sources)
         (str "\n## Sources\n\n" (str/join "\n" (map #(str "- " %) sources)) "\n"))
       (when collection-url
         (str "\nSaved references: " collection-url "\n"))))

(defn writer
  "Bind a writer to a wiki working copy + target branch, using bot identity from
   cfg (:wiki :author-name/-email)."
  [cfg repo branch]
  {:cfg cfg :repo repo :branch branch})

(defn ensure-branch! [{:keys [repo branch]}]
  (git! repo "checkout" "-B" branch))

(def section
  "Wiki subfolder per card type — keeps meta/concepts/references/connections apart."
  {:concept "concepts" :reference "references" :connection "connections" :meta "meta"})

(defn card-rel [type slug]
  (str "content/" (get section (or type :concept) "concepts") "/" slug ".md"))

(defn put-page!
  "Write/overwrite a card on the writer's branch and commit. Returns page meta."
  [{:keys [cfg repo branch] :as w} page]
  (let [slug (slugify (:title page))
        rel  (card-rel (:type page) slug)
        name  (get-in cfg [:wiki :author-name]  "smith-wiki-bot")
        email (get-in cfg [:wiki :author-email] "bot@smith.wiki")]
    (ensure-branch! w)
    (io/make-parents (io/file repo rel))
    (spit (str repo "/" rel) (render page))
    (git! repo "add" rel)
    (git! repo
          "-c" (str "user.name=" name)
          "-c" (str "user.email=" email)
          "-c" "commit.gpgsign=false"
          "commit" "-m" (str "wiki: " (:title page)))
    {:slug slug :path rel :branch branch :title (:title page)}))

(defn work-dir [cfg]
  (or (get-in cfg [:wiki :work-dir])
      (str (System/getProperty "user.home") "/.cache/researcher/wiki-work")))

(defn- auth-header [cfg]
  (str "http.extraheader=Authorization: Basic "
       (.encodeToString (java.util.Base64/getEncoder)
                        (.getBytes (str "x-access-token:"
                                        (System/getenv (get-in cfg [:wiki :token-env] "GH_TOKEN")))))))

(defn prepare-branch!
  "Ensure a managed work clone of the (public) wiki exists, fetch base, and
   checkout -B branch from origin/base. Returns the clone path. Read is
   token-less (public repo); push carries the token in an http header, not on
   disk (see push-branch!)."
  [cfg branch]
  (let [work   (work-dir cfg)
        base   (get-in cfg [:wiki :base] "main")
        remote (str "https://github.com/" (get-in cfg [:github :repo]) ".git")]
    (when-not (.exists (io/file work ".git"))
      (io/make-parents (io/file work ".git"))
      (let [r (sh "git" "clone" remote work)]
        (when-not (zero? (:exit r)) (throw (ex-info "wiki clone failed" {:err (:err r)})))))
    (git! work "remote" "set-url" "origin" remote)
    (git! work "fetch" "origin" base)
    (git! work "checkout" "-B" branch (str "origin/" base))
    work))

(defn ahead?
  "True if branch has commits beyond origin/base (i.e. the worker wrote pages)."
  [repo base branch]
  (let [r (sh "git" "-C" repo "rev-list" "--count" (str "origin/" base ".." branch))]
    (pos? (Integer/parseInt (str/trim (:out r))))))

(defn push-branch! [cfg repo branch]
  (git! repo "-c" (auth-header cfg) "push" "-u" "origin" branch))

(defn page-titles
  "Slugs of existing wiki cards (recursively under content/) — dedup context."
  [repo]
  (let [dir (io/file repo "content")]
    (when (.isDirectory dir)
      (->> (file-seq dir)
           (filter #(and (.isFile %) (str/ends-with? (.getName %) ".md")))
           (mapv #(str/replace (.getName %) #"\.md$" ""))))))
