(ns researcher.linkwarden
  "Linkwarden Cloud REST: durable store for references. Also the source of
   readable text: fetch = create a link, then wait for Linkwarden's own
   readability archive (no external fallback)."
  (:require [researcher.http :as http]
            [clojure.string :as str]))

(defn- base [cfg] (System/getenv (get-in cfg [:linkwarden :url-env])))
(defn- H    [cfg] {"Authorization" (str "Bearer " (System/getenv (get-in cfg [:linkwarden :token-env])))})

(defn collections [cfg]
  (get (:body (http/json-request {:method :get
                                  :url (str (base cfg) "/api/v1/collections")
                                  :headers (H cfg)}))
       "response"))

(defn links
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
  "Return {:id n} for the named collection, creating it under the existing owner
   if missing."
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
  "Save a URL. opts: :name :tags [str] :collection {:id n} (omit for Unorganized).
   Returns raw {:status :body}."
  [cfg {:keys [url name tags collection]}]
  (http/json-request {:method :post
                      :url (str (base cfg) "/api/v1/links")
                      :headers (H cfg)
                      :json (cond-> {:url url}
                              collection (assoc :collection collection)
                              name       (assoc :name name)
                              tags       (assoc :tags (mapv #(hash-map :name %) tags)))}))

(defn readability
  "Readable text for a link id, or nil while the archive is still processing."
  [cfg id]
  (let [{:keys [status body]}
        (http/json-request {:method :get
                            :url (str (base cfg) "/api/v1/archives/" id "?format=readability")
                            :headers (H cfg)})]
    (when (and (= 200 status) (string? body) (not (str/blank? body)))
      body)))

(defn fetch-readable!
  "Create a link (opts: :collection :tags :name), then BLOCK polling Linkwarden's
   readability archive until text is ready. No fallback. Returns {:id :text}."
  [cfg url {:keys [timeout-ms poll-ms] :or {timeout-ms 180000 poll-ms 3000} :as opts}]
  (let [r  (create-link cfg (assoc opts :url url))
        id (get-in r [:body "response" "id"])
        deadline (+ (System/currentTimeMillis) timeout-ms)]
    (when-not id (throw (ex-info "linkwarden create-link failed" {:url url :resp r})))
    (loop []
      (if-let [txt (readability cfg id)]
        {:id id :text txt}
        (if (< (System/currentTimeMillis) deadline)
          (do (Thread/sleep poll-ms) (recur))
          (throw (ex-info "linkwarden readability timeout" {:url url :id id})))))))
