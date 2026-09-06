(ns researcher.linkwarden
  "Linkwarden Cloud REST: durable store for the references our notes cite."
  (:require [researcher.http :as http]))

(defn- base [cfg] (System/getenv (get-in cfg [:linkwarden :url-env])))
(defn- H    [cfg] {"Authorization" (str "Bearer " (System/getenv (get-in cfg [:linkwarden :token-env])))})

(defn collections [cfg]
  (get (:body (http/json-request {:method :get
                                  :url (str (base cfg) "/api/v1/collections")
                                  :headers (H cfg)}))
       "response"))

(defn links
  "List links, optionally filtered to a collection id."
  ([cfg] (links cfg nil))
  ([cfg collection-id]
   (get (:body (http/json-request {:method :get
                                   :url (str (base cfg) "/api/v1/links"
                                             (when collection-id (str "?collectionId=" collection-id)))
                                   :headers (H cfg)}))
        "response")))

(defn get-link [cfg id]
  (:body (http/json-request {:method :get
                             :url (str (base cfg) "/api/v1/links/" id)
                             :headers (H cfg)})))

(defn delete-collection [cfg id]
  (http/json-request {:method :delete
                      :url (str (base cfg) "/api/v1/collections/" id)
                      :headers (H cfg)}))

(defn find-or-create-collection
  "Return {:id n} for the named collection, creating it under the existing
   owner if missing."
  [cfg name]
  (let [cs  (collections cfg)
        hit (first (filter #(= (get % "name") name) cs))]
    (if hit
      {:id (get hit "id")}
      (let [owner (get (first cs) "ownerId")
            r (http/json-request {:method :post
                                  :url (str (base cfg) "/api/v1/collections")
                                  :headers (H cfg)
                                  :json {:name name :ownerId owner}})]
        {:id (get-in r [:body "response" "id"])}))))

(defn create-link
  "Save a URL to Linkwarden. opts: :name :tags [str] :collection {:id n}.
   Returns the raw {:status :body} so callers can check success."
  [cfg {:keys [url name tags collection]}]
  (http/json-request {:method :post
                      :url (str (base cfg) "/api/v1/links")
                      :headers (H cfg)
                      :json (cond-> {:url url :collection collection}
                              name (assoc :name name)
                              tags (assoc :tags (mapv #(hash-map :name %) tags)))}))
