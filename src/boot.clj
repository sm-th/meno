(ns boot
  "Instance boot: read the description, PROVISION identities (Zulip bots), DELIVER
   accesses to each machine, BUILD and RUN. The glue that knows which machines
   exist; the generic parts (provision/deliver) stay machine-agnostic."
  (:require [provision :as provision]
            [zulip-identity :as zid]
            [deliver :as deliver]
            [publisher.machine :as pub]
            [publisher.config :as pubcfg]
            [research :as research]
            [publisher.zulip :as zulip]
            [zeno.loop :as zloop]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.walk :as walk]))

(defn- env [k] (System/getenv k))

(defn- expand [x]
  (if (and (string? x) (str/starts-with? x "~"))
    (str (System/getProperty "user.home") (subs x 1)) x))

(defn load-instance
  ([] (load-instance "instance.edn"))
  ([path] (->> (edn/read-string (slurp path)) (walk/postwalk expand))))

(defn provision!
  "Reconcile every machine's chat identity against Zulip; returns
   {machine-key {:email :api-key :user-id}}."
  [inst]
  (let [z (:zulip inst)
        owner {:site (env (:site-env z)) :email (env (:owner-email-env z))
               :api-key (env (:owner-key-env z))}
        agents (into {} (for [[k m] (:machines inst)] [k (:identity m)]))]
    (provision/reconcile! (zid/adapter owner) agents)))

(defn researcher-cfg
  "Sandbox-delivery cfg for the researcher: instance :research merged with
   network-bound secrets (GH_TOKEN + the Manifest ANTHROPIC_API_KEY) and broad
   web egress. No Zulip creds — the wiki worker never talks to Zulip."
  [inst]
  (let [plan (deliver/plan {:machine  :researcher
                            :accesses (get-in inst [:machines :researcher :accesses])
                            :catalog  (:access-catalog inst)
                            :identity nil})
        spec (deliver/sandbox-spec plan)]
    (merge (:research inst)
           {:net-bound  (:net-bound spec)
            :secret-env (reduce (fn [m {:keys [env value]}] (assoc m env value))
                                {} (:secrets plan))
            :egress     {:net "public"}})))

(defn publisher-overrides
  "Fold the provisioned publisher bot + delivered accesses into publisher/build
   overrides (host delivery). Structural config comes from publisher.edn."
  [inst ids]
  (let [z    (:zulip inst)
        plan (deliver/plan {:machine    :publisher
                            :accesses   (get-in inst [:machines :publisher :accesses])
                            :catalog    (:access-catalog inst)
                            :identity   (:publisher ids)
                            :zulip-site (env (:site-env z))
                            :zulip-host (:host z)})
        e    (deliver/env-map plan)
        pcfg (pubcfg/load-config)
        rcfg  (researcher-cfg inst)
        r-src (zulip/adapter {:site    (env (:site-env z))
                              :email   (get-in ids [:researcher :email])
                              :api-key (get-in ids [:researcher :api-key])
                              :stream  "blog"})]
    {:ports  {:on-published (fn [source _config post published]
                              ((:mark-published! source) post)
                              (future
                                (let [r (research/ingest! rcfg published)]
                                  (when (zero? (:exit r))
                                    (when-let [acc (research/accept! rcfg r)]
                                      ((:reply! r-src) post (research/receipt acc))
                                      (research/changelog! rcfg (assoc acc :title (:title r))))))))}
     :config {:translate (:translate pcfg)
              :site      (:site pcfg)
              :source    (merge (:zulip pcfg)
                                {:site    (get e "ZULIP_SITE")
                                 :email   (get e "ZULIP_EMAIL")
                                 :api-key (get e "ZULIP_API_KEY")})
              :channel   (merge (:telegram pcfg) {:token (get e "TELEGRAM_BOT_TOKEN")})}}))

(defn setup!
  "Provision identities once and build the publisher machine once. Returns
   {:source :run} — the built source port and a one-publish-pass fn."
  []
  (let [inst (load-instance)
        ids  (provision! inst)]
    (println "provisioned:" (vec (keys ids)))
    (let [pubm (pub/build (publisher-overrides inst ids))]
      {:source (:source pubm) :run (:run pubm)})))

(defn start-publisher!
  "Run the #blog publisher as an event-driven long-poll in a supervised daemon
   thread: register a Zulip event queue, block on /events, and on each new message
   run one idempotent publish pass. The blocking long-poll lives on its OWN thread
   (never on zeno's single scheduler thread); a zeno.loop watchdog restarts it if it
   ever dies. The listen loop catches everything, so it does not die on its own."
  []
  (let [{:keys [source run]} (setup!)
        spawn (fn []
                (doto (Thread.
                       (fn []
                         (let [qstate (atom nil)]
                           (loop []
                             (try
                               (when (:wake? ((:events! source) qstate))
                                 (doseq [r (run)]
                                   (println "published:" (:topic r) "->" (:site-url r) "|" (:tg-url r))))
                               (catch Throwable e
                                 (println "!! publisher listener:" (or (.getMessage e) (str e)))
                                 (Thread/sleep 2000)))
                             (recur)))))
                  (.setName "publisher-longpoll")
                  (.setDaemon true)
                  (.start)))
        thread (atom (spawn))]
    (zloop/every :publisher-watchdog 10000
                 (fn []
                   (when-not (.isAlive ^Thread @thread)
                     (println "publisher listener died — restarting")
                     (reset! thread (spawn)))))
    (println "zeno: publisher listening on #blog (event-driven long-poll)")
    nil))

(defn -main
  "One-shot: provision + one publisher pass (for manual `clojure -M` runs)."
  [& _]
  (doseq [r ((:run (setup!)))]
    (println "published:" (:topic r) "->" (:site-url r) "|" (:tg-url r))))
