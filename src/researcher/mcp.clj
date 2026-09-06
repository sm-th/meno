(ns researcher.mcp
  "The living researcher image, ONE process:
     - HTTP MCP gateway (single tool `eval`) at /mcp/{planner,worker}
     - an nREPL server (connect your editor to the SAME image)
     - a stdin REPL (drive it over the process stdin)
   The HTTP handler rebuilds the grant from live code+config on every request, so
   `(require ... :reload)` / config edits take effect immediately — no restart.
   Secrets/state live here; omp sessions hold only the gateway URL."
  (:require [clojure.data.json :as json]
            [clojure.main]
            [nrepl.server :as nrepl]
            [cider.nrepl :refer [cider-nrepl-handler]]
            [researcher.config :as config]
            [researcher.grant :as grant]
            [researcher.planner :as planner]
            [researcher.worker :as worker]
            [researcher.task :as task])
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
                           :serverInfo {:name "researcher" :version "0.1"}}}
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

(defn- handler [profile]
  (proxy [HttpHandler] []
    (handle [^HttpExchange ex]
      (try
        (let [req  (json/read-str (slurp (.getRequestBody ex)))
              ;; live: rebuild grant from current config+code each request
              world (if (= :worker profile)
                      (merge {:profile :worker} @task/current)
                      {:profile profile})
              ctx  (grant/build (config/load-config) world)
              resp (handle-rpc ctx req)]
          (if resp
            (write-json! ex 200 resp)
            (do (.sendResponseHeaders ex 202 -1) (.close (.getResponseBody ex)))))
        (catch Throwable t
          (write-json! ex 200 {:jsonrpc "2.0" :id nil
                               :error {:code -32603 :message (.getMessage t)}})))
      nil)))

(defn plan!
  "Trigger one planner tick FROM the living image: the image spawns omp, which
   calls back into this same image's eval gateway and files an issue."
  []
  (planner/run (config/load-config)))

(defn work!
  "Trigger one worker tick FROM the living image on an approved (Todo) issue.
   number = issue number, or nil for the first in the queue. The image spawns
   omp, which writes to the per-issue branch via this same gateway, then a PR opens."
  ([] (work! nil))
  ([number]
   (let [cfg   (config/load-config)
         issue (worker/pick cfg number)]
     (if issue
       (worker/run cfg issue)
       (println "no approved (Todo) issue" (when number (str "#" number)))))))

(defn -main [& _]
  (let [cfg   (config/load-config)
        host  (get-in cfg [:gateway :host] "127.0.0.1")
        port  (get-in cfg [:gateway :port] 7777)
        nport (get-in cfg [:gateway :nrepl-port] 7778)
        srv   (HttpServer/create (InetSocketAddress. ^String host (int port)) 0)]
    (doseq [profile [:planner :worker]]
      (.createContext srv (str "/mcp/" (name profile)) (handler profile)))
    (.setExecutor srv nil)
    (.start srv)
    (nrepl/start-server :bind host :port nport :handler cider-nrepl-handler)
    (println (str "researcher living image | gateway http://" host ":" port
                  "/mcp/{planner,worker} | nrepl " host ":" nport))
    (flush)
    (clojure.main/repl :prompt #(do (print "image=> ") (flush)))))
