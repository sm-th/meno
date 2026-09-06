(ns researcher.config
  (:require [clojure.edn :as edn]))

(defn expand
  "Expand a leading ~ to the user's home directory."
  [p]
  (if (and (string? p) (.startsWith p "~"))
    (str (System/getProperty "user.home") (subs p 1))
    p))

(defn load-config
  ([] (load-config "config.edn"))
  ([file]
   (-> (edn/read-string (slurp file))
       (update-in [:blog :root] expand)
       (update-in [:wiki :root] expand))))
