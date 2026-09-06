(ns researcher.embed
  "Embeddings via OpenRouter /v1/embeddings (OpenAI-compatible), metered."
  (:require [researcher.http :as http]
            [researcher.budget :as budget]))

(defn- est-tokens [texts]
  (int (/ (reduce + 0 (map count texts)) 4)))  ; ~4 chars/token fallback

(defn embed-texts
  "Return a vector of embedding vectors (vec of doubles) for the given texts.
   Records token/cost usage and enforces cfg :limits caps (throws if exceeded)."
  [cfg texts]
  (let [key (System/getenv (get-in cfg [:embed :api-key-env]))
        _   (when (nil? key)
              (throw (ex-info "missing OpenRouter key in env"
                              {:env (get-in cfg [:embed :api-key-env])})))
        url (str (get-in cfg [:embed :base-url]) "/embeddings")
        {:keys [status body]}
        (http/json-request {:method :post :url url
                            :headers {"Authorization" (str "Bearer " key)}
                            :json {:model (get-in cfg [:embed :model])
                                   :input (vec texts)}})]
    (if (= 200 status)
      (let [tokens (or (get-in body ["usage" "total_tokens"])
                       (get-in body ["usage" "prompt_tokens"])
                       (est-tokens texts))
            price  (get-in cfg [:embed :price-per-token] 0.0)]
        (budget/add! :embed {:tokens tokens :usd (* tokens price)})
        (budget/check! (:limits cfg))
        (mapv #(vec (get % "embedding")) (get body "data")))
      (throw (ex-info "embed failed" {:status status :body body})))))

(defn embed-batched
  "Embed texts in bounded batches (cap enforced between batches)."
  ([cfg texts] (embed-batched cfg texts 64))
  ([cfg texts n]
   (vec (mapcat #(embed-texts cfg %) (partition-all n texts)))))
