(ns botfather
  "One-time bootstrap for the provisioning owner account — the 'botfather', an
   admin USER (bots cannot create bots). Interactive:
     create — mint a fresh admin account via the API (self-hosted, or a realm
              where your account has can_create_users + password auth enabled);
     adopt  — store an account you created by hand (paste its email + API key).
   Stores ZULIP_OWNER_EMAIL + ZULIP_OWNER_API_KEY in secretspec. Runtime auth is
   email + API key (a token); a password is used only transiently in `create` to
   mint that key via fetch_api_key.

   Run:  nix develop -c clojure -M -m botfather"
  (:require [shared.http :as http]
            [clojure.java.shell :as sh]
            [clojure.string :as str])
  (:import (java.util Base64)
           (java.security SecureRandom)))

(defn- prompt [q]
  (print (str q ": ")) (flush)
  (str/trim (or (read-line) "")))

(defn- prompt-secret [q]
  (if-let [c (System/console)]
    (str/trim (String. (.readPassword c "%s: " (object-array [q]))))
    (prompt q)))

(defn- basic [email key]
  (str "Basic " (.encodeToString (Base64/getEncoder)
                                 (.getBytes (str email ":" key) "UTF-8"))))

(defn- gen-password []
  (let [b (byte-array 24)]
    (.nextBytes (SecureRandom.) b)
    (.encodeToString (Base64/getUrlEncoder) b)))

(defn- api [site path] (str site "/api/v1" path))

(defn- ok? [{:keys [status body]}]
  (and (= 200 status) (= "success" (:result body))))

(defn- verify!
  "A proxy for 'can provision': the account can list bots."
  [site email key]
  (let [r (http/request {:method  :get :url (api site "/bots")
                         :headers {"Authorization" (basic email key)} :timeout 30})]
    (if (ok? r)
      (println "  verified: account reaches /bots (" (count (:bots (:body r))) "existing bots ).")
      (throw (ex-info "verify failed — account cannot list bots" {:resp r})))))

(defn- store! [email key]
  (doseq [[k v] [["ZULIP_OWNER_EMAIL" email] ["ZULIP_OWNER_API_KEY" key]]]
    (let [{:keys [exit err]} (sh/sh "secretspec" "set" k v "--reason" "botfather bootstrap")]
      (when-not (zero? exit)
        (throw (ex-info (str "secretspec set " k " failed") {:err (str/trim (str err))})))))
  (println "  stored ZULIP_OWNER_EMAIL + ZULIP_OWNER_API_KEY in secretspec."))

(defn adopt! [site]
  (let [email (prompt "botfather account email")
        key   (prompt-secret "botfather API key (token)")]
    (verify! site email key)
    (store! email key)))

(defn create! [site]
  (let [admin-email (prompt "your admin email (bootstrap, NOT stored)")
        admin-key   (prompt-secret "your admin API key (bootstrap, NOT stored)")
        bf-email    (prompt "new botfather email (e.g. botfather@your-realm)")
        bf-name     (let [n (prompt "new botfather full name [Botfather]")]
                      (if (str/blank? n) "Botfather" n))
        pw          (gen-password)
        auth        {"Authorization" (basic admin-email admin-key)}
        created     (http/request {:method :post :url (api site "/users") :headers auth
                                   :form {:email bf-email :password pw :full_name bf-name}
                                   :timeout 60})]
    (when-not (ok? created)
      (throw (ex-info "create user failed — does your account have can_create_users?"
                      {:resp created})))
    (let [uid (:user_id (:body created))
          promoted (http/request {:method :patch :url (api site (str "/users/" uid))
                                  :headers auth :form {:role 200} :timeout 60})]  ; 200 = administrator
      (when-not (ok? promoted)
        (println "  WARN: could not set admin role:" (:msg (:body promoted))))
      (let [fetched (http/request {:method :post :url (api site "/fetch_api_key")
                                   :form {:username bf-email :password pw} :timeout 60})]
        (when-not (ok? fetched)
          (throw (ex-info "fetch_api_key failed — is password auth (EmailAuthBackend) enabled?"
                          {:resp fetched})))
        (let [bf-key (:api_key (:body fetched))]
          (println "  created botfather" bf-email "(administrator), minted its API key.")
          (verify! site bf-email bf-key)
          (store! bf-email bf-key))))))

(defn -main [& _]
  (println "Zulip botfather bootstrap — the provisioning owner (admin user).")
  (let [site (prompt "Zulip realm URL (https://…)")]
    (println "\n  1) create a new admin 'botfather' via API (self-hosted / can_create_users)")
    (println "  2) adopt an existing account (paste its email + API key)")
    (case (prompt "choose [1/2]")
      "1" (create! site)
      "2" (adopt! site)
      (println "cancelled."))
    (println "\nDone. Next: set per-machine secrets (PUBLISHER_TELEGRAM_BOT_TOKEN, …)"
             "then run castle.boot.")))
