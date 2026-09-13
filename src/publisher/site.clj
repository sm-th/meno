(ns publisher.site
  "11ty site adapter — write an English post into the site repo over git, commit,
   and push (the site auto-deploys). URLs are short and day-level, matching the
   file path: src/YYYY/Mon/D/<slug>/index.md -> <site>/YYYY/Mon/D/<slug>/."
  (:require [clojure.java.shell :as sh]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (java.time Instant ZoneId)
           (java.time.format DateTimeFormatter)
           (java.util Locale)))

(defn slug [title]
  (-> (str/lower-case (str title))
      (str/replace #"[^a-z0-9]+" "-")
      (str/replace #"^-+|-+$" "")))

(defn- date-parts [^Instant at zone]
  (let [z (.atZone at (ZoneId/of zone))]
    {:year (format "%04d" (.getYear z))
     :mon  (.format (DateTimeFormatter/ofPattern "MMM" Locale/ENGLISH) z)
     :day  (str (.getDayOfMonth z))
     :iso  (.format (DateTimeFormatter/ofPattern "yyyy-MM-dd'T'HH:mm:ssxxx") z)}))

(defn- frontmatter [{:keys [title description iso tags]}]
  (str "---\n"
       "title: " title "\n"
       "type: post\n"
       (when (seq tags) (str "tags: [" (str/join ", " tags) "]\n"))
       "description: " description "\n"
       "date: " iso "\n"
       "---\n\n"))

(defn- git-base
  "git argv with identity/signing from cfg baked in as -c flags."
  [dir {:keys [ssh-key user-name user-email sign signing-key allowed-signers]}]
  (cond-> ["git" "-C" dir
           "-c" (str "user.name=" (or user-name "Andy Smith"))
           "-c" (str "user.email=" (or user-email "me@andysmith.ai"))]
    ssh-key (into ["-c" (str "core.sshCommand=ssh -i " ssh-key " -o IdentitiesOnly=yes")])
    sign    (into ["-c" "commit.gpgsign=true" "-c" "gpg.format=ssh"
                   "-c" (str "user.signingkey=" signing-key)
                   "-c" (str "gpg.ssh.allowedSignersFile=" allowed-signers)])))

(defn- git! [dir cfg & args]
  (let [{:keys [exit out err]} (apply sh/sh (concat (git-base dir cfg) args))]
    (when-not (zero? exit)
      (throw (ex-info "site: git failed" {:args (vec args) :err (str/trim (str err))})))
    (str/trim out)))

(defn publish!
  "Write an English post {:title :description :body [:tags]} dated `at`
   (java.time.Instant) into the site clone, commit, and push. Idempotent at the
   git level: a re-run after a failed push tolerates 'nothing to commit' and
   re-pushes (push of an already-pushed commit is a no-op). Returns {:url :rel
   :sha}. cfg: :clone-dir :site-url :posts-subdir :branch :zone :git {…} :push?."
  [{:keys [clone-dir site-url posts-subdir branch zone git push?]
    :or   {posts-subdir "src" branch "main" zone "+07:00" push? true}}
   {:keys [title description body tags]} at]
  (let [{:keys [year mon day iso]} (date-parts at zone)
        sl   (slug title)
        rel  (str posts-subdir "/" year "/" mon "/" day "/" sl "/index.md")
        file (io/file clone-dir rel)]
    (io/make-parents file)
    (spit file (str (frontmatter {:title title :description description :iso iso :tags tags})
                    body "\n"))
    (git! clone-dir git "add" rel)
    (let [{:keys [exit out err]}
          (apply sh/sh (concat (git-base clone-dir git)
                               ["commit" "-q" "-m" (str "publish: " title)]))]
      (when-not (or (zero? exit) (re-find #"nothing to commit" (str out err)))
        (throw (ex-info "site: commit failed" {:err (str/trim (str err))}))))
    (let [sha (git! clone-dir git "rev-parse" "HEAD")]
      (when push? (git! clone-dir git "push" "origin" branch))
      {:url (str site-url "/" year "/" mon "/" day "/" sl "/") :rel rel :sha sha})))
