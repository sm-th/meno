(ns researcher.wiki
  "Branch-scoped write side of the wiki. A writer is bound to {:repo :branch};
   every put commits straight to that branch (bot identity, unsigned). Markdown
   is the source of truth; the graph connector reads it back."
  (:require [clojure.java.shell :refer [sh]]
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
  [{:keys [title type description tags body sources collection-url]}]
  (str "---\n"
       "title: " title "\n"
       "type: " (name (or type :concept)) "\n"
       (when description (str "description: " description "\n"))
       (when (seq tags) (str "tags: [" (str/join ", " tags) "]\n"))
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

(defn put-page!
  "Write/overwrite a page on the writer's branch and commit. Returns page meta."
  [{:keys [cfg repo branch] :as w} page]
  (let [slug (slugify (:title page))
        rel  (str "content/" slug ".md")
        name  (get-in cfg [:wiki :author-name]  "smith-wiki-bot")
        email (get-in cfg [:wiki :author-email] "bot@smith.wiki")]
    (ensure-branch! w)
    (spit (str repo "/" rel) (render page))
    (git! repo "add" rel)
    (git! repo
          "-c" (str "user.name=" name)
          "-c" (str "user.email=" email)
          "-c" "commit.gpgsign=false"
          "commit" "-m" (str "wiki: " (:title page)))
    {:slug slug :path rel :branch branch :title (:title page)}))
