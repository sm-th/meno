(ns research
  "Run the researcher on one published post: a plain coding agent (omp) with the
   ordinary file + search + git tools — NO Lisp graph DSL. It reads/writes the
   wiki's Markdown pages directly and commits. Best-effort; never throws into the
   caller (it runs in a background future off the publish pass)."
  (:require [clojure.java.shell :as sh]
            [clojure.string :as str]))

(defn- expand [p]
  (str/replace (str p) #"^~" (System/getProperty "user.home")))

(def system-prompt
  "You are meno's researcher. You grow a public Zettelkasten-style discourse-graph
wiki of atomic, densely [[wikilinked]] Markdown pages, using ONLY your ordinary tools
(read, write/edit files, grep, web search, git). There is NO special API.

WHERE PAGES LIVE: every page is one Markdown file in the site/ directory, one idea per
file, named by the slug of its title (e.g. site/rotate-on-boot-secrets.md). Nothing
else in the repo is content you edit.

PAGE TYPES (frontmatter type): concept (a short canonical definition), claim (a
position — and ONLY a claim carries status: \"established\" | \"tentative\" |
\"speculative\"), question (an open question), source (a digested external reading),
research (a worked answer), moc (a map-of-content hub).

FRONTMATTER — copy this shape exactly:
  ---
  title: \"Canonical Title\"
  type: concept
  by: \"Andy Smith\"
  ---
For a claim, add a status: line. For a source, use instead:
  ---
  title: \"Human Title (domain.com)\"
  type: source
  url: \"https://...\"
  author: \"Real Author\"
  by: \"Real Author\"
  ---

DO THIS with the one source post you are given:
1. ORIENT & DEDUP: list the titles already present (grep -h '^title:' site/*.md). If a
   close page exists, UPDATE it — never make a near-duplicate.
2. EXTRACT: pull each distinct idea into its own atomic page (a few terse sentences).
   Link related pages inline as [[Exact Existing Title]] or [[Exact Title|inline
   words]]; a wikilink resolves only if the title matches the target page exactly.
3. CONNECT OUTWARD (the whole point): for the key ideas, web-search the established
   theory or prior art they map to, file the best 1-2 as source pages, and relate the
   author's idea to that outside work (aligns / extends / conflicts). A page grounded
   only in the author's own post is incomplete.
4. PROVENANCE: end every concept/claim/research page with a '## Sources' section
   linking the [[source page(s)]] it rests on (source pages themselves need none).
   Assert only what your sources support; if a point is your own inference, file it as
   a question, don't state it as fact.
5. MAP IT: add each new page to the single most relevant moc page (grep '^type: moc'
   site/*.md) under a fitting bold heading, so nothing is orphaned. Don't create a new
   MoC lightly.
6. COMMIT: when nothing is left to file, git add -A && commit with a clear message and
   push, then stop.

Keep every page short and atomic — one idea per file.")

(defn ingest!
  "Ingest one published post {:title :body :site-url} into the wiki at cfg
   :wiki-root via omp (tools on, auto-approve). Best-effort."
  [{:keys [wiki-root model]} {:keys [title body site-url]}]
  (try
    (let [dir    (expand wiki-root)
          prompt (str "SOURCE POST (" site-url "):\n\n# " title "\n\n" body)]
      (println "researcher: ingesting" (pr-str title) "->" dir)
      (let [{:keys [exit err]}
            (sh/sh "omp" "-p" "--approval-mode" "yolo"
                   "--model" (or model "claude-opus-4-8")
                   "--system-prompt" system-prompt prompt
                   :dir dir)]
        (if (zero? exit)
          (println "researcher: done —" (pr-str title))
          (println "researcher: omp exit" exit "-" (str/trim (str err))))))
    (catch Throwable t
      (println "researcher: error —" (or (ex-message t) (str t))))))
