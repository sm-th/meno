(ns zulip-identity
  "Zulip identity adapter — owner-account operations used to provision agent
   bots: list/create bots, regenerate a bot's API key, list/subscribe streams.
   Basic auth with the owner account (a REGULAR account — bots cannot create
   bots). Implements the identity port the generic reconcile consumes, so
   another bus can be provisioned by supplying the same shape."
  (:require [shared.http :as http]
            [clojure.data.json :as json]
            [clojure.string :as str])
  (:import (java.util Base64)
           (java.net URLEncoder)))

(defn- basic [email api-key]
  (str "Basic " (.encodeToString (Base64/getEncoder)
                                 (.getBytes (str email ":" api-key) "UTF-8"))))

(defn- url [site path] (str site "/api/v1" path))

(defn- call [{:keys [site email api-key]} method path {:keys [form query]}]
  (let [qs (when (seq query)
             (str "?" (str/join "&" (map (fn [[k v]]
                                           (str (name k) "=" (URLEncoder/encode (str v) "UTF-8")))
                                         query))))
        {:keys [status body]}
        (http/request {:method  method
                       :url     (url site (str path qs))
                       :headers {"Authorization" (basic email api-key)}
                       :form    form
                       :timeout 60})]
    (when-not (and (= 200 status) (= "success" (:result body)))
      (throw (ex-info (str "zulip-identity: " (or (:msg body) "request failed")
                           " [" (:code body) "] " (name method) " " path)
                      {:status status :path path :body body})))
    body))

(defn adapter
  "Owner-account identity operations. cfg {:site :email :api-key}."
  [cfg]
  {:list-bots    (fn [] (:bots (call cfg :get "/bots" {})))
   :create-bot!  (fn [{:keys [full-name short-name]}]
                   (call cfg :post "/bots"
                         {:form {:full_name full-name :short_name short-name :bot_type 1}}))
   :regenerate!  (fn [bot-id]
                   (:api_key (call cfg :post (str "/bots/" bot-id "/api_key/regenerate") {})))
   :list-streams (fn [] (mapv :name (:streams (call cfg :get "/streams" {}))))
   :subscribe!   (fn [email streams]
                   (call cfg :post "/users/me/subscriptions"
                         {:form {:subscriptions (json/write-str (mapv (fn [s] {:name s}) streams))
                                 :principals    (json/write-str [email])}}))})
