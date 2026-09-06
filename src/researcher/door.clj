(ns researcher.door
  "zeno-style SCI 'door': the agent reaches the shared image through ONE eval,
   and only the symbols we GRANT exist inside it. SCI is deny-by-default — no
   slurp, no System interop, no io unless injected — so the grant, not a
   guardrail, decides what exists. Lets the agent compose our tools in Clojure
   (batch/loop/map) instead of one MCP round-trip per step (CodeAct pattern).

   Security here is a bonus (the worker already runs in an isolated microVM);
   the primary win is ergonomics/round-trips."
  (:require [sci.core :as sci]
            [researcher.index :as index]
            [researcher.reader :as reader]))

(defn- hit->clj [h]
  (let [p (get h "payload")]
    {:score (get h "score")
     :kind  (get p "kind")
     :title (get p "title")
     :url   (get p "url")
     :source (get p "source")}))

(defn grant
  "SCI context whose `user` namespace is EXACTLY the capabilities we hand the
   agent. Nothing else (slurp/System/io/interop) is resolvable."
  [cfg]
  (sci/init
   {:namespaces
    {'user {'recall  (fn [q k] (mapv hit->clj (index/recall cfg q k)))
            'fetch   (fn [url] (reader/readable url))
            'context (fn [] {:model (get-in cfg [:omp :model])
                             :grant '[recall fetch context]})}}}))

(defn eval-str
  "Evaluate agent-supplied Clojure against the grant. Non-granted symbols do not
   exist -> error, by construction (the door, not a filter)."
  [cfg code]
  (sci/eval-string* (grant cfg) code))

(defn demo
  "Prove the door: granted symbols work, non-granted (slurp) do not exist."
  [cfg]
  (println "context =>" (eval-str cfg "(context)"))
  (println "recall  =>" (eval-str cfg "(mapv :title (recall \"microVM sandbox\" 3))"))
  (println "slurp   =>"
           (try (eval-str cfg "(slurp \"/etc/passwd\")")
                (catch Throwable t (str "BLOCKED: " (.getMessage t))))))
