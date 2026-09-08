(ns researcher.mcp
  "The living researcher image, ONE process:
     - HTTP MCP gateway (single tool `eval`) at /mcp/<role> for every configured role
     - an nREPL server (connect your editor to the SAME image)
     - a stdin REPL (drive it over the process stdin)
   The HTTP handler rebuilds the grant from live code+config on every request, so
   `(require ... :reload)` / config edits take effect immediately — no restart.
   Secrets/state live here; omp sessions hold only the gateway URL."
  (:refer-clojure :exclude [run!])
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [nrepl.server :as nrepl]
            [cider.nrepl :refer [cider-nrepl-handler]]
            [researcher.config :as config]
            [researcher.grant :as grant]
            [researcher.runner :as runner]
            [researcher.task :as task]
            [researcher.process :as process])
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
              world (assoc (or @task/current {}) :role role :dry? @task/dry)
              ctx   (grant/build (config/load-config) world)
              resp  (handle-rpc ctx req)]
          (if resp
            (write-json! ex 200 resp)
            (do (.sendResponseHeaders ex 202 -1) (.close (.getResponseBody ex)))))
        (catch Throwable t
          (write-json! ex 200 {:jsonrpc "2.0" :id nil
                               :error {:code -32603 :message (.getMessage t)}})))
      nil)))

(defn ingest!
  "Per new article (call when a post is published): deterministically file a
   `role:ingest` task into Backlog. No LLM — just enqueues for your triage."
  [] (runner/file-ingest-task! (config/load-config)))

(defn dry!
  "Global preview switch: (dry! true) makes every side-effecting tool PRINT what it
   would do (full issue title+body for propose-task!) instead of touching GitHub/the
   wiki, so a whole stage runs through the REAL pipeline and is watched on screen.
   (dry! false) turns it off."
  [on?] (reset! task/dry (boolean on?)) {:dry @task/dry})

(defn run!
  "Execute the next approved (Todo) task of ANY role (or a specific issue number)
   under its own `role:<name>` tag: load that role's wiki prompt, grant its tools,
   run it, and — for writing roles — open a PR."
  ([] (run! nil))
  ([number]
   (let [cfg   (config/load-config)
         issue (runner/pick cfg number)]
     (if issue
       (runner/run-issue cfg issue)
       (println "no approved (Todo) issue" (when number (str "#" number)))))))

(def ^:private orchestrator (atom nil))
(def ^:private active (atom #{}))       ; issue numbers currently running

(defn start-loop!
  "Background orchestrator: poll the board and run approved (Todo) issues, up to
   `:orchestrator :wip` at a time (default 1), until stopped. Filing/approving
   stays manual; this drains whatever you move to Todo.
   NOTE: run context (branch) and the eval trace are process-global today, so only
   wip=1 is safe; wip>1 needs per-run isolation of task/current + task/trace."
  []
  (when-not @orchestrator
    (reset! orchestrator true)
    (future
      (println "orchestrator: draining Todo")
      (try (let [n (runner/requeue-orphans! (config/load-config))]
             (when (seq n) (println "orchestrator: re-queued orphaned In Progress ->" n)))
           (catch Throwable _ nil))
      (let [warned (atom false)]
        (while @orchestrator
          (let [cfg (config/load-config)
                wip (get-in cfg [:orchestrator :wip] 1)]
            ;; GUARD: parallel runs share task/current + task/trace, so cap at 1 in
            ;; flight regardless of :wip until that context is per-run isolated.
            (when (and (> wip 1) (not @warned))
              (println "orchestrator: :wip" wip "requested — clamped to 1 (per-run isolation not implemented yet)")
              (reset! warned true))
            (loop []
              (when (and @orchestrator (< (count @active) 1))
                (if-let [issue (try (runner/next-todo cfg @active) (catch Throwable _ nil))]
                  (do
                    (swap! active conj (:number issue))
                    (println "orchestrator: running #" (:number issue) "(" (name (runner/role-of issue)) ")")
                    (future
                      (try (runner/run-issue cfg issue)
                           (catch Throwable t (println "orchestrator error #" (:number issue) ":" (.getMessage t)))
                           (finally (swap! active disj (:number issue)))))
                    (recur))
                  ;; no approved Todo -> autonomously dereference ONE dangling [[link]]:
                  ;; the wiki's own frontier, most-wanted missing card first, one PR each.
                  (when-let [seed (try (runner/next-dangling cfg @active) (catch Throwable _ nil))]
                    (swap! active conj (:card seed))
                    (println "orchestrator: dereferencing [[" (:card seed) "]] requested by" (:refs seed))
                    (future
                      (try (runner/run-issue cfg seed)
                           (catch Throwable t (println "orchestrator deref error [[" (:card seed) "]]:" (.getMessage t)))
                           (finally (swap! active disj (:card seed)))))
                    (recur)))))
            (Thread/sleep (get-in cfg [:orchestrator :poll-ms] 15000))))))
    :started))

(defn stop-loop! [] (reset! orchestrator nil) :stopped)

(defn -main [& _]
  (let [cfg   (config/load-config)
        host  (get-in cfg [:gateway :host] "127.0.0.1")
        port  (get-in cfg [:gateway :port] 7777)
        nport (get-in cfg [:gateway :nrepl-port] 7778)
        roles (process/roles)
        srv   (HttpServer/create (InetSocketAddress. ^String host (int port)) 0)]
    (doseq [role roles]
      (.createContext srv (str "/mcp/" (name role)) (handler role)))
    (.setExecutor srv (java.util.concurrent.Executors/newFixedThreadPool 4))
    (.start srv)
    (nrepl/start-server :bind host :port nport :handler cider-nrepl-handler)
    (println (str "researcher living image | gateway http://" host ":" port
                  "/mcp/{" (str/join "," (map name roles)) "} | nrepl " host ":" nport))
    (start-loop!)
    (flush)
    (clojure.main/repl :prompt #(do (print "image=> ") (flush)))))
