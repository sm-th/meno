(ns researcher.search
  "Web search via Tavily (legit, agent-optimized, free tier). Returns only
   {:title :url :snippet}; page content is fetched separately (Jina reader)."
  (:require [researcher.http :as http]))

(defn web
  [cfg query]
  (let [key (System/getenv (get-in cfg [:search :api-key-env]))
        {:keys [status body]}
        (http/json-request {:method :post
                            :url (get-in cfg [:search :endpoint])
                            :json {:api_key key
                                   :query query
                                   :max_results (get-in cfg [:search :max-results] 5)}})]
    (if (= 200 status)
      (mapv (fn [r] {:title (get r "title") :url (get r "url") :snippet (get r "content")})
            (get body "results"))
      (throw (ex-info "tavily search failed" {:status status :body body})))))
