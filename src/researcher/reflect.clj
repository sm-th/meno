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
            [researcher.projects :as projects]
            [researcher.task :as task]
            [researcher.git :as git]
            [researcher.note :as note]))

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

;; --------------------------------------------------------------------------
;; job 0: ingest-new — the standard new-article puller. Newest published blog
;;        posts with no reference card (not ingested) and no open ingest task ->
;;        `role:ingest` tasks. Runs on the reflect tick; call with a limit for a
;;        one-off batch. This is the automatic ingest of new posts.
;; --------------------------------------------------------------------------

(defn published-posts
  "Newest-first blog posts as {:rel :url}, one per `publish:` commit. URL is derived
   from the path (no slurp), so enumeration stays cheap."
  [cfg]
  (let [blog (get-in cfg [:blog :root])
        burl (get-in cfg [:blog :url])]
    (->> (git/publish-commits blog)
         (mapcat (fn [c] (git/commit-post-files blog (:sha c))))
         distinct
         (keep (fn [rel] (when-let [u (note/path->url rel)]
                           {:rel rel :url (str burl u)}))))))

(defn ingest-new!
  "File `role:ingest` tasks for the newest published posts not yet ingested (no
   reference card on main) and not already queued (no open `Ingest:` issue).
   Newest-first, capped at `limit` (default :reflect :ingest-new-per-run). Returns
   the filed [{:title :url}]. The scheduled auto-ingest; pass a limit for a one-off."
  ([cfg] (ingest-new! cfg (get-in cfg [:reflect :ingest-new-per-run] 5)))
  ([cfg limit]
   (let [dir   (fresh-main! cfg)
         blog  (get-in cfg [:blog :root])
         have  (set (keys (reference-index dir)))
         openi (->> (gh/open-issues cfg) (map #(str (get % "title")))
                    (filter #(str/starts-with? % "Ingest: "))
                    (map #(str/replace % #"^Ingest:\s*" "")) set)
         new   (->> (published-posts cfg)
                    (remove #(contains? have (normalize-url (:url %))))
                    (remove #(contains? openi (:url %)))
                    (take limit))]
     (vec (for [{:keys [rel url]} new]
            (let [title (:title (note/load-note blog rel))]
              (runner/file-ingest-task! cfg url (str "Auto-ingest: new blog post — " title))
              {:title title :url url}))))))

;; --------------------------------------------------------------------------
;; job 4: curate-research — pick the single most interesting open question and
;;        file it as a research task. Gated: nothing while a research PR is in
;;        flight (one research at a time). A question is "covered" once a research
;;        card or an open research issue carries it — so the same question
;;        is never re-picked, and the LLM judge only runs when something is uncovered.
;; --------------------------------------------------------------------------

(defn- clean-question
  "Normalise an `## Open questions` bullet into a plain, self-contained question:
   drop a leading **bold label**: prefix and any inline markdown (bold, italic, code,
   wikilinks, links), collapse whitespace. Deterministic + idempotent, so the pool and
   the filed title always agree — coverage dedup stays stable."
  [s]
  (-> (str s)
      str/trim
      (str/replace #"^\*\*[^*]+\*\*\s*[:：—–-]+\s*" "")
      (str/replace #"`([^`]*)`" "$1")
      (str/replace #"\*\*([^*]+)\*\*" "$1")
      (str/replace #"(?<!\*)\*([^*]+)\*(?!\*)" "$1")
      (str/replace #"\[\[([^\]|]+)(?:\|[^\]]*)?\]\]" "$1")
      (str/replace #"\[([^\]]+)\]\([^)]*\)" "$1")
      (str/replace #"\s+" " ")
      str/trim))

(defn question-sources
  "Map cleaned open-question -> set of rel paths (cards) that raise it: every bullet
   under a `## Open questions` heading, normalised; blanks dropped."
  [dir]
  (reduce
   (fn [acc f]
     (let [rel (rel-of dir f)
           sec (second (re-find #"(?ims)^##\s+open\s+questions\s*$(.*?)(?=^##\s|\z)" (slurp f)))]
       (if-not sec
         acc
         (reduce (fn [a line]
                   (let [q (some-> (second (re-find #"^\s*[-*]\s+(.+)$" line)) clean-question)]
                     (if (str/blank? (str q)) a (update a q (fnil conj #{}) rel))))
                 acc (str/split-lines sec)))))
   {} (content-files dir)))

(defn open-questions
  "Distinct cleaned open questions across the wiki (see question-sources)."
  [dir]
  (vec (keys (question-sources dir))))

(defn- research-card-titles
  "Titles of research report cards already written to main (content/research/)."
  [dir]
  (->> (content-files dir)
       (filter #(str/starts-with? (rel-of dir %) "content/research/"))
       (keep #(fm-field (slurp %) "title"))
       (map str/trim)
       set))

(defn- research-issue-titles
  "Questions of OPEN research issues (task filed, card not yet on main) — the `Research: `
   task prefix stripped so they compare against the raw pool questions."
  [cfg]
  (->> (gh/open-issues cfg)
       (filter (fn [i] (some #(= "type:research" (get % "name")) (get i "labels"))))
       (map #(-> (str (get % "title")) (str/replace #"(?i)^\s*research:\s*" "") str/trim))
       set))

(defn- open-research-pr?
  "True if an open PR belongs to a research task (its issue is labelled
   type:research). The one-research-at-a-time gate — keyed on PR state, not the
   issue (issues go Done on open, PRs stay open until merged)."
  [cfg]
  (let [prefix (get-in cfg [:worker :branch-prefix] "researcher/issue-")
        pat    (re-pattern (str (java.util.regex.Pattern/quote prefix) "(\\d+)"))]
    (boolean
     (some (fn [pr]
             (when-let [n (some-> (get-in pr ["head" "ref"]) (->> (re-find pat)) second Integer/parseInt)]
               (try (boolean (some #(= "type:research" (get % "name"))
                                   (get (gh/get-issue cfg n) "labels")))
                    (catch Throwable _ false))))
           (gh/open-prs cfg)))))

(defn- pool-prompt [qmap pool]
  (str "OPEN QUESTIONS surfaced across the wiki — pick ONE to research next:\n\n"
       (str/join "\n"
                 (map-indexed (fn [i q]
                                (str (inc i) ". " q
                                     "  [raised in " (count (get qmap q)) " card(s)]"))
                              pool))))

(defn- file-research-task! [cfg question proposal links]
  (let [issue (gh/create-issue cfg {:title  (str "Research: " question)
                                    :labels ["type:research" "role:report"]
                                    :body   (str "## Question\n\n" question "\n\n"
                                                 (str/trim (str proposal)) "\n\n"
                                                 "---\n\nRaised in these cards:\n\n"
                                                 (str/join "\n" (map #(str "- " %) links)) "\n\n"
                                                 "_Auto-curated by reflect._\n")})]
    (when-let [p (projects/find-project cfg)]
      (projects/add-to-backlog! cfg (get p "id") (get issue "node_id")))
    (get issue "number")))

(defn curate-research!
  "Pick the single most interesting UNCOVERED open question and file it as a fully-planned
   research task. No-op while a research PR is in flight, or when every question is already
   covered by a research card / open research issue (the planner — an omp run that may search
   the web — runs ONLY when uncovered questions exist, so an idle tick spends nothing).
   dry? runs the planner but prints the proposal instead of filing."
  [cfg & [{:keys [dry?]}]]
  (if (open-research-pr? cfg)
    {:skipped :research-pr-open}
    (let [dir     (fresh-main! cfg)
          qmap    (question-sources dir)
          covered (into (research-card-titles dir) (research-issue-titles cfg))
          pool    (->> (keys qmap) (remove covered) vec)]
      (if (empty? pool)
        {:skipped :none-uncovered}
        (do
          (reset! task/plan nil)
          (runner/run-issue cfg {:role :curate :title "Research planning"
                                 :body (pool-prompt qmap pool)})
          (let [{:keys [n proposal]} @task/plan
                n (when (integer? n) (int n))]
            (if-not (and n (<= 1 n (count pool)))
              {:skipped :no-plan :pool (count pool)}
              (let [q     (nth pool (dec n))
                    links (map #(card-link cfg dir %) (sort (get qmap q)))]
                (if dry?
                  {:dry :research :chosen q :proposal proposal :pool (count pool)}
                  {:filed (file-research-task! cfg q proposal links)
                   :title q :pool (count pool)})))))))))

;; --------------------------------------------------------------------------
;; job 5: rebuild-changelog — the accepted-PR audit trail. Regenerate
;;        content/changelog.md from the merged-PR history (PR -> pages it
;;        created/edited/deleted, with links + merge SHA), push to main when it
;;        changed. Cheap (no LLM); the history a human uses to review auto-merge
;;        and to roll back (revert the PR on GitHub).
;; --------------------------------------------------------------------------

(defn- clean-title [s] (str/replace (str s) #"[\[\]]" ""))

(defn- changelog-file-line [cfg dir {:keys [path status]}]
  (let [tag (case status "added" "🟢 new" "removed" "🔴 deleted" "renamed" "🔵 renamed" "🟡 edited")
        f   (io/file dir path)
        ttl (or (when (.exists f) (fm-field (slurp f) "title"))
                (-> path (str/replace #"^content/" "") (str/replace #"\.md$" "")))]
    (if (= status "removed")
      (str "- " tag " `" path "`")
      (str "- " tag " [" (clean-title ttl) "](" (wiki-url cfg path) ")"))))

(defn- changelog-md [cfg dir prs]
  (str "---\ntitle: Changelog\n---\n\n"
       "_Auto-generated from GitHub — every accepted PR and the pages it created, edited or "
       "deleted, newest first. Roll back a change by reverting its PR on GitHub._\n\n"
       (str/join "\n\n"
         (for [{:keys [number title url sha merged-at]} prs]
           (str "### [#" number " " (clean-title title) "](" url ")\n"
                "merged " (subs (str merged-at) 0 10)
                (when (seq (str sha)) (str " · `" (subs (str sha) 0 (min 7 (count (str sha)))) "`")) "\n\n"
                (let [fs (->> (try (gh/pr-files cfg number) (catch Throwable _ []))
                              (filter #(str/ends-with? (str (:path %)) ".md")))]
                  (if (seq fs)
                    (str/join "\n" (map #(changelog-file-line cfg dir %) fs))
                    "_no page changes_")))))
       "\n"))

(defn rebuild-changelog!
  "Regenerate content/changelog.md from the merged-PR history; commit+push to main
   only when it changed. Returns {:prs n :changed bool}."
  [cfg]
  (let [dir (fresh-main! cfg)
        f   (io/file dir "content/changelog.md")
        old (when (.exists f) (slurp f))
        new (changelog-md cfg dir (gh/merged-prs cfg))]
    (when (not= old new)
      (spit f new)
      (git! dir "add" "-A")
      (git! dir "-c" "user.name=smith-wiki-bot" "-c" "user.email=bot@smith.wiki"
            "-c" "commit.gpgsign=false" "commit" "-m" "reflect: rebuild changelog")
      (git! dir "-c" (str "core.sshCommand=" (ssh-cmd cfg))
            "push" (str "git@github.com:" (get-in cfg [:github :repo]) ".git") "HEAD:main"))
    {:prs (count (gh/merged-prs cfg)) :changed (not= old new)}))
