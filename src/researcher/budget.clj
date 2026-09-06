(ns researcher.budget
  "In-memory usage/cost meter with hard caps, so a run can never quietly burn
   through subscription/credit limits. State is per-process (an atom); nothing
   persisted yet.")

(defonce ^:private state (atom nil))

(defn reset-run! []
  (reset! state {:embed {:tokens 0 :usd 0.0 :calls 0}
                 :llm   {:tokens 0 :usd 0.0 :calls 0}}))

(defn- ensure! [] (when (nil? @state) (reset-run!)))

(defn add!
  "kind = :embed | :llm ; usage = {:tokens n :usd x}."
  [kind {:keys [tokens usd]}]
  (ensure!)
  (swap! state update kind
         (fn [m] (-> m
                     (update :tokens + (or tokens 0))
                     (update :usd + (or usd 0.0))
                     (update :calls inc)))))

(defn snapshot [] (ensure!) @state)

(defn check!
  "Throw if any cap in `limits` is exceeded. Keys (any may be nil/absent):
   :embed-tokens :embed-usd :llm-tokens :llm-usd :total-usd."
  [limits]
  (when limits
    (let [s @state
          over (fn [path cap] (and cap (> (get-in s path) cap)))
          total-usd (+ (get-in s [:embed :usd]) (get-in s [:llm :usd]))]
      (cond
        (over [:embed :tokens] (:embed-tokens limits))
        (throw (ex-info "embed token cap exceeded" {:limit (:embed-tokens limits) :spent (get-in s [:embed :tokens])}))
        (over [:embed :usd] (:embed-usd limits))
        (throw (ex-info "embed USD cap exceeded" {:limit (:embed-usd limits) :spent (get-in s [:embed :usd])}))
        (over [:llm :tokens] (:llm-tokens limits))
        (throw (ex-info "llm token cap exceeded" {:limit (:llm-tokens limits) :spent (get-in s [:llm :tokens])}))
        (over [:llm :usd] (:llm-usd limits))
        (throw (ex-info "llm USD cap exceeded" {:limit (:llm-usd limits) :spent (get-in s [:llm :usd])}))
        (and (:total-usd limits) (> total-usd (:total-usd limits)))
        (throw (ex-info "total USD cap exceeded" {:limit (:total-usd limits) :spent total-usd}))))))

(defn report []
  (let [s (snapshot)]
    (println (format "budget: embed %d tok / $%.5f (%d calls) | llm %d tok / $%.5f (%d calls)"
                     (get-in s [:embed :tokens]) (get-in s [:embed :usd]) (get-in s [:embed :calls])
                     (get-in s [:llm :tokens]) (get-in s [:llm :usd]) (get-in s [:llm :calls])))))
