(ns researcher.git
  "Blog git delta: the ingest unit is ONE post per publish: commit."
  (:require [clojure.java.shell :refer [sh]]
            [clojure.string :as str]))

(defn- g [repo & args]
  (apply sh "git" "-C" repo args))

(defn publish-commits
  "publish: commits, newest-first. With `since` (a sha), only commits after it."
  ([repo] (publish-commits repo nil))
  ([repo since]
   (let [range (if since (str since "..HEAD") "HEAD")
         out   (:out (g repo "log" "--format=%H%x09%s" range))]
     (->> (str/split-lines out)
          (remove str/blank?)
          (map #(let [[h s] (str/split % #"\t" 2)] {:sha h :subject s}))
          (filter #(str/starts-with? (:subject %) "publish:"))))))

(defn commit-post-files
  "Post index.md files touched by a commit."
  [repo sha]
  (->> (:out (g repo "show" "--name-only" "--format=" sha))
       str/split-lines
       (filter #(re-find #"src/.*/index\.md$" %))))

(defn newest-publish [repo]
  (first (publish-commits repo)))
