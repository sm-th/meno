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

(defn as-list
  "Coerce an LLM-supplied field to a seq of strings: a string stays ONE item
   (never exploded into characters), a collection is kept as-is, nil -> empty.
   Guards every 'list' field the model might hand us as a bare string."
  [x]
  (cond (nil? x) [] (sequential? x) x :else [x]))

(defn render
  "Render a card to Markdown. page: :title :type :description :tags :body :sources
   [url] :collection-url :seed. For :reference cards, bibliographic fields
   :author :url :date :kind go in the frontmatter."
  [{:keys [title type description tags body sources collection-url seed author url date kind]}]
  (let [tags (as-list tags) sources (as-list sources)]
   (str "---\n"
       "title: " title "\n"
       "type: " (name (or type :concept)) "\n"
       (when kind (str "kind: " kind "\n"))
       (when author (str "author: " author "\n"))
       (when url (str "url: " url "\n"))
       (when date (str "date: " date "\n"))
       (when description (str "description: " description "\n"))
       (when (seq tags) (str "tags: [" (str/join ", " tags) "]\n"))
       (when seed (str "seed: " seed "\n"))
       "---\n\n"
       (str/trim (or body "")) "\n"
       (when (seq sources)
         (str "\n## Sources\n\n" (str/join "\n" (map #(str "- " %) sources)) "\n"))
       (when collection-url
         (str "\nSaved references: " collection-url "\n")))))

(defn writer
  "Bind a writer to a wiki working copy + target branch, using bot identity from
   cfg (:wiki :author-name/-email)."
  [cfg repo branch]
  {:cfg cfg :repo repo :branch branch})

(defn ensure-branch! [{:keys [repo branch]}]
  (git! repo "checkout" "-B" branch))

(def section
  "Wiki subfolder per card type — keeps meta/concepts/references/connections apart."
  {:concept "concepts" :reference "references" :connection "connections" :answer "answers" :meta "meta"})

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

(defn- ssh-cmd [cfg]
  ;; Wiki pushes use the human's write access (id_alchery) over SSH — the bot PAT
  ;; lacks Contents:write. In prod, grant the bot Contents:write and switch back.
  (str "ssh -i " (or (get-in cfg [:wiki :ssh-key])
                     (str (System/getProperty "user.home") "/.ssh/id_alchery"))
       " -o IdentitiesOnly=yes -o StrictHostKeyChecking=no"))

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
  ;; Force-push the agent's own ephemeral per-issue branch. Safe: `researcher/issue-N`
  ;; is never shared, and prepare-branch! rebuilds it from origin/base every run, so a
  ;; re-run diverges from any prior PR's commits and a plain push would be rejected
  ;; non-fast-forward ("git failed").
  (git! repo "-c" (str "core.sshCommand=" (ssh-cmd cfg))
        "push" "--force" (str "git@github.com:" (get-in cfg [:github :repo]) ".git")
        (str branch ":" branch)))


(defn page-titles
  "Slugs of existing wiki cards (recursively under content/) — dedup context."
  [repo]
  (let [dir (io/file repo "content")]
    (when (.isDirectory dir)
      (->> (file-seq dir)
           (filter #(and (.isFile %) (str/ends-with? (.getName %) ".md")))
           (mapv #(str/replace (.getName %) #"\.md$" ""))))))
