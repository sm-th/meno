(ns researcher.mcp
  "The living researcher image, ONE process:
     - HTTP MCP gateway (single tool `eval`) at /mcp/<role> for every configured role
     - an nREPL server (connect your editor to the SAME image)
     - a stdin REPL (drive it over the process stdin)
   The HTTP handler rebuilds the grant from live code+config on every request, so
   `(require ... :reload)` / config edits take effect immediately — no restart.
   Secrets/state live here; omp sessions hold only the gateway URL."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [nrepl.server :as nrepl]
            [cider.nrepl :refer [cider-nrepl-handler]]
            [researcher.config :as config]
            [researcher.grant :as grant]
            [researcher.runner :as runner]
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
            t0   (System/currentTimeMillis)
            [ok? out] (try [true (pr-str (grant/eval-ctx ctx code))]
                           (catch Throwable t [false (str "ERROR: " (.getMessage t))]))
            ms   (- (System/currentTimeMillis) t0)]
        (swap! task/trace conj {:code code :ok? ok? :result out :ms ms})
        (println (str "  eval[" (if ok? "ok" "ERR") " " ms "ms] "
                      (subs code 0 (min 100 (count code)))
                      " => " (subs out 0 (min 140 (count out)))))
        (flush)
        {:jsonrpc "2.0" :id id :result {:content [{:type "text" :text out}]}})
      {:jsonrpc "2.0" :id id :error {:code -32602 :message (str "unknown tool: " (get params "name"))}})
    (when id {:jsonrpc "2.0" :id id :error {:code -32601 :message (str "unknown method: " method)}})))

(defn- write-json! [^HttpExchange ex status obj]
  (let [bytes (.getBytes (json/write-str obj) "UTF-8")]
    (.set (.getResponseHeaders ex) "Content-Type" "application/json")
    (.sendResponseHeaders ex status (alength bytes))
    (doto (.getResponseBody ex) (.write bytes) (.close))))

(defn- handler [role]
  (proxy [HttpHandler] []
    (handle [^HttpExchange ex]
      (try
        (let [req  (json/read-str (slurp (.getRequestBody ex)))
              ;; live: rebuild the grant from current config+code each request.
              ;; task/current carries the running issue's branch (writes roles).
              world (assoc (or @task/current {}) :role role)
              ctx   (grant/build (config/load-config) world)
              resp  (handle-rpc ctx req)]
          (if resp
            (write-json! ex 200 resp)
            (do (.sendResponseHeaders ex 202 -1) (.close (.getResponseBody ex)))))
        (catch Throwable t
          (write-json! ex 200 {:jsonrpc "2.0" :id nil
                               :error {:code -32603 :message (.getMessage t)}})))
      nil)))

(defn plan!
  "Top-of-pipeline: run the PLAN role on the newest published note (files research
   tasks into Backlog via propose-task!)."
  []
  (runner/run-issue (config/load-config) (runner/seed-issue (config/load-config) :plan)))

(defn ingest!
  "Top-of-pipeline: run the INGEST role on the newest published note."
  []
  (runner/run-issue (config/load-config) (runner/seed-issue (config/load-config) :ingest)))

(defn work!
  "Run one approved (Todo) issue by number, or the first in the queue, under its
   own `role:<name>` tag. Writes roles open a PR."
  ([] (work! nil))
  ([number]
   (let [cfg   (config/load-config)
         issue (runner/pick cfg number)]
     (if issue
       (runner/run-issue cfg issue)
       (println "no approved (Todo) issue" (when number (str "#" number)))))))

;; tick! = alias for work!: pull the next approved issue of any role.
(def tick! work!)

(defn -main [& _]
  (let [cfg   (config/load-config)
        host  (get-in cfg [:gateway :host] "127.0.0.1")
        port  (get-in cfg [:gateway :port] 7777)
        nport (get-in cfg [:gateway :nrepl-port] 7778)
        roles (keys (:roles cfg))
        srv   (HttpServer/create (InetSocketAddress. ^String host (int port)) 0)]
    (doseq [role roles]
      (.createContext srv (str "/mcp/" (name role)) (handler role)))
    (.setExecutor srv (java.util.concurrent.Executors/newFixedThreadPool 4))
    (.start srv)
    (nrepl/start-server :bind host :port nport :handler cider-nrepl-handler)
    (println (str "researcher living image | gateway http://" host ":" port
                  "/mcp/{" (str/join "," (map name roles)) "} | nrepl " host ":" nport))
    (flush)
    (clojure.main/repl :prompt #(do (print "image=> ") (flush)))))
