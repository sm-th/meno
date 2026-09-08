(ns researcher.qdrant
  "Managed Qdrant Cloud REST client (collections, upsert, search)."
  (:require [researcher.http :as http]))

(defn- base    [cfg] (System/getenv (get-in cfg [:qdrant :url-env])))
(defn- api-key [cfg] (System/getenv (get-in cfg [:qdrant :api-key-env])))
(defn- coll    [cfg] (get-in cfg [:qdrant :collection]))
(defn- H       [cfg] {"api-key" (api-key cfg)})

(defn ensure-collection!
  "Create the collection if missing (idempotent PUT)."
  [cfg dim]
  (http/json-request {:method :put
                      :url (str (base cfg) "/collections/" (coll cfg))
                      :headers (H cfg)
                      :json {:vectors {:size dim :distance "Cosine"}}}))

(defn upsert!
  "points: seq of {:id uuid-string :vector [..] :payload {..}}."
  [cfg points]
  (http/json-request {:method :put
                      :url (str (base cfg) "/collections/" (coll cfg) "/points")
                      :headers (H cfg)
                      :json {:points (vec points)}}))

(defn search
  "Return hits: [{\"id\" \"score\" \"payload\"}]."
  [cfg vector limit]
  (let [{:keys [status body]}
        (http/json-request {:method :post
                            :url (str (base cfg) "/collections/" (coll cfg) "/points/search")
                            :headers (H cfg)
                            :json {:vector vector :limit limit :with_payload true}})]
    (if (= 200 status)
      (get body "result")
      (throw (ex-info "qdrant search failed" {:status status :body body})))))

(defn delete-by-filter!
  "Delete all points matching a payload filter, e.g.
   {:must [{:key \"kind\" :match {:value \"task\"}}]}."
  [cfg filter]
  (http/json-request {:method :post
                      :url (str (base cfg) "/collections/" (coll cfg) "/points/delete")
                      :headers (H cfg)
                      :json {:filter filter}}))
