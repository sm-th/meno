(ns researcher.image
  "meno living image — the ever-living process.

  Starts the Zeno MCP gateway ONCE (the ingest grant) and runs a supervised loop:
  each tick, ingest the newest blog posts not yet in the KB. It never exits;
  ingest is a handler that spawns a session within the live image. State is not
  held here — 'already ingested?' is derived from the KB (a post's URL is cited by
  its source page), so a restart just rescans."
  (:require [researcher.config :as config]
            [researcher.ingest :as ingest]
            [researcher.blog :as blog]
            [researcher.kb :as kb]
            [zeno.image :as image]
            [zeno.loop :as zloop]))

(defn new-posts
  "Newest blog posts whose URL isn't yet cited anywhere in the KB (stateless dedup)."
  [cfg n]
  (vec (remove #(seq (kb/grep cfg (:url %))) (blog/recent cfg n))))

(defn -main [& _]
  (let [cfg0 (config/load-config)
        img  (image/start! {:host  (get-in cfg0 [:gateway :host] "127.0.0.1")
                            :port  (get-in cfg0 [:gateway :port] 7777)
                            :roles {:ingest (fn [] (ingest/grant-spec (config/load-config)))}})
        steps [["ingest-new"
                (fn [ctx]
                  (let [cfg (config/load-config)
                        n   (get-in cfg [:reflect :ingest-new-per-run] 5)]
                    (doseq [p (new-posts cfg n)]
                      (println "ingest:" (:title p) "->" (:url p))
                      (ingest/ingest! cfg (:gateway-url img) p)))
                  ctx)]]]
    (println "meno living image up | gateway" (:gateway-url img))
    (loop [ctx {}]
      (let [ctx' (zloop/tick steps ctx)]
        (Thread/sleep (get-in (config/load-config) [:reflect :interval-ms] 3600000))
        (recur ctx')))))
