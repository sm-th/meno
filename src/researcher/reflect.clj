(ns researcher.reflect
  "Scheduled reconciliation over the WHOLE wiki, independent of the Todo queue.

   Sources live in notes as plain external URLs. Two jobs keep them tidy:
   - materialize-sources!  a bare URL cited in >= :ingest-threshold notes, with no
                           reference card and no open ingest task -> file an ingest
                           task (READ later turns it into a reference card).
   - relink-sources!       a bare URL that already HAS a reference card -> rewrite it
                           to a [[Card title]] wikilink, commit and push to main.

   Both run off a dedicated main checkout so they never race the worker's branch."
  (:require [clojure.java.shell :refer [sh]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [researcher.runner :as runner]
            [researcher.github :as gh]
            [researcher.projects :as projects]))

;; --------------------------------------------------------------------------
;; git plumbing (own checkout; read-only scan + push-to-main for relink)
;; --------------------------------------------------------------------------

(defn- ssh-cmd [cfg]
  (str "ssh -i " (or (get-in cfg [:wiki :ssh-key])
                     (str (System/getProperty "user.home") "/.ssh/id_alchery"))
       " -o IdentitiesOnly=yes -o StrictHostKeyChecking=no"))

(defn- work-dir [_cfg]
  (str (System/getProperty "user.home") "/.cache/researcher/reflect-work"))

(defn- git! [dir & args]
  (apply sh "git" "-C" dir "-c" "safe.directory=*" args))

(defn- fresh-main!
  "Clean checkout of origin/base at the reflect work dir; returns the path."
  [cfg]
  (let [dir    (work-dir cfg)
        base   (get-in cfg [:wiki :base] "main")
        remote (str "https://github.com/" (get-in cfg [:github :repo]) ".git")]
    (when-not (.exists (io/file dir ".git"))
      (io/make-parents (io/file dir ".git"))
      (sh "git" "clone" remote dir))
    (git! dir "remote" "set-url" "origin" remote)
    (git! dir "fetch" "origin" base)
    (git! dir "reset" "--hard" (str "origin/" base))
    (git! dir "clean" "-fd")
    dir))

;; --------------------------------------------------------------------------
;; url extraction + the reference-card index (url -> card)
;; --------------------------------------------------------------------------

(def ^:private url-re #"https?://[^\s)>\]\"'`]+")

(defn normalize-url
  "Canonical key for a URL: fragment, trailing punctuation and trailing slash dropped."
  [u]
  (-> (str u) str/trim
      (str/replace #"#.*$" "")
      (str/replace #"[.,;:]+$" "")
      (str/replace #"/+$" "")))

(defn extract-urls
  "Distinct normalized external URLs mentioned in a markdown string."
  [md]
  (->> (re-seq url-re (str md)) (map normalize-url) (remove str/blank?) distinct))

(defn- content-files [dir]
  (->> (io/file dir "content") file-seq
       (filter #(and (.isFile %) (str/ends-with? (.getName %) ".md")))))

(defn- rel-of [dir ^java.io.File f]
  (str (.relativize (.toPath (io/file dir)) (.toPath f))))

(defn- fm-field [text field]
  (some-> (re-find (re-pattern (str "(?m)^" field ":\\s*(.+)$")) (str text))
          second str/trim))

(defn reference-index
  "Map normalized-url -> {:title :rel} for every reference card that declares a url."
  [dir]
  (into {}
        (for [f (content-files dir)
              :let [rel (rel-of dir f)]
              :when (str/starts-with? rel "content/references/")
              :let [t (slurp f) u (fm-field t "url")]
              :when (seq (str u))]
          [(normalize-url u) {:title (fm-field t "title") :rel rel}])))

(defn source-frequency
  "Map normalized-url -> set of rel paths mentioning it (across all cards)."
  [dir]
  (reduce (fn [acc f]
            (let [rel (rel-of dir f)]
              (reduce (fn [a u] (update a u (fnil conj #{}) rel))
                      acc (extract-urls (slurp f)))))
          {} (content-files dir)))

(defn quartz-slug
  "Replicate Quartz's slugifyPath to build a card's public URL: per path segment,
   whitespace->'-', &->'-and-', %->'-percent', drop ?#<>:\"|*, then lowercase."
  [s]
  (->> (str/split (str s) #"/")
       (map (fn [seg]
              (-> seg
                  (str/replace #"\s" "-")
                  (str/replace #"&" "-and-")
                  (str/replace #"%" "-percent")
                  (str/replace #"[?#<>:\"|*]" "")
                  str/lower-case)))
       (str/join "/")))

(defn- wiki-url [cfg rel]
  (str (get-in cfg [:wiki :site] "https://smith.wiki") "/"
       (quartz-slug (-> rel (str/replace #"^content/" "") (str/replace #"\.md$" "")))))

(defn- card-link [cfg dir rel]
  (let [title (or (fm-field (slurp (io/file dir rel)) "title") rel)]
    (str "[" title "](" (wiki-url cfg rel) ")")))

;; --------------------------------------------------------------------------
;; job 1: materialize — recurring bare URLs -> ingest tasks
;; --------------------------------------------------------------------------

(defn materialize-sources!
  "File ingest tasks for bare URLs cited in >= threshold notes that have neither a
   reference card nor an open ingest task. Bounded by the WIP cap and :max-per-run."
  [cfg]
  (let [dir   (fresh-main! cfg)
        thr   (get-in cfg [:reflect :ingest-threshold] 2)
        cards (reference-index dir)
        freq  (source-frequency dir)
        open  (set (map #(str (get % "title")) (gh/open-issues cfg)))
        cap   (get-in cfg [:planner :wip-cap] 10)
        budget (max 0 (- cap (count open)))
        want  (->> freq
                   (filter (fn [[u files]]
                             (and (>= (count files) thr)
                                  (not (contains? cards u))
                                  (not (contains? open (str "Ingest: " u))))))
                   (sort-by (fn [[_ files]] (- (count files))))
                   (map first))
        pick  (take (min budget (get-in cfg [:reflect :max-per-run] 3)) want)]
    (vec (for [u pick]
           (do (runner/file-ingest-task!
                 cfg u (str "Auto-queued by reflect — cited in these cards: "
                            (str/join ", " (map #(card-link cfg dir %) (sort (get freq u)))) "."))
               u)))))

;; --------------------------------------------------------------------------
;; job 2: relink — bare URLs that now have a card -> [[Card title]]
;; --------------------------------------------------------------------------

(defn relink-text
  "Rewrite mentions of `url` — a bare url or [label](url) (trailing-slash tolerant,
   prefix-safe) — into the natural wikilink [[title]]. Reference cards are FILE-named
   by their title, so Quartz resolves [[title]] out of the box (no slug guessing)."
  [text url title]
  (let [q    (java.util.regex.Pattern/quote url)
        md   (re-pattern (str "\\[[^\\]]*\\]\\(" q "/?\\)"))
        ba   (re-pattern (str q "/?(?![\\w./-])"))
        link (str "[[" title "]]")]
    (-> (str text) (str/replace md link) (str/replace ba link))))

(defn relink-sources!
  "Across every card, rewrite bare source URLs that have a reference card into
   [[Card title]] links (skipping a url inside its OWN reference card). Commits and
   pushes to main when anything changed; returns the changed rel paths."
  [cfg]
  (let [dir     (fresh-main! cfg)
        cards   (reference-index dir)
        changed (atom [])]
    (doseq [f (content-files dir)
            :let [rel (rel-of dir f) text (slurp f)]]
      (let [text' (reduce (fn [t [u card]]
                            (let [title (:title card)]
                              (if (or (str/blank? (str title)) (= (:rel card) rel))
                                t
                                (relink-text t u title))))
                          text cards)]
        (when (not= text text')
          (spit f text')
          (swap! changed conj rel))))
    (when (seq @changed)
      (git! dir "add" "-A")
      (git! dir "-c" "user.name=smith-wiki-bot" "-c" "user.email=bot@smith.wiki"
            "-c" "commit.gpgsign=false" "commit" "-m"
            (str "reflect: relink bare source URLs -> [[reference]] in "
                 (count @changed) " file(s)"))
      (git! dir "-c" (str "core.sshCommand=" (ssh-cmd cfg))
            "push" (str "git@github.com:" (get-in cfg [:github :repo]) ".git") "HEAD:main"))
    @changed))

;; --------------------------------------------------------------------------
;; job 3: materialize concepts — a dangling [[concept]] referenced by >= N cards
;;        -> an "Add concept: X" task. Agents no longer file concept tasks; they
;;        just leave [[wikilinks]] and recurrence promotes a concept to the queue.
;; --------------------------------------------------------------------------

(defn- wikilink-targets
  "All [[target]] / [[target|display]] link targets in a markdown string."
  [md]
  (->> (re-seq #"\[\[([^\]|]+)(?:\|[^\]]*)?\]\]" (str md))
       (map (comp str/trim second))
       (remove str/blank?)))

(defn- card-slugs
  "Slugs of existing cards (Quartz-slugged filename) — what a [[link]] resolves to."
  [dir]
  (set (map #(quartz-slug (-> (rel-of dir %) (str/split #"/") last (str/replace #"\.md$" "")))
            (content-files dir))))

(defn concept-frequency
  "slug -> {:title <first-seen link text> :files #{rel}} for DANGLING concept wikilinks
   (targets with no card yet), counted across every card."
  [dir]
  (let [have (card-slugs dir)]
    (reduce (fn [acc f]
              (let [rel (rel-of dir f)]
                (reduce (fn [a t]
                          (let [s (quartz-slug t)]
                            (if (or (str/blank? s) (contains? have s))
                              a
                              (-> a (update-in [s :files] (fnil conj #{}) rel)
                                    (update-in [s :title] (fn [x] (or x t)))))))
                        acc (wikilink-targets (slurp f)))))
            {} (content-files dir))))

(defn- file-concept-task! [cfg title citing]
  (let [issue (gh/create-issue cfg {:title  (str "Add concept: " title)
                                    :labels ["type:concept" "role:research"]
                                    :body   (str "## Context\n\nAuto-queued by reflect — this concept "
                                                 "is referenced by these cards:\n\n"
                                                 (str/join "\n" (map #(str "- " %) citing)) "\n")})]
    (when-let [p (projects/find-project cfg)]
      (projects/add-to-backlog! cfg (get p "id") (get issue "node_id")))
    (get issue "number")))

(defn materialize-concepts!
  "File 'Add concept: X' tasks for dangling concept wikilinks referenced by >=
   :concept-threshold cards, with no card and no open task. Bounded by WIP cap + :max-per-run."
  [cfg]
  (let [dir    (fresh-main! cfg)
        thr    (get-in cfg [:reflect :concept-threshold] 2)
        freq   (concept-frequency dir)
        open   (set (map #(str (get % "title")) (gh/open-issues cfg)))
        cap    (get-in cfg [:planner :wip-cap] 10)
        budget (max 0 (- cap (count open)))
        want   (->> freq
                    (map second)
                    (filter (fn [{:keys [files title]}]
                              (and (>= (count files) thr)
                                   (not (contains? open (str "Add concept: " title))))))
                    (sort-by (fn [{:keys [files]}] (- (count files)))))
        pick   (take (min budget (get-in cfg [:reflect :max-per-run] 3)) want)]
    (vec (for [{:keys [title files]} pick]
           (do (file-concept-task! cfg title (sort (map #(card-link cfg dir %) files)))
               title)))))
