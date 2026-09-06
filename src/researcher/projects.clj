(ns researcher.projects
  "GitHub Projects v2 (the approval board) via GraphQL. Uses GH_PROJECTS_TOKEN
   (classic PAT, `project` scope) — see docs/auth.md. Projects v2 are owned by a
   user/org and LINKED to the repo (they cannot be repo-owned)."
  (:require [researcher.http :as http]
            [clojure.string :as str]))

(defn- token [cfg] (System/getenv (get-in cfg [:projects :token-env])))

(defn gql
  "Run a GraphQL query/mutation; return the `data` map or throw with errors."
  [cfg query vars]
  (let [{:keys [status body]}
        (http/json-request {:method :post
                            :url (get-in cfg [:projects :graphql])
                            :headers {"Authorization" (str "Bearer " (token cfg))
                                      "User-Agent" "researcher"}
                            :json {:query query :variables vars}})]
    (if (and (= 200 status) (not (get body "errors")))
      (get body "data")
      (throw (ex-info "graphql error" {:status status :body body})))))

(defn owner [cfg]
  (-> (gql cfg "query($l:String!){ repositoryOwner(login:$l){ id __typename } }"
           {:l (get-in cfg [:projects :owner])})
      (get "repositoryOwner")))

(defn repo-id [cfg]
  (let [[o n] (str/split (get-in cfg [:projects :link-repo]) #"/")]
    (-> (gql cfg "query($o:String!,$n:String!){ repository(owner:$o,name:$n){ id } }"
             {:o o :n n})
        (get-in ["repository" "id"]))))

(defn find-project [cfg]
  (let [q "query($l:String!){ repositoryOwner(login:$l){
             ... on User         { projectsV2(first:50){ nodes { id number title } } }
             ... on Organization { projectsV2(first:50){ nodes { id number title } } } } }"
        nodes (-> (gql cfg q {:l (get-in cfg [:projects :owner])})
                  (get-in ["repositoryOwner" "projectsV2" "nodes"]))]
    (first (filter #(= (get-in cfg [:projects :name]) (get % "title")) nodes))))

(defn create-project! [cfg owner-id title]
  (-> (gql cfg "mutation($o:ID!,$t:String!){ createProjectV2(input:{ownerId:$o,title:$t}){ projectV2 { id number title } } }"
           {:o owner-id :t title})
      (get-in ["createProjectV2" "projectV2"])))

(defn link-repo! [cfg project-id repository-id]
  (gql cfg "mutation($p:ID!,$r:ID!){ linkProjectV2ToRepository(input:{projectId:$p,repositoryId:$r}){ repository { nameWithOwner } } }"
       {:p project-id :r repository-id}))

(defn fields
  "All fields of a project; single-select fields include their options."
  [cfg project-id]
  (-> (gql cfg "query($p:ID!){ node(id:$p){ ... on ProjectV2 { fields(first:30){ nodes {
                 ... on ProjectV2SingleSelectField { id name options { id name } }
                 ... on ProjectV2FieldCommon { id name } } } } } }"
           {:p project-id})
      (get-in ["node" "fields" "nodes"])))

(defn delete-project! [cfg project-id]
  (gql cfg "mutation($p:ID!){ deleteProjectV2(input:{projectId:$p}){ projectV2 { id } } }"
       {:p project-id}))

(defn ensure-project!
  "Find the project by name, or create it. (Linking to the repo needs repo write,
   which the project-only token lacks by design — link once in the UI.) Returns
   the project node {id number title}."
  [cfg]
  (or (find-project cfg)
      (create-project! cfg (get (owner cfg) "id") (get-in cfg [:projects :name]))))
