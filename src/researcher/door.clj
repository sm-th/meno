(ns researcher.door
  "zeno-style SCI 'door': the agent reaches the shared image through ONE eval;
   only GRANTED symbols exist (SCI is deny-by-default). The agent composes our
   capabilities in Clojure (CodeAct) instead of one MCP round-trip per step.

   The grant is BRANCH-SCOPED: given a wiki working copy + branch, write ops
   commit straight to that branch. Read ops span the whole evidence store
   (Qdrant) and the knowledge graph (markdown). Security is a bonus (worker runs
   in an isolated microVM); the win is ergonomics."
  (:require [sci.core :as sci]
            [researcher.index :as index]
            [researcher.reader :as reader]
            [researcher.graph :as graph]
            [researcher.wiki :as wiki]))

(defn- hit->clj [h]
  (let [p (get h "payload")]
    {:score (get h "score") :kind (get p "kind") :title (get p "title")
     :url (get p "url") :source (get p "source")}))

(defn grant
  "Build the SCI context. world = {:wiki-repo path :branch name} enables writes."
  [cfg {:keys [wiki-repo branch]}]
  (let [w (when (and wiki-repo branch) (wiki/writer cfg wiki-repo branch))
        read-fns
        {'recall  (fn [q k] (mapv hit->clj (index/recall cfg q k)))
         'fetch   (fn [url] (reader/readable url))
         'central (fn [n] (graph/central (graph/load-graph cfg) n))
         'reference-frequency (fn [] (graph/reference-frequency (graph/load-graph cfg)))
         'context (fn [] {:model (get-in cfg [:omp :model]) :branch branch
                          :grant (vec (concat '[recall fetch central reference-frequency context]
                                              (when w '[put-concept! put-reference!])))})}
        write-fns
        (when w
          {'put-concept!   (fn [page] (wiki/put-page! w (assoc page :type :concept)))
           'put-reference! (fn [page] (wiki/put-page! w (assoc page :type :reference)))})]
    (sci/init {:namespaces {'user (merge read-fns write-fns)}})))

(defn eval-str
  "Evaluate agent code against the grant. 2-arity = read-only; 3-arity binds a
   write world {:wiki-repo :branch}."
  ([cfg code] (eval-str cfg {} code))
  ([cfg world code] (sci/eval-string* (grant cfg world) code)))

(defn demo
  "Prove the door: granted symbols work, non-granted (slurp) do not exist."
  [cfg]
  (println "context =>" (eval-str cfg "(context)"))
  (println "recall  =>" (eval-str cfg "(mapv :title (recall \"microVM sandbox\" 3))"))
  (println "slurp   =>"
           (try (eval-str cfg "(slurp \"/etc/passwd\")")
                (catch Throwable t (str "BLOCKED: " (.getMessage t))))))


(defn demo-write
  "Prove branch-scoped compose: ONE eval does recall (read) + put-concept! (write
   committed to `branch`)."
  [cfg wiki-repo branch]
  (eval-str cfg {:wiki-repo wiki-repo :branch branch}
    "(let [hits (recall \"sandbox\" 3)] (put-concept! {:title \"Sandboxing\" :description \"iso\" :tags [\"security\"] :body (str \"Isolating untrusted code. Recall says: \" (pr-str (mapv :title hits))) :sources [\"https://example.com\"]}))"))