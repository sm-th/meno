(ns publisher.machine
  "The publisher machine: a new note in #blog -> a site post + a Telegram repost,
   with the links sent back as replies in the thread, then the note reacted (📢)
   and handed to the researcher.

   Idempotent: each externally-visible step checks the thread for its receipt (the
   reply carrying the link) and skips it if already there — so a retry after a
   mid-pipeline failure resumes instead of duplicating.

   Ships default adapters (Zulip source, 11ty site, Telegram channel, Manifest
   translate). `build` deep-merges overrides, so a consumer swaps any facade."
  (:require [publisher.zulip :as zulip]
            [publisher.site :as site]
            [publisher.telegram :as telegram]
            [publisher.translate :as translate]
            [clojure.string :as str]))

(defn deep-merge
  "Recursively merge maps; a non-nil scalar in `b` overrides `a`."
  [a b]
  (cond
    (and (map? a) (map? b)) (merge-with deep-merge a b)
    (some? b) b
    :else a))

(defn- spoiler [title body]
  (str "```spoiler " (or title "post") "\n" body "\n```"))

(defn- site-host [config]
  (-> (get-in config [:site :site-url] "")
      (str/replace #"^https?://" "")
      (str/split #"/") first))

(defn publish-one!
  "Process one source post, idempotently. Each external step reuses the URL
   already posted as a reply in the thread (a prior receipt) or does the step and
   posts the receipt: translate -> [site + reply] -> [telegram + reply] ->
   on-published (react + researcher)."
  [{:keys [translate site channel on-published]} config source post]
  (let [{:keys [title body] :as en} (translate (:translate config) (:content post))
        find-link (or (:find-link source) (constantly nil))
        site-url  (or (find-link post (site-host config))
                      (let [{u :url} (site (:site config) en (:at post))]
                        ((:reply! source) post (str u "\n\n" (spoiler title body)))
                        u))
        tg-url    (or (find-link post "t.me")
                      (let [{u :url} (channel (:channel config)
                                             {:title title :body body :site-url site-url})]
                        ((:reply! source) post (str u "\n\n" body))
                        u))
        published {:title title :body body :site-url site-url :tg-url tg-url
                   :post-id (:id post) :topic (:topic post)}]
    (on-published source config post published)
    published))

(defn poll-once!
  "Publish every currently-new post once, isolating per-post failures so one bad
   post doesn't block the rest of the batch (nor stall the loop)."
  [ports config source]
  (->> ((:list-new source))
       (keep (fn [post]
               (try (publish-one! ports config source post)
                    (catch Throwable t
                      (println "!! publish" (pr-str (:topic post)) "failed:"
                               (or (ex-message t) (str t)))
                      nil))))
       vec))

(def defaults
  {:ports  {:translate    translate/translate
            :site         site/publish!
            :channel      telegram/send-post!
            :make-source  zulip/adapter
            ;; done policy — overridable: default reacts (marks published), never
            ;; resolves, so the thread stays open for the researcher.
            :on-published (fn [source _config post _published] ((:mark-published! source) post))}
   :config {:translate {:model "claude-opus-4-8"}
            :source    {:stream "blog"}
            :site      {:branch "main" :posts-subdir "src" :zone "+07:00"}
            :channel   {:disable-preview true}}})

(defn build
  "Assemble the machine. `overrides` deep-merges onto `defaults`. Returns the
   merged map plus :source (the built source port) and :run (one poll pass)."
  [overrides]
  (let [{:keys [ports config] :as m} (deep-merge defaults overrides)
        source ((:make-source ports) (:source config))]
    (assoc m
           :source source
           :run    (fn [] (poll-once! ports config source)))))
