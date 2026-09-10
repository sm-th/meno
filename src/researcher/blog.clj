(ns researcher.blog
  "meno domain — the blog is the (only, for now) input. Newest published posts,
   newest-first, read from the blog git repo's `publish:` commits."
  (:require [researcher.git :as git]
            [researcher.note :as note]))

(defn recent
  "The newest published posts as [{:title :url :rel}], newest-first, up to `n`."
  [cfg n]
  (let [root (get-in cfg [:blog :root])
        base (get-in cfg [:blog :url] "")]
    (->> (git/publish-commits root)
         (mapcat #(git/commit-post-files root (:sha %)))
         distinct
         (take n)
         (keep (fn [rel]
                 (let [nt (try (note/load-note root rel) (catch Throwable _ nil))]
                   (when (:url nt)
                     {:title (:title nt) :url (str base (:url nt)) :rel rel}))))
         vec)))
