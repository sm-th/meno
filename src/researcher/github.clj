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
