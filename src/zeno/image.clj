(ns zeno.image
  "Zeno core — the living image + MCP gateway.

  One long-lived process that exposes the capability grant to spawned agents over
  HTTP MCP (`/mcp/<role>`, a single `eval` tool). The domain supplies `roles`: a
  map of role-keyword -> (fn [] grant-spec), rebuilt per request from current
  config/code so live edits take effect without a restart. The image holds the
  secrets and privilege; spawned sessions see only the gateway URL."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [zeno.grant :as grant])
  (:import (com.sun.net.httpserver HttpServer HttpHandler HttpExchange)
           (java.net InetSocketAddress)
           (java.util.concurrent Executors ThreadFactory ExecutorService)))

(def ^:private tool
  {:name "eval"
   :description (str "Evaluate a Clojure form against the granted capabilities "
                     "(deny-by-default; only granted symbols exist). Call (tools) "
                     "and (context) to see the grant.")
   :inputSchema {:type "object"
                 :properties {:code {:type "string" :description "Clojure to evaluate"}}
                 :required ["code"]}})

(defn- handle-rpc [ctx on-eval {:strs [id method params]}]
  (case method
    "initialize" {:jsonrpc "2.0" :id id
                  :result {:protocolVersion "2024-11-05"
                           :capabilities {:tools {}}
                           :serverInfo {:name "zeno" :version "0.1"}}}
    "notifications/initialized" nil
    "ping" {:jsonrpc "2.0" :id id :result {}}
    "tools/list" {:jsonrpc "2.0" :id id :result {:tools [tool]}}
    "tools/call"
    (if (= "eval" (get params "name"))
      (let [code (get-in params ["arguments" "code"])
            t0   (System/currentTimeMillis)
            [ok? out] (try [true (pr-str (grant/eval-str ctx code))]
                           (catch Throwable t [false (str "ERROR: " (.getMessage t))]))
            ms   (- (System/currentTimeMillis) t0)]
        (when on-eval (on-eval {:code code :ok? ok? :result out :ms ms}))
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

(defn- handler [spec-fn on-eval]
  (proxy [HttpHandler] []
    (handle [^HttpExchange ex]
      (try
        (let [req  (json/read-str (slurp (.getRequestBody ex)))
              ctx  (grant/build (spec-fn))          ; rebuilt per request (live)
              resp (handle-rpc ctx on-eval req)]
          (if resp
            (write-json! ex 200 resp)
            (do (.sendResponseHeaders ex 202 -1) (.close (.getResponseBody ex)))))
        (catch Throwable t
          (write-json! ex 200 {:jsonrpc "2.0" :id nil
                               :error {:code -32603 :message (.getMessage t)}})))
      nil)))

(def ^:private daemon-factory
  (reify ThreadFactory
    (newThread [_ r] (doto (Thread. r) (.setDaemon true)))))

(defn start!
  "Start the gateway. opts:
     :host :port  gateway bind (default 127.0.0.1:7777)
     :roles       map of role-keyword -> (fn [] grant-spec)
     :on-eval     optional (fn [{:code :ok? :result :ms}]) trace hook
   Returns {:server :executor :gateway-url}. Executor threads are daemon so a
   one-shot host exits cleanly; call stop! to release for good."
  [{:keys [host port roles on-eval] :or {host "127.0.0.1" port 7777}}]
  (let [srv  (HttpServer/create (InetSocketAddress. ^String host (int port)) 0)
        pool (Executors/newFixedThreadPool 4 daemon-factory)]
    (doseq [[role spec-fn] roles]
      (.createContext srv (str "/mcp/" (name role)) (handler spec-fn on-eval)))
    (.setExecutor srv pool)
    (.start srv)
    (println (str "zeno image | gateway http://" host ":" port "/mcp/{"
                  (str/join "," (map name (keys roles))) "}"))
    {:server srv :executor pool :gateway-url (str "http://" host ":" port)}))

(defn stop! [{:keys [server executor]}]
  (when server (.stop ^HttpServer server 0))
  (when executor (.shutdownNow ^ExecutorService executor))
  :stopped)
