(ns publisher.machine
  "The publisher machine: a new note in #blog -> a site post + a Telegram repost,
   with the links sent back as replies in the thread, then the thread marked done.

   Ships default adapters (Zulip source, 11ty site, Telegram channel, Manifest
   translate). `build` deep-merges overrides, so a consumer swaps any facade: a
   different source bus (Discourse), a different channel, or the done policy
   (e.g. let the researcher resolve the thread after it ingests, not here)."
  (:require [publisher.zulip :as zulip]
            [publisher.site :as site]
            [publisher.telegram :as telegram]
            [publisher.translate :as translate]))

(defn deep-merge
  "Recursively merge maps; a non-nil scalar in `b` overrides `a`."
  [a b]
  (cond
    (and (map? a) (map? b)) (merge-with deep-merge a b)
    (some? b) b
    :else a))

(defn- spoiler [title body]
  (str "```spoiler " (or title "post") "\n" body "\n```"))

(defn publish-one!
  "Run the full pipeline for one source post. Sends TWO replies into the thread
   (site link + spoilered text, then Telegram link + text), never editing the
   original, and finally applies the done policy. Returns a summary."
  [{:keys [translate site channel on-published]} config source post]
  (let [{:keys [title body] :as en} (translate (:translate config) (:content post))
        {site-url :url} (site (:site config) en (:at post))]
    ((:reply! source) post (str site-url "\n\n" (spoiler title body)))
    (let [{tg-url :url} (channel (:channel config) (str body "\n\n" site-url))]
      ((:reply! source) post (str tg-url "\n\n" body))
      (on-published source config post)
      {:post-id (:id post) :topic (:topic post) :site-url site-url :tg-url tg-url})))

(defn poll-once!
  "Publish every currently-new post once. Idempotent: the done marker keeps a
   post from being republished on the next pass."
  [ports config source]
  (mapv #(publish-one! ports config source %) ((:list-new source))))

(def defaults
  {:ports  {:translate    translate/translate
            :site         site/publish!
            :channel      telegram/send-post!
            :make-source  zulip/adapter
            ;; done policy — overridable: default resolves the thread here.
            :on-published (fn [source _config post] ((:mark-done! source) post))}
   :config {:translate {:model "claude-opus-4-8"}
            :source    {:stream "blog"}
            :site      {:branch "main" :posts-subdir "src" :zone "+07:00"}
            :channel   {:disable-preview true}}})

(defn build
  "Assemble the machine. `overrides` deep-merges onto `defaults`: replace any
   facade under :ports (e.g. :make-source discourse/adapter, :on-published a
   no-op) or any per-adapter values under :config. Returns the merged map plus
   :source (the built source port) and :run (poll once)."
  [overrides]
  (let [{:keys [ports config] :as m} (deep-merge defaults overrides)
        source ((:make-source ports) (:source config))]
    (assoc m
           :source source
           :run    (fn [] (poll-once! ports config source)))))
