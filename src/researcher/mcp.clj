(ns researcher.mcp
  "Stdio MCP server exposing exactly ONE tool: `eval` over the SCI door. The
   grant PROFILE and write-world come from env, so different omp roles point at
   different grants (planner vs worker):
     RESEARCHER_GRANT   planner|worker (default worker)
     RESEARCHER_WIKI_REPO / RESEARCHER_BRANCH  enable worker writes
   Only newline-delimited JSON-RPC goes to stdout; logs go to stderr."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [researcher.config :as config]
            [researcher.door :as door])
  (:gen-class))

(defn- send! [msg]
  (println (json/write-str msg))
  (flush))

(defn- reply [id result] (send! {:jsonrpc "2.0" :id id :result result}))
(defn- fail  [id code m]  (send! {:jsonrpc "2.0" :id id :error {:code code :message m}}))

(def ^:private tool
  {:name "eval"
   :description (str "Evaluate a Clojure form against the granted capabilities "
                     "(deny-by-default; only granted symbols exist). Compose "
                     "reads and writes in one form. See (context) for the grant.")
   :inputSchema {:type "object"
                 :properties {:code {:type "string" :description "Clojure to evaluate"}}
                 :required ["code"]}})

(defn -main [& _]
  (let [cfg (config/load-config)
        world {:profile (keyword (or (System/getenv "RESEARCHER_GRANT") "worker"))
               :wiki-repo (System/getenv "RESEARCHER_WIKI_REPO")
               :branch (System/getenv "RESEARCHER_BRANCH")}
        ctx (door/grant cfg world)]
    (binding [*out* (java.io.PrintWriter. System/out true)]
      (doseq [line (line-seq (java.io.BufferedReader. *in*))]
        (when-not (str/blank? line)
          (let [{:strs [id method params]} (try (json/read-str line) (catch Exception _ {}))]
            (case method
              "initialize"
              (reply id {:protocolVersion "2024-11-05"
                         :capabilities {:tools {}}
                         :serverInfo {:name "researcher-door" :version "0.1"}})
              "notifications/initialized" nil
              "ping" (reply id {})
              "tools/list" (reply id {:tools [tool]})
              "tools/call"
              (if (= "eval" (get params "name"))
                (let [code (get-in params ["arguments" "code"])
                      out (try (pr-str (door/eval-ctx ctx code))
                               (catch Throwable t (str "ERROR: " (.getMessage t))))]
                  (reply id {:content [{:type "text" :text out}]}))
                (fail id -32602 (str "unknown tool: " (get params "name"))))
              (when id (fail id -32601 (str "unknown method: " method))))))))))
