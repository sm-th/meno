(ns researcher.ingest
  "meno domain — the ingest role.

  Read ONE source and integrate it into the discourse-graph KB. Defines the
  capability grant the ingest agent gets (the verbs it may eval) and its system
  prompt. `ingest!` is the handler (one spawn against an already-running image);
  `run-post` is a one-shot convenience that spins a throwaway image around it."
  (:require [researcher.kb :as kb]
            [researcher.config :as config]
            [researcher.reader :as reader]
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
       "  (write-page {:title .. :type .. :body ..}) -> create/overwrite a page\n\n"
       "Page types: :concept (canonical SHORT definition), :source (a digested reading, "
       "cite its url in the body), :question (one open question), :claim (a subjective "
       "position/take), :research (a worked answer). One idea per page.\n\n"
       "Process: read the source; (list-kb) and (grep ...) to see what already exists; "
       "then write the pages it warrants — a :concept per key idea, a :source for the "
       "reading itself, :claim pages for positions taken, :question pages for what's left "
       "open — each linking related pages as [[Canonical Title]] and citing sources. "
       "UPDATE an existing page (read-page then write-page) instead of duplicating. Keep "
       "pages atomic and short. When there is nothing more to file, stop."))

(defn- vocab [cfg]
  {"list-kb"    (fn [] (mapv #(select-keys % [:title :type]) (kb/index cfg)))
   "read-page"  (fn [title] (kb/read-page cfg title))
   "grep"       (fn [q] (kb/grep cfg q))
   "fetch"      (fn [url] (reader/readable url))
   "write-page" (fn [page] (kb/write-page! cfg page))})

(def ^:private docs
  {"list-kb"    "(list-kb) — existing pages [{:title :type}]"
   "read-page"  "(read-page title) — a page's Markdown or nil"
   "grep"       "(grep q) — titles of pages containing q"
   "fetch"      "(fetch url) — readable text of an external URL"
   "write-page" "(write-page {:title :type :body}) — create/overwrite a page"})

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
