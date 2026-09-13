(ns shared.http
  "Minimal JSON/form HTTP over java.net.http. Self-contained so the publisher
   tree carries no dependency on researcher.*."
  (:require [clojure.data.json :as json]
            [clojure.string :as str])
  (:import (java.net URI URLEncoder)
           (java.net.http HttpClient HttpClient$Redirect HttpRequest
                          HttpRequest$BodyPublishers HttpResponse$BodyHandlers)
           (java.time Duration)))

(defn- encode-form [m]
  (->> m
       (map (fn [[k v]] (str (URLEncoder/encode (name k) "UTF-8")
                             "=" (URLEncoder/encode (str v) "UTF-8"))))
       (str/join "&")))

(defn request
  "Perform an HTTP request. Opts:
     :method  :get/:post/:put/:patch/:delete   (default :get)
     :url     string
     :headers map of extra headers
     :json    clj data serialized as a JSON body
     :form    clj map serialized as x-www-form-urlencoded
     :timeout seconds (default 120)
   Returns {:status int :body parsed-json-or-raw-string}."
  [{:keys [method url headers json form timeout]}]
  (let [b (HttpRequest/newBuilder (URI/create url))
        [ct pub] (cond
                   json ["application/json"
                         (HttpRequest$BodyPublishers/ofString (json/write-str json))]
                   form ["application/x-www-form-urlencoded"
                         (HttpRequest$BodyPublishers/ofString (encode-form form))]
                   :else [nil (HttpRequest$BodyPublishers/noBody)])]
    (.timeout b (Duration/ofSeconds (long (or timeout 120))))
    (when ct (.header b "Content-Type" ct))
    (doseq [[k v] headers] (.header b (name k) (str v)))
    (case (or method :get)
      :get    (.GET b)
      :post   (.POST b pub)
      :put    (.PUT b pub)
      :patch  (.method b "PATCH" pub)
      :delete (.method b "DELETE" (HttpRequest$BodyPublishers/noBody)))
    (let [client (-> (HttpClient/newBuilder)
                     (.followRedirects HttpClient$Redirect/NORMAL)
                     .build)
          resp   (.send client (.build b) (HttpResponse$BodyHandlers/ofString))
          body   (.body resp)]
      {:status (.statusCode resp)
       :body   (when (seq body)
                 (try (json/read-str body :key-fn keyword)
                      (catch Exception _ body)))})))
