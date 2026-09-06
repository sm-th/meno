(ns researcher.llm
  "LLM request representation + dry-run sink. Never sends while dry-run.")

(defn request [purpose model system user]
  {:purpose purpose :model model :system system :user user})

(defn- rule [c] (apply str (repeat 66 c)))

(defn print-request [{:keys [purpose model system user]}]
  (println (rule \=))
  (println (str "LLM REQUEST  \u25B8 " purpose "    (model: " model ")"))
  (println (rule \-))
  (println "\u2500\u2500 system \u2500\u2500")
  (println system)
  (println)
  (println "\u2500\u2500 user \u2500\u2500")
  (println user)
  (println)
  (println "(dry-run: request NOT sent \u2014 LLM disabled)")
  (println))

(def ^:dynamic *sink* print-request)

(defn ask
  "Dry-run: hand the request to the sink (prints). Returns a marker."
  [req]
  (*sink* req)
  {:dry-run true :purpose (:purpose req)})
