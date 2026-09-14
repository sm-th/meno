(ns research
  "Ingest one published post into the wiki via a SANDBOXED omp worker: a coding
   agent (omp, with its ordinary file/search/git tools) runs inside an ephemeral
   microVM (zeno.sandbox) against a fresh wiki clone, with LLM + GitHub creds
   network-bound and broad egress to fetch sources. NEVER on the host — the post
   and any page it fetches are untrusted input. Best-effort; never throws."
  (:require [clojure.java.shell :as sh]
            [clojure.string :as str]
            [zeno.sandbox :as sandbox]
            [shared.http :as http]))

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
6. COMMIT & PUSH: when nothing is left to file, git add -A && commit with a clear
   message, then run the exact `git push` command your task gives you. Then stop.

Keep every page short and atomic — one idea per file.")

(defn- slug [s]
  (-> (str s) str/lower-case (str/replace #"[^a-z0-9]+" "-") (str/replace #"^-+|-+$" "")))

(defn ingest!
  "Run one published post {:title :body :site-url} — EVERYTHING happens inside one
   ephemeral microVM; the host only does `msb run`. The VM clones the wiki, the
   agent (omp, full native tools, broad web egress) researches, edits, commits and
   pushes to a review branch. Every secret is network-bound: msb injects the real
   value only toward its host — even inside git's base64 Basic-auth — so the guest
   only ever holds placeholders and a prompt-injection has nothing to exfiltrate.
   Secrets: ANTHROPIC_API_KEY@manifest (LLM) and GH_TOKEN@github (clone + push).
   Retries the whole run on a transient upstream stream drop. cfg:
   {:wiki-repo :wiki-base :image :manifest-url :model :git-name :git-email
    :net-bound :secret-env :egress :retries}."
  [{:keys [wiki-repo wiki-base image manifest-url model git-name git-email
           net-bound secret-env egress retries]}
   {:keys [title body site-url]}]
  (let [max-att (inc (or retries 2))
        branch  (str "researcher/" (slug title))
        env     {"OMP_TASK"     (str "SOURCE POST (" site-url "):\n\n# " title "\n\n" body
                                     "\n\n---\nWhen finished: git add -A, commit with a clear"
                                     " message, then `git push origin HEAD:refs/heads/" branch "`.")
                 "OMP_SYS"      system-prompt
                 "MANIFEST_URL" (or manifest-url "https://llm.gradienthike.com/v1")
                 "ANTHROPIC_BASE_URL" (str/replace (or manifest-url "https://llm.gradienthike.com/v1") #"/v1/?$" "")
                 "MODEL_ID"     (or model "opencode-go/deepseek-v4-flash")
                 "WIKI_REPO"    wiki-repo
                 "WIKI_BASE"    (or wiki-base "main")
                 "GIT_SSL_CAINFO"      "/.msb/tls/ca.pem"
                 "GIT_AUTHOR_NAME"     (or git-name "Agent Smith")
                 "GIT_AUTHOR_EMAIL"    (or git-email "agent@smith.wiki")
                 "GIT_COMMITTER_NAME"  (or git-name "Agent Smith")
                 "GIT_COMMITTER_EMAIL" (or git-email "agent@smith.wiki")}
        argv    ["/bin/sh" "-c"
                 (str "set -e; mkdir -p /root/.omp/agent; "
                      "printf 'providers:\\n  manifest:\\n    baseUrl: %s\\n"
                      "    api: openai-completions\\n    apiKey: \"%s\"\\n"
                      "    models:\\n      - id: %s\\n' "
                      "\"$MANIFEST_URL\" \"$ANTHROPIC_API_KEY\" \"$MODEL_ID\" "
                      "> /root/.omp/agent/models.yml; "
                      "git config --global credential.helper "
                      "'!f() { echo username=x-access-token; echo \"password=$GH_TOKEN\"; }; f'; "
                      "git clone --depth 1 --branch \"$WIKI_BASE\" \"$WIKI_REPO\" /repos/wiki; "
                      "cd /repos/wiki; "
                      "git config user.name \"$GIT_AUTHOR_NAME\"; "
                      "git config user.email \"$GIT_AUTHOR_EMAIL\"; "
                      "printf %s \"$OMP_TASK\" | omp -p --approval-mode yolo "
                      "--model \"manifest/$MODEL_ID\" --system-prompt \"$OMP_SYS\"")]
        run-once
        (fn []
          (sandbox/run {:image      (or image "zeno-agent:base")
                        :env        env
                        :net-bound  net-bound
                        :secret-env secret-env
                        :egress     (or egress {:net "public"})
                        :timeout    "20m"
                        :argv       argv}))]
    (try
      (loop [attempt 1]
        (println "researcher: ingesting" (pr-str title) "in sandbox — attempt"
                 attempt "/" max-att)
        (let [{:keys [exit err]} (run-once)]
          (cond
            (zero? exit)        (do (println "researcher: done —" (pr-str title) "→" branch)
                                    {:exit 0 :branch branch :title title})
            (< attempt max-att) (do (println "researcher: attempt" attempt "failed (exit"
                                             exit "):" (str/trim (str err)) "— retrying")
                                    (recur (inc attempt)))
            :else               (do (println "researcher: gave up after" max-att
                                             "attempts —" (str/trim (str err)))
                                    {:exit exit}))))
      (catch Throwable t
        (println "researcher: error —" (or (ex-message t) (str t)))))))

(defn- gh
  "One GitHub REST call with the bot token. Returns {:status :body}."
  [token method url json]
  (http/request {:method  method
                 :url     url
                 :headers {"Authorization"        (str "Bearer " token)
                           "Accept"               "application/vnd.github+json"
                           "X-GitHub-Api-Version" "2022-11-28"
                           "User-Agent"           "meno-researcher"}
                 :json    json
                 :timeout 60}))

(defn accept!
  "Auto-accept a finished ingest: open a PR for the pushed branch and squash-merge
   it (deleting the branch), so review is automatic and only the merged-PR list
   remains to glance at. Host-side deterministic policy using GH_TOKEN from the env
   (the trusted orchestrator merges; the agent only pushed the branch). On success
   returns {:number :url :pages}; on any failure returns nil. Never throws."
  [{:keys [wiki-repo wiki-base wiki-site]} {:keys [branch title]}]
  (try
    (let [token (System/getenv "GH_TOKEN")
          repo  (-> (str wiki-repo)
                    (str/replace #"^https?://github\.com/" "")
                    (str/replace #"\.git$" ""))
          api   (str "https://api.github.com/repos/" repo)
          pr    (gh token :post (str api "/pulls")
                    {:title (str "researcher: " title)
                     :head  branch
                     :base  (or wiki-base "main")
                     :body  (str "Automated research ingest for **" title "**. Auto-merged.")})
          num   (get-in pr [:body :number])
          url   (get-in pr [:body :html_url])]
      (if (nil? num)
        (do (println "researcher: accept! — PR create failed (" (:status pr) "):"
                     (pr-str (get-in pr [:body :message])))
            nil)
        (let [pages (->> (:body (gh token :get (str api "/pulls/" num "/files") nil))
                         (keep :filename)
                         (filter #(str/ends-with? % ".md"))
                         vec)
              mg    (gh token :put (str api "/pulls/" num "/merge")
                        {:merge_method "squash"
                         :commit_title (str "researcher: " title " (#" num ")")})]
          (if (get-in mg [:body :merged])
            (do (gh token :delete (str api "/git/refs/heads/" branch) nil)
                (println "researcher: accepted — PR #" num "squash-merged, branch deleted")
                {:number num :url url :pages pages :site (or wiki-site "https://smith.wiki")})
            (do (println "researcher: accept! — merge failed for PR #" num "(" (:status mg) "):"
                         (pr-str (get-in mg [:body :message])))
                nil)))))
    (catch Throwable t
      (println "researcher: accept! error —" (or (ex-message t) (str t)))
      nil)))

(defn receipt
  "A short Zulip thread receipt for a merged research PR: the PR link plus links to
   the wiki pages it added or updated."
  [{:keys [number url pages site]}]
  (let [base (or site "https://smith.wiki")]
    (str "🔬 Researched — merged [PR #" number "](" url ")"
         (when (seq pages)
           (str "\n" (count pages) " page(s): "
                (->> pages
                     (map #(let [slug (-> (str %) (str/replace #"^site/" "") (str/replace #"\.md$" ""))]
                             (str "[" slug "](" base "/" slug "/)")))
                     (str/join ", ")))))))
