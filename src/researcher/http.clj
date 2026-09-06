(ns researcher.http
  "Minimal JSON HTTP over the built-in java.net.http client."
  (:require [clojure.data.json :as json])
  (:import (java.net URI)
           (java.net.http HttpClient HttpRequest
                          HttpRequest$BodyPublishers HttpResponse$BodyHandlers)
           (java.time Duration)))

(defn json-request
  "Perform an HTTP request. Opts: :method (:get/:post/:put), :url, :headers map,
   :json (clj data serialized as the request body).
   Returns {:status int :body parsed-json-or-raw-string}."
  [{:keys [method url headers json]}]
  (let [b (HttpRequest/newBuilder (URI/create url))
        pub (if json
              (HttpRequest$BodyPublishers/ofString (json/write-str json))
              (HttpRequest$BodyPublishers/noBody))]
    (.timeout b (Duration/ofSeconds 120))
    (.header b "Content-Type" "application/json")
    (doseq [[k v] headers] (.header b (name k) (str v)))
    (case (or method :get)
      :get  (.GET b)
      :post (.POST b pub)
      :put  (.PUT b pub)
      :delete (.method b "DELETE" (HttpRequest$BodyPublishers/noBody)))
    (let [resp (.send (HttpClient/newHttpClient) (.build b)
                      (HttpResponse$BodyHandlers/ofString))
          body (.body resp)]
      {:status (.statusCode resp)
       :body (when (seq body)
               (try (json/read-str body) (catch Exception _ body)))})))
