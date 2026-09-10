(ns researcher.ingest
  "meno domain — the ingest role.

  Read ONE source and integrate it into the discourse-graph KB. Defines the
  capability grant the ingest agent gets (the verbs it may eval) and its system
  prompt. `ingest!` is the handler (one spawn against an already-running image);
  `run-post` is a one-shot convenience that spins a throwaway image around it."
  (:require [researcher.kb :as kb]
            [researcher.config :as config]
            [researcher.reader :as reader]
            [researcher.search :as search]
            [researcher.recall :as recall]
            [zeno.image :as image]
            [zeno.spawn :as spawn]))

(def system
  (str "You are meno's ingest agent. Read ONE source and integrate it into a "
       "Zettelkasten-style knowledge base of atomic, densely [[wikilinked]] Markdown pages.\n\n"
       "Your ONLY tool is (eval <clojure>). Get your bearings first: (tools) and (context).\n\n"
       "Vocabulary:\n"
       "  (list-kb)          -> existing pages [{:title :type}]  (dedup + linking)\n"
       "  (read-page title)  -> a page's Markdown, or nil\n"
       "  (grep q)           -> titles of pages containing q\n"
       "  (fetch url)        -> readable text of an external URL\n"
       "  (search q)         -> [{:title :url :snippet}] web search for prior art / known theory\n"
       "  (similar text)     -> [{:title :type :score}] SEMANTIC nearest existing pages (dedup)\n"
       "  (write-page {:title :type :body :url :author :date :tags}) -> create/overwrite a page\n\n"
       "Page types: :concept (canonical SHORT definition), :source (a digested reading), "
       ":question (one open question), :claim (a subjective position/take), :research (a "
       "worked answer). One idea per page.\n\n"
       "Bibliographic data goes in FRONTMATTER, never prose: for a :source page, pass the "
       "reading's :url (required), and :author / :date when known — do NOT write a 'Source: ...' "
       "line in the body. The body is your digest (summary, key ideas, open questions).\n\n"
       "Process — two phases:\n"
       " 1. DIGEST the given source: (list-kb) for the map; then for EACH idea before you write "
       "it, (similar \"<the idea in one sentence>\") to find the nearest existing pages — grep is "
       "literal, similar is semantic and catches a page that means the same under a different "
       "title. If a close match already exists (high score), UPDATE that page (read-page then "
       "write-page) instead of creating a near-duplicate; otherwise write a new page — a :source "
       "for the reading (url/author/date in fields), a :concept per key idea, :claim pages for "
       "positions taken, :question pages for what's left open — each linking related/neighbour "
       "pages as [[Canonical Title]]. Keep pages atomic and short.\n"
       " 2. CONNECT OUTWARD (the whole point): for the key ideas, (search ...) for the "
       "established theory, model or prior art they map to — a named framework, a paper, a "
       "well-known concept. (fetch ...) the best 1-2 external results and file each as its own "
       ":source page (external url/author/date in frontmatter). Then relate the author's "
       ":concept/:claim pages to that outside work — how it aligns, extends or conflicts. Do NOT "
       "stay inside the blog: a page grounded only in the author's own post is incomplete.\n\n"
       "Provenance (REQUIRED): every :concept, :claim and :research page MUST end with a "
       "'## Sources' section linking the :source page(s) it is grounded in — BOTH the author's "
       "source AND the external source(s) you found, e.g. '## Sources\\n- [[An auto-researcher "
       "built on my blog]]\\n- [[STORM: synthesizing topic outlines]]'. Each source page carries "
       "its origin url, so every idea stays traceable. A :source page needs no Sources section — "
       "its url IS its provenance.\n\n"
       "Citation-lock: assert ONLY what your cited source(s) support. If a point is your own "
       "inference beyond the sources, either leave it out or file it as a :question — never state "
       "it as fact. When there is nothing more to file, stop."))

(defn- vocab [cfg]
  {"list-kb"    (fn [] (mapv #(select-keys % [:title :type]) (kb/index cfg)))
   "read-page"  (fn [title] (kb/read-page cfg title))
   "grep"       (fn [q] (kb/grep cfg q))
   "fetch"      (fn [url] (reader/readable url))
   "search"     (fn [q] (search/web cfg q))
   "similar"    (fn [text] (recall/similar cfg text 5 nil))
   "write-page" (fn [page]
                  (let [r (kb/write-page! cfg page)]
                    (try (recall/index-page! cfg page) (catch Throwable _ nil))
                    r))})

(def ^:private docs
  {"list-kb"    "(list-kb) — existing pages [{:title :type}]"
   "read-page"  "(read-page title) — a page's Markdown or nil"
   "grep"       "(grep q) — titles of pages containing q"
   "fetch"      "(fetch url) — readable text of an external URL"
   "search"     "(search q) — [{:title :url :snippet}] web search for external prior art"
   "similar"    "(similar text) — [{:title :type :score}] semantic nearest existing pages"
   "write-page" "(write-page {:title :type :body :url :author :date :tags}) — url/author/date go in frontmatter"})

(defn grant-spec [cfg]
  {:vocab (vocab cfg) :docs docs :ctx-info {:role "ingest" :kb (kb/root cfg)}})

(defn- prompt-for [{:keys [title url body]}]
  (let [text (or body (when url (reader/readable url)) "")]
    (str "SOURCE" (when title (str " — " title)) (when url (str " (" url ")")) ":\n\n" text)))

(defn ingest!
  "Ingest one source into the KB via one spawn against an ALREADY-RUNNING gateway.
   The living image owns the gateway; this is just the handler. source = {:title :url :body}."
  [cfg gateway-url source]
  (spawn/spawn {:model       (get-in cfg [:omp :model])
                :system      system
                :prompt      (prompt-for source)
                :gateway-url gateway-url
                :role        :ingest}))

(defn run-post
  "One-shot: spin a throwaway image, ingest one source, stop. For manual runs."
  ([source] (run-post (config/load-config) source))
  ([cfg source]
   (let [img (image/start! {:host  (get-in cfg [:gateway :host] "127.0.0.1")
                            :port  (get-in cfg [:gateway :port] 7777)
                            :roles {:ingest (fn [] (grant-spec cfg))}})]
     (try (ingest! cfg (:gateway-url img) source)
          (finally (image/stop! img))))))
