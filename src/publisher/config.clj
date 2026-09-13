(ns publisher.config
  "Instance config for the publisher machine. Structure lives in publisher.edn
   (checked in); secrets come from the environment (secretspec). Kept separate
   from researcher.* so the publisher tree stays self-contained."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.walk :as walk]))

(defn- expand [x]
  (if (and (string? x) (str/starts-with? x "~"))
    (str (System/getProperty "user.home") (subs x 1))
    x))

(defn load-config
  ([] (load-config "publisher.edn"))
  ([path] (->> (edn/read-string (slurp path)) (walk/postwalk expand))))

(defn- env [k] (System/getenv k))

(defn overrides
  "Turn the instance config + env secrets into machine `build` overrides:
   Zulip creds and the Telegram token are injected from the environment."
  [cfg]
  {:config
   {:translate (:translate cfg)
    :source    (merge (:zulip cfg)
                      {:site    (env "ZULIP_SITE")
                       :email   (env "ZULIP_EMAIL")
                       :api-key (env "ZULIP_API_KEY")})
    :site      (:site cfg)
    :channel   (merge (:telegram cfg) {:token (env "TELEGRAM_BOT_TOKEN")})}})
