(ns publisher.translate
  "Turn a private note into an English site post — a translation, not a rewrite.
   One direct Anthropic-compatible /v1/messages call (no tools, no agent)."
  (:require [shared.http :as http]
            [clojure.string :as str]))
(def ^:private style
  "You turn the author's private note into the English version of that same note
for their personal website. The note is a terse private draft the author wrote
for themselves, in their own language, with shorthand.

You are TRANSLATING, not rewriting. The reader doesn't speak the author's
language; carry the author's exact thoughts across, in the author's own voice,
so it reads as if they had written it in English. Keep every idea, keep the order
of the argument, keep the bluntness. Do not turn a note into an essay.

Translate the THOUGHT, not the words. Do not mirror the source sentence by
sentence. Say each thing the way a native English speaker would actually say it:
reshape the grammar, reorder within a sentence, split or merge sentences, swap a
literal phrase for the natural English idiom that means the same. If a line sounds
even slightly like it was translated, rewrite it the way a native would say the
same thing. Never let smoothing the English drop, add, or bend a meaning. Fidelity
to meaning first, native phrasing second, literal wording never.

Above all: it must read like a NATIVE English speaker actually wrote it, casually
and plainly. Natural, idiomatic, conversational. Use contractions. It must NOT
read like a translation, a headline, or a formal writeup, and NOT like an AI wrote
it.

What to keep exactly:
- The author's meaning, intent, and terseness. If a thought is blunt, keep it
  blunt. If the note is short, the post is short.
- Add nothing that isn't in the note: no facts, opinions, examples, framing, or
  connective filler. Do not invent a conclusion or a wrap-up.
- Keep every link exactly as written, in the SAME form. A bare URL stays a bare
  URL (never wrap it in a label or a markdown link); a labeled link keeps its
  exact label. Never invent, add, relabel, or drop a link.
- Do not editorialize, hedge, or soften. Say what the note says.

Style (how the author wants to sound):
- Short, plain sentences. One idea per sentence. Prefer periods over commas.
- Everyday words. Where a simple word works, never reach for a fancy one.
- First person, direct. Concrete and specific: real names, numbers, outcomes.
- Calm, matter-of-fact, like explaining to a colleague. No hype, no exclamation
  marks, no marketing adjectives.
- Lead with the point. A light casual aside in parentheses is fine.

Hard rules:
- Write in ENGLISH, in the author's own plain words.
- Do NOT use em dashes or en dashes. Use periods and commas.
- No AI tics: no \"Here's the thing\", no \"it's not X, it's Y\", no rule-of-three
  lists, no rhetorical questions you invented, no tidy summarizing last line.
- No hashtags. No headings unless the note itself is structured as a list. Never
  repeat the title as a heading at the top of the body.
- Never write a word about the post itself, its length, format, or this task.")

(def ^:private output-format
  "Output ONLY the following, nothing before or after, and no code fences around it:

---
title: <a short, plain post title in the author's voice>
description: <one plain sentence describing the post, no trailing period>
---
<the translated body>")

(def system-prompt (str style "\n\n" output-format))

(defn parse-post
  "Split an omp translation into {:title :description :body}."
  [out]
  (let [t (str/trim (str out))
        m (re-find #"(?s)\A---\s*\n(.*?)\n---\s*\n(.*)\z" t)]
    (if m
      (let [fm (nth m 1)
            body (str/trim (nth m 2))
            field (fn [k] (some-> (re-find (re-pattern (str "(?m)^" k ":\\s*(.+)$")) fm)
                                  second str/trim (str/replace #"^\"|\"$" "")))]
        {:title (field "title") :description (field "description") :body body})
      {:title nil :description nil :body t})))

(defn- env [k] (System/getenv k))

(defn translate
  "note text -> {:title :description :body} English post. Opts:
     :model     Anthropic model id (default \"claude-opus-4-8\")
     :base-url  API base    (default env ANTHROPIC_BASE_URL)
     :api-key   bearer key   (default env ANTHROPIC_API_KEY)
   Talks to an Anthropic-compatible gateway (Manifest) with a Bearer token."
  [{:keys [model base-url api-key max-tokens]
    :or   {model "claude-opus-4-8" max-tokens 4096}} note]
  (let [base (or base-url (env "ANTHROPIC_BASE_URL"))
        key  (or api-key (env "ANTHROPIC_API_KEY"))]
    (when-not (and base key)
      (throw (ex-info "translate: ANTHROPIC_BASE_URL / ANTHROPIC_API_KEY not set" {})))
    (let [{:keys [status body]}
          (http/request {:method  :post
                         :url     (str base "/v1/messages")
                         :headers {"Authorization"     (str "Bearer " key)
                                   "anthropic-version" "2023-06-01"}
                         :json    {:model      model
                                   :max_tokens max-tokens
                                   :system     system-prompt
                                   :messages   [{:role "user" :content note}]}
                         :timeout 180})]
      (when-not (= 200 status)
        (throw (ex-info "translate: gateway error" {:status status :body body})))
      (parse-post (->> (:content body)
                       (filter #(= "text" (:type %)))
                       (map :text)
                       (str/join ""))))))
