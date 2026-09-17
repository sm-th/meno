(ns social.threads
  "Deterministically enqueue text posts in the per-platform Threads repository.
   The repository's CI owns Meta credentials and external publication; this
   producer only commits immutable post files through a network-bound GitHub token."
  (:require [clojure.string :as str]
            [research.image :as image]
            [zeno.sandbox :as sandbox])
  (:import (java.nio.charset StandardCharsets)
           (java.util Base64)))

(defn- character-count [value]
  (let [text (str value)]
    (.codePointCount text 0 (.length text))))

(defn- last-whitespace-offset [text end]
  (loop [offset 0
         boundary nil]
    (if (< offset end)
      (let [code-point (.codePointAt text offset)
            next-offset (+ offset (Character/charCount code-point))]
        (recur next-offset
               (if (Character/isWhitespace (int code-point)) offset boundary)))
      boundary)))

(defn segments
  "Split text into non-empty segments of at most limit characters, preferring
   whitespace boundaries. Threads allows 500 characters per post."
  ([text] (segments text 500))
  ([text limit]
   (when-not (pos-int? limit)
     (throw (ex-info "Threads segment limit must be a positive integer"
                     {:limit limit})))
   (loop [remaining (str/trim (str text))
          result    []]
     (cond
       (str/blank? remaining)
       result

       (<= (character-count remaining) limit)
       (conj result remaining)

       :else
       (let [end      (.offsetByCodePoints remaining 0 limit)
             boundary (last-whitespace-offset remaining end)
             cut      (if (and boundary (pos? boundary)) boundary end)
             segment  (subs remaining 0 cut)
             rest     (subs remaining cut)]
         (recur (if boundary (str/triml rest) rest)
                (conj result segment)))))))

(defn post-file
  "Render one repository post. A parent is either a local reply-to slug or an
   external reply-to-id plus optional provenance URL. Long text becomes a native
   Threads reply chain. When mention is present, append it as its own final
   segment so a dependent Agent post can reply to the mention media."
  ([actor reply-to text]
   (post-file actor reply-to nil nil nil text))
  ([actor reply-to reply-to-id reply-to-url mention text]
   (when (and reply-to reply-to-id)
     (throw (ex-info "Threads post cannot have both local and external parents" {})))
   (when (and reply-to-url (nil? reply-to-id))
     (throw (ex-info "Threads external parent URL requires its media ID" {})))
   (let [body (cond-> (segments text)
                (not (str/blank? mention))
                (conj (str "@" (str/replace mention #"^@" ""))))]
     (str "---\nactor: " (name actor) "\n"
          (when reply-to (str "reply_to: " reply-to "\n"))
          (when reply-to-id (str "reply_to_id: " reply-to-id "\n"))
          (when reply-to-url (str "reply_to_url: " reply-to-url "\n"))
          "---\n"
          (str/join "\n---\n" body)
          "\n"))))

(defn- b64 [s]
  (.encodeToString (Base64/getEncoder) (.getBytes (str s) StandardCharsets/UTF_8)))

(defn- commit-sha [out]
  (some->> (str/split-lines (or out ""))
           (filter #(str/starts-with? % "THREADS_COMMIT "))
           last
           (#(subs % (count "THREADS_COMMIT ")))
           str/trim
           not-empty))

(defn enqueue!
  "Commit a stable post file and push it to the Threads repository. Existing
   identical intent is a successful no-op; differing content for the same slug
   is rejected. cfg requires :repo/:base plus sandbox secret delivery for
   BLOG_GH_TOKEN. Returns {:slug :commit :url}, or throws."
  [{:keys [repo base image net-bound secret-env egress git-name git-email]}
   {:keys [slug actor reply-to reply-to-id reply-to-url mention text]}]
  (image/ensure!)
  (let [content (post-file actor reply-to reply-to-id reply-to-url mention text)
        env     {"THREADS_REPO" repo
                 "THREADS_BASE" (or base "main")
                 "THREADS_SLUG" slug
                 "THREADS_POST_B64" (b64 content)
                 "GIT_AUTHOR_NAME" (or git-name "Agent Smith")
                 "GIT_AUTHOR_EMAIL" (or git-email "agent@smith.wiki")
                 "GIT_COMMITTER_NAME" (or git-name "Agent Smith")
                 "GIT_COMMITTER_EMAIL" (or git-email "agent@smith.wiki")}
        argv    ["/bin/sh" "-c"
                 (str "set -eu; "
                      "git config --global credential.helper "
                      "'!f() { echo username=x-access-token; echo \"password=$BLOG_GH_TOKEN\"; }; f'; "
                      "git clone --depth 1 --branch \"$THREADS_BASE\" \"$THREADS_REPO\" /work/repo; "
                      "cd /work/repo; mkdir -p posts; "
                      "printf '%s' \"$THREADS_POST_B64\" | base64 -d > /tmp/proposed.md; "
                      "path=\"posts/$THREADS_SLUG.md\"; "
                      "if [ -f \"$path\" ]; then "
                      "cmp -s \"$path\" /tmp/proposed.md || { echo 'stable slug has different content' >&2; exit 42; }; "
                      "else mv /tmp/proposed.md \"$path\"; fi; "
                      "git config user.name \"$GIT_AUTHOR_NAME\"; "
                      "git config user.email \"$GIT_AUTHOR_EMAIL\"; "
                      "git add \"$path\"; "
                      "if ! git diff --cached --quiet; then "
                      "git commit -m \"queue Threads post $THREADS_SLUG\" >/dev/null; git push origin HEAD:\"$THREADS_BASE\" >/dev/null; fi; "
                      "printf 'THREADS_COMMIT %s\\n' \"$(git rev-parse HEAD)\"")]
        run     #(sandbox/run {:image      (or image "researcher-agent:base")
                              :env        env
                              :net-bound  net-bound
                              :secret-env secret-env
                              :egress     egress
                              :timeout    "3m"
                              :memory     768
                              :argv       argv})]
    (loop [attempt 1]
      (let [{:keys [exit out err]} (run)]
        (cond
          (zero? exit)
          (if-let [sha (commit-sha out)]
            {:slug slug
             :commit sha
             :url (str (str/replace repo #"\.git$" "") "/commit/" sha)}
            (throw (ex-info "Threads enqueue produced no commit marker" {:slug slug})))

          (< attempt 3)
          (recur (inc attempt))

          :else
          (throw (ex-info "Threads enqueue failed"
                          {:slug slug :exit exit :err (str/trim (str err))})))))))
