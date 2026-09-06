(ns researcher.embed
  "Embeddings via OpenRouter /v1/embeddings (OpenAI-compatible)."
  (:require [researcher.http :as http]))

(defn embed-texts
  "Return a vector of embedding vectors (vec of doubles) for the given texts."
  [cfg texts]
  (let [key (System/getenv (get-in cfg [:embed :api-key-env]))
        _   (when (nil? key)
              (throw (ex-info "missing OpenRouter key in env" {:env (get-in cfg [:embed :api-key-env])})))
        url (str (get-in cfg [:embed :base-url]) "/embeddings")
        {:keys [status body]}
        (http/json-request {:method :post :url url
                            :headers {"Authorization" (str "Bearer " key)}
                            :json {:model (get-in cfg [:embed :model])
                                   :input (vec texts)}})]
    (if (= 200 status)
      (mapv #(vec (get % "embedding")) (get body "data"))
      (throw (ex-info "embed failed" {:status status :body body})))))

(defn embed-batched
  "Embed texts in batches to keep requests bounded."
  ([cfg texts] (embed-batched cfg texts 64))
  ([cfg texts n]
   (vec (mapcat #(embed-texts cfg %) (partition-all n texts)))))
