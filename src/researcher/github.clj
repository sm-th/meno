(ns researcher.github
  "GitHub issues client (REST) for the task queue. Token from env (GH_TOKEN)."
  (:require [researcher.http :as http]))

(def ^:private api "https://api.github.com")

(defn- H [cfg]
  {"Authorization" (str "Bearer " (System/getenv (get-in cfg [:github :token-env])))
   "Accept" "application/vnd.github+json"
   "X-GitHub-Api-Version" "2022-11-28"
   "User-Agent" "researcher"})

(defn- repo [cfg] (get-in cfg [:github :repo]))

(defn open-issues
  "Open issues (excluding PRs, which the issues endpoint also returns)."
  [cfg]
  (let [{:keys [status body]}
        (http/json-request {:method :get
                            :url (str api "/repos/" (repo cfg) "/issues?state=open&per_page=100")
                            :headers (H cfg)})]
    (if (= 200 status)
      (remove #(contains? % "pull_request") body)
      (throw (ex-info "github open-issues failed" {:status status :body body})))))

(defn create-issue
  "Create an issue. opts: :title (req) :body :labels [str]."
  [cfg {:keys [title body labels]}]
  (let [{:keys [status body] :as r}
        (http/json-request {:method :post
                            :url (str api "/repos/" (repo cfg) "/issues")
                            :headers (H cfg)
                            :json (cond-> {:title title}
                                    body   (assoc :body body)
                                    labels (assoc :labels labels))})]
    (if (#{200 201} status)
      body
      (throw (ex-info "github create-issue failed" {:status status :body body})))))

(defn create-pr!
  "Open a pull request. opts: :title :head (branch) :base :body. Idempotent: if a PR
   already exists for :head (GitHub 422), return the existing open PR instead of
   failing - a re-run reuses its per-issue branch and its PR."
  [cfg {:keys [title head base body]}]
  (let [{:keys [status body]}
        (http/json-request {:method :post
                            :url (str api "/repos/" (repo cfg) "/pulls")
                            :headers (H cfg)
                            :json {:title title :head head :base base :body body}})]
    (cond
      (#{200 201} status) body
      (= 422 status)
      (let [owner (re-find #"^[^/]+" (repo cfg))
            {s :status b :body}
            (http/json-request {:method :get
                                :url (str api "/repos/" (repo cfg)
                                          "/pulls?state=open&head=" owner ":" head)
                                :headers (H cfg)})]
        (if (and (= 200 s) (seq b))
          (first b)
          (throw (ex-info "github create-pr failed" {:status status :body body}))))
      :else
      (throw (ex-info "github create-pr failed" {:status status :body body})))))

(defn open-prs
  "Open pull requests as raw maps (head.ref, number, title, ...)."
  [cfg]
  (let [{:keys [status body]}
        (http/json-request {:method :get
                            :url (str api "/repos/" (repo cfg) "/pulls?state=open&per_page=100")
                            :headers (H cfg)})]
    (if (= 200 status)
      body
      (throw (ex-info "github open-prs failed" {:status status :body body})))))

(defn get-issue
  "Fetch a single issue (for its body/rationale/acceptance)."
  [cfg number]
  (let [{:keys [status body]}
        (http/json-request {:method :get
                            :url (str api "/repos/" (repo cfg) "/issues/" number)
                            :headers (H cfg)})]
    (if (= 200 status)
      body
      (throw (ex-info "github get-issue failed" {:status status :body body})))))

(defn comment-issue!
  "Post a comment on an issue (used to stream the worker's eval trace for review)."
  [cfg number body]
  (let [{:keys [status body]}
        (http/json-request {:method :post
                            :url (str api "/repos/" (repo cfg) "/issues/" number "/comments")
                            :headers (H cfg)
                            :json {:body body}})]
    (if (#{200 201} status)
      body
      (throw (ex-info "github comment failed" {:status status :body body})))))

(defn update-comment!
  "Edit an existing issue comment — live-updates the worker's eval trace."
  [cfg comment-id body]
  (let [{:keys [status body]}
        (http/json-request {:method :patch
                            :url (str api "/repos/" (repo cfg) "/issues/comments/" comment-id)
                            :headers (H cfg)
                            :json {:body body}})]
    (if (= 200 status)
      body
      (throw (ex-info "github update-comment failed" {:status status :body body})))))

(defn close-issue!
  "Close an issue (used to discard a stale/denied task)."
  [cfg number]
  (let [{:keys [status body]}
        (http/json-request {:method :patch
                            :url (str api "/repos/" (repo cfg) "/issues/" number)
                            :headers (H cfg)
                            :json {:state "closed"}})]
    (if (= 200 status)
      body
      (throw (ex-info "github close-issue failed" {:status status :body body})))))

(defn update-issue!
  "Edit an existing issue's :title/:body/:labels (PATCH). Used to enrich a queued
   task in place instead of filing a duplicate."
  [cfg number {:keys [title body labels]}]
  (let [resp (http/json-request {:method :patch
                                 :url (str api "/repos/" (repo cfg) "/issues/" number)
                                 :headers (H cfg)
                                 :json (cond-> {}
                                         title  (assoc :title title)
                                         body   (assoc :body body)
                                         labels (assoc :labels labels))})]
    (if (= 200 (:status resp))
      (:body resp)
      (throw (ex-info "github update-issue failed" resp)))))

(defn merged-prs
  "Merged PRs, newest first: [{:number :title :url :sha :merged-at}]. Scans the most
   recent closed PRs (bounded) and keeps only merged ones."
  [cfg]
  (let [{:keys [status body]}
        (http/json-request {:method :get
                            :url (str api "/repos/" (repo cfg)
                                      "/pulls?state=closed&sort=updated&direction=desc&per_page=100")
                            :headers (H cfg)})]
    (if (= 200 status)
      (->> body
           (filter #(get % "merged_at"))
           (map (fn [p] {:number (get p "number") :title (get p "title")
                         :url (get p "html_url") :sha (get p "merge_commit_sha")
                         :merged-at (get p "merged_at")}))
           (sort-by :merged-at) reverse vec)
      (throw (ex-info "github merged-prs failed" {:status status :body body})))))

(defn pr-files
  "Files a PR changed: [{:path :status}], status ∈ added|modified|removed|renamed."
  [cfg number]
  (let [{:keys [status body]}
        (http/json-request {:method :get
                            :url (str api "/repos/" (repo cfg) "/pulls/" number "/files?per_page=100")
                            :headers (H cfg)})]
    (if (= 200 status)
      (mapv (fn [f] {:path (get f "filename") :status (get f "status")}) body)
      (throw (ex-info "github pr-files failed" {:status status :body body})))))
