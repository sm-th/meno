(ns research
  "Ingest one published post into the wiki via a SANDBOXED omp worker: a coding
   agent (omp, with its ordinary file/search/git tools) runs inside an ephemeral
   microVM (zeno.sandbox) against a fresh wiki clone, with LLM + GitHub creds
   network-bound and broad egress to fetch sources. NEVER on the host — the post
   and any page it fetches are untrusted input. Best-effort; never throws."
  (:require [clojure.java.shell :as sh]
            [clojure.string :as str]
            [zeno.sandbox :as sandbox]))

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
  "Run one published post {:title :body :site-url} through a sandboxed omp worker.
   The worker's omp routes ALL LLM traffic through the Manifest gateway (a custom
   openai-completions provider written at guest start from the network-bound
   ANTHROPIC_API_KEY placeholder), never a provider directly. Retries the whole
   sandboxed run on non-zero exit (a transient upstream stream drop kills the omp
   session) up to :retries times (default 2 -> 3 attempts); the researcher is
   idempotent (re-orients from the wiki's current state each run). cfg:
   {:wiki-repo :wiki-base :image :manifest-url :model :git-name :git-email
    :net-bound [{:env :host}] :secret-env {ENV VAL} :egress :retries}."
  [{:keys [wiki-repo wiki-base image manifest-url model git-name git-email
           net-bound secret-env egress retries]}
   {:keys [title body site-url]}]
  (let [max-att (inc (or retries 2))
        env     {"OMP_TASK"     (str "SOURCE POST (" site-url "):\n\n# " title "\n\n" body)
                 "OMP_SYS"      system-prompt
                 "MANIFEST_URL" (or manifest-url "https://llm.gradienthike.com/v1")
                 "MODEL_ID"     (or model "opencode-go/deepseek-v4-flash")
                 "GIT_AUTHOR_NAME"     (or git-name "Agent Smith")
                 "GIT_AUTHOR_EMAIL"    (or git-email "agent@smith.wiki")
                 "GIT_COMMITTER_NAME"  (or git-name "Agent Smith")
                 "GIT_COMMITTER_EMAIL" (or git-email "agent@smith.wiki")}
        argv    ["/bin/sh" "-c"
                 (str "mkdir -p /root/.omp/agent && "
                      "printf 'providers:\\n  manifest:\\n    baseUrl: %s\\n"
                      "    api: openai-completions\\n    apiKey: \"%s\"\\n"
                      "    models:\\n      - id: %s\\n' "
                      "\"$MANIFEST_URL\" \"$ANTHROPIC_API_KEY\" \"$MODEL_ID\" "
                      "> /root/.omp/agent/models.yml && "
                      "cd /repos/wiki && printf %s \"$OMP_TASK\" | "
                      "omp -p --approval-mode yolo "
                      "--model \"manifest/$MODEL_ID\" --system-prompt \"$OMP_SYS\"")]
        run-once
        (fn []
          (let [work  (str (or (System/getenv "TMPDIR") "/tmp/") "meno-wiki-"
                           (System/currentTimeMillis))
                clone (sh/sh "git" "clone" "--depth" "1" "--branch" (or wiki-base "main")
                             wiki-repo work)]
            (if-not (zero? (:exit clone))
              {:exit 1 :err (str "wiki clone failed: " (str/trim (str (:err clone))))}
              (try
                (sandbox/run {:image      (or image "zeno-agent:base")
                              :workdir    "/repos/wiki"
                              :mounts     [{:src work :dst "/repos/wiki"}]
                              :env        env
                              :net-bound  net-bound
                              :secret-env secret-env
                              :egress     (or egress {:net "public"})
                              :timeout    "20m"
                              :argv       argv})
                (finally (sh/sh "rm" "-rf" work))))))]
    (try
      (loop [attempt 1]
        (println "researcher: ingesting" (pr-str title) "in sandbox — attempt"
                 attempt "/" max-att)
        (let [{:keys [exit err] :as r} (run-once)]
          (cond
            (zero? exit)        (do (println "researcher: done —" (pr-str title)) r)
            (< attempt max-att) (do (println "researcher: attempt" attempt "failed (exit"
                                             exit "):" (str/trim (str err)) "— retrying")
                                    (recur (inc attempt)))
            :else               (do (println "researcher: gave up after" max-att
                                             "attempts —" (str/trim (str err))) r))))
      (catch Throwable t
        (println "researcher: error —" (or (ex-message t) (str t)))))))
