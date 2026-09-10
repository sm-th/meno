(ns zeno.spawn
  "Zeno core — spawn an ephemeral coding-agent session.

  One spawn = one task. The agent (omp) is launched in print mode with its ONLY
  tool being the eval grant, reached over the living image's MCP gateway. The
  session holds no secrets — only the gateway URL — and dies when the task ends.
  This is the `spawn` primitive: heavy reasoning/writing is delegated to a
  spawned session; the image stays thin and long-lived."
  (:require [clojure.java.shell :as sh]
            [clojure.data.json :as json]
            [clojure.java.io :as io]))

(defn- mcp-json [gateway-url role]
  (json/write-str
   {:mcpServers {:zeno {:type "http" :url (str gateway-url "/mcp/" (name role))}}}))

(defn spawn
  "Run one omp session against the grant. opts:
     :model       provider/model-id (e.g. \"opencode-go/deepseek-v4-flash\")
     :system      system prompt (the role's instructions)
     :prompt      the task input
     :gateway-url base URL of the living image's MCP gateway
     :role        grant role segment in the gateway path (default \"worker\")
   Returns {:exit :out :err}."
  [{:keys [model system prompt gateway-url role] :or {role "worker"}}]
  (let [tmp (str (System/getProperty "java.io.tmpdir") "/zeno-" (name role) "-"
                 (System/currentTimeMillis))]
    (.mkdirs (io/file tmp ".omp"))
    (spit (io/file tmp ".omp/mcp.json") (mcp-json gateway-url role))
    (sh/sh "omp" "-p" "--no-tools" "--no-session" "--no-title" "--mode=json"
           "--model" model "--cwd" tmp "--system-prompt" system "--" prompt)))
