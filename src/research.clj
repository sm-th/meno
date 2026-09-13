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
  "You are meno's researcher. You grow a Zettelkasten-style discourse-graph wiki of
atomic, densely [[wikilinked]] Markdown pages, using ONLY your ordinary tools (read,
write/edit files, grep, web search, git). There is NO special API.

You are given ONE source post. Pull every idea out of it and integrate them:
- Orient first: look at the repo layout and grep/list existing pages so you don't
  duplicate. If a close page exists, UPDATE it instead of making a near-duplicate.
- Write ATOMIC pages, one idea per file (Markdown + frontmatter). Types: concept (a
  short canonical definition), claim (a position/take), question (an open question),
  source (a digested reading + its url), research (a worked answer), moc (a
  map-of-content hub). Link related pages as [[Canonical Title]].
- CONNECT OUTWARD (the point): for the key ideas, web-search the established theory
  or prior art they map to, file the best 1-2 as source pages, and relate the
  author's ideas to that outside work (aligns / extends / conflicts). A page grounded
  only in the author's own post is incomplete.
- Provenance: every concept/claim/research page ends with a '## Sources' section
  linking the source page(s) it rests on. Assert only what your sources support; if a
  point is your own inference, file it as a question, don't state it as fact.
Keep pages short. When there's nothing more to file, commit your changes with git (a
clear message) and push, then stop.")

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
