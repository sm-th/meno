(ns researcher.mcp
  "HTTP MCP server = the ONE door into the long-lived researcher image. Exposes a
   single tool `eval` over Streamable-HTTP (plain JSON-RPC responses). Profile is
   the URL path: POST /mcp/planner or /mcp/worker -> that grant."
  (:require [clojure.data.json :as json]
            [researcher.config :as config]
            [researcher.grant :as grant])
  (:import (com.sun.net.httpserver HttpServer HttpHandler HttpExchange)
           (java.net InetSocketAddress))
  (:gen-class))

(def ^:private tool
  {:name "eval"
   :description (str "Evaluate a Clojure form against the granted capabilities "
                     "(deny-by-default; only granted symbols exist). Call (context) "
                     "to see the grant.")
   :inputSchema {:type "object"
                 :properties {:code {:type "string" :description "Clojure to evaluate"}}
                 :required ["code"]}})

(defn- handle-rpc [ctx {:strs [id method params]}]
  (case method
    "initialize" {:jsonrpc "2.0" :id id
                  :result {:protocolVersion "2024-11-05"
                           :capabilities {:tools {}}
                           :serverInfo {:name "researcher-door" :version "0.1"}}}
    "notifications/initialized" nil
    "ping" {:jsonrpc "2.0" :id id :result {}}
    "tools/list" {:jsonrpc "2.0" :id id :result {:tools [tool]}}
    "tools/call"
    (if (= "eval" (get params "name"))
      (let [code (get-in params ["arguments" "code"])
            out (try (pr-str (grant/eval-ctx ctx code))
                     (catch Throwable t (str "ERROR: " (.getMessage t))))]
        {:jsonrpc "2.0" :id id :result {:content [{:type "text" :text out}]}})
      {:jsonrpc "2.0" :id id :error {:code -32602 :message (str "unknown tool: " (get params "name"))}})
    (when id {:jsonrpc "2.0" :id id :error {:code -32601 :message (str "unknown method: " method)}})))

(defn- write-json! [^HttpExchange ex status obj]
  (let [bytes (.getBytes (json/write-str obj) "UTF-8")]
    (.set (.getResponseHeaders ex) "Content-Type" "application/json")
    (.sendResponseHeaders ex status (alength bytes))
    (doto (.getResponseBody ex) (.write bytes) (.close))))

(defn- handler [ctx]
  (proxy [HttpHandler] []
    (handle [^HttpExchange ex]
      (try
        (let [req  (json/read-str (slurp (.getRequestBody ex)))
              resp (handle-rpc ctx req)]
          (if resp
            (write-json! ex 200 resp)
            (do (.sendResponseHeaders ex 202 -1) (.close (.getResponseBody ex)))))
        (catch Throwable t
          (write-json! ex 200 {:jsonrpc "2.0" :id nil
                               :error {:code -32603 :message (.getMessage t)}})))
      nil)))

(defn -main [& _]
  (let [cfg  (config/load-config)
        host (get-in cfg [:door :host] "127.0.0.1")
        port (get-in cfg [:door :port] 7777)
        srv  (HttpServer/create (InetSocketAddress. ^String host (int port)) 0)]
    (doseq [profile [:planner :worker]]
      (.createContext srv (str "/mcp/" (name profile))
                      (handler (grant/build cfg {:profile profile}))))
    (.setExecutor srv nil)
    (.start srv)
    (println (str "researcher door listening on http://" host ":" port "/mcp/{planner,worker}"))
    (flush)
    @(promise)))
