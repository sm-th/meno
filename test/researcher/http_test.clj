(ns researcher.http-test
  (:require [clojure.test :refer [deftest is]]
            [researcher.http :as http]
            [clojure.string :as str])
  (:import (com.sun.net.httpserver HttpServer HttpHandler HttpExchange)
           (java.net InetSocketAddress)))

(defn- echo-server
  "Tiny in-process server that echoes the request method as JSON."
  []
  (let [srv (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext srv "/"
                    (proxy [HttpHandler] []
                      (handle [^HttpExchange ex]
                        (let [resp (.getBytes (str "{\"method\":\"" (.getRequestMethod ex) "\"}") "UTF-8")]
                          (.sendResponseHeaders ex 200 (alength resp))
                          (doto (.getResponseBody ex) (.write resp) (.close))))))
    (.setExecutor srv nil)
    (.start srv)
    srv))

(deftest method-dispatch
  ;; Regression: :patch/:delete must be dispatched (a missing clause once broke
  ;; issue-closing with "No matching clause: :patch").
  (let [srv  (echo-server)
        port (.getPort (.getAddress srv))
        base (str "http://127.0.0.1:" port "/")]
    (try
      (doseq [m [:get :post :put :patch :delete]]
        (let [{:keys [status body]} (http/json-request {:method m :url base :json {:a 1}})]
          (is (= 200 status) (str m))
          (is (= (str/upper-case (name m)) (get body "method")) (str m " echoed"))))
      (finally (.stop srv 0)))))
