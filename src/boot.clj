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
        rcfg (researcher-cfg inst)]
    {:ports  {:on-published (fn [source _config post published]
                              ((:mark-published! source) post)
                              (future
                                (let [r (research/ingest! rcfg published)]
                                  (when (zero? (:exit r))
                                    (research/accept! rcfg r)))))}
     :config {:translate (:translate pcfg)
              :site      (:site pcfg)
              :source    (merge (:zulip pcfg)
                                {:site    (get e "ZULIP_SITE")
                                 :email   (get e "ZULIP_EMAIL")
                                 :api-key (get e "ZULIP_API_KEY")})
              :channel   (merge (:telegram pcfg) {:token (get e "TELEGRAM_BOT_TOKEN")})}}))

(defn setup!
  "Provision identities once; return {:poll-ms :step}. `step` runs one publisher
   poll pass. The engine (zeno.loop) schedules it — the instance never loops."
  []
  (let [inst (load-instance)
        ids  (provision! inst)]
    (println "provisioned:" (vec (keys ids)))
    {:poll-ms (get inst :poll-ms 60000)
     :step (fn []
             (let [pubm (pub/build (publisher-overrides inst ids))]
               (doseq [r ((:run pubm))]
                 (println "published:" (:topic r) "->" (:site-url r) "|" (:tg-url r)))))}))

(defn -main
  "One-shot: provision + one publisher pass (for manual `clojure -M` runs)."
  [& _]
  ((:step (setup!))))
