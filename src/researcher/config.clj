(ns researcher.config
  (:require [clojure.edn :as edn]))

(defn expand
  "Expand a leading ~ to the user's home directory."
  [p]
  (if (and (string? p) (.startsWith p "~"))
    (str (System/getProperty "user.home") (subs p 1))
    p))

(defn- env-or [k v] (or (System/getenv k) v))

(defn load-config
  ([] (load-config "config.edn"))
  ([file]
   (-> (edn/read-string (slurp file))
       (update-in [:blog :root] #(expand (env-or "RESEARCHER_BLOG_ROOT" %)))
       (update-in [:wiki :root] #(expand (env-or "RESEARCHER_WIKI_ROOT" %))))))
