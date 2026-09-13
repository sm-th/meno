(ns publisher.main
  "Runner for the publisher machine: build it from instance config, then poll the
   source forever under a supervised zeno loop (a crashing pass is isolated, not
   fatal). One-shot `once` publishes the current backlog and exits."
  (:require [publisher.config :as config]
            [publisher.machine :as machine]
            [zeno.loop :as zloop]))

(defn- machine! []
  (let [cfg (config/load-config)]
    [cfg (machine/build (config/overrides cfg))]))

(defn once
  "Publish every currently-new post once, then return the summaries."
  [& _]
  (let [[_ m] (machine!)]
    (doseq [r ((:run m))] (println "published:" (:topic r) "->" (:site-url r) "|" (:tg-url r)))))

(defn -main
  "Poll #blog forever."
  [& _]
  (let [[cfg m] (machine!)
        steps [["publish-blog" (fn [ctx] ((:run m)) ctx)]]]
    (println "publisher up | stream" (get-in cfg [:zulip :stream] "blog"))
    (loop [ctx {}]
      (let [ctx' (zloop/tick steps ctx)]
        (Thread/sleep (get cfg :poll-ms 60000))
        (recur ctx')))))
