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

(defn add-issue!
  "Add an issue (by its GraphQL node id) to the project. Lands in No Status =
   Backlog. Idempotent-ish: GitHub returns the existing item if already added."
  [cfg project-id issue-node-id]
  (-> (gql cfg "mutation($p:ID!,$c:ID!){ addProjectV2ItemById(input:{projectId:$p,contentId:$c}){ item { id } } }"
           {:p project-id :c issue-node-id})
      (get-in ["addProjectV2ItemById" "item" "id"])))

(defn items
  "All board items with their Status name and linked issue."
  [cfg project-id]
  (-> (gql cfg "query($p:ID!){ node(id:$p){ ... on ProjectV2 { items(first:100){ nodes {
                 id
                 status: fieldValueByName(name:\"Status\"){ ... on ProjectV2ItemFieldSingleSelectValue { name } }
                 content { ... on Issue { number title id url state } } } } } } }"
           {:p project-id})
      (get-in ["node" "items" "nodes"])))

(defn todo-items
  "Open issues the human moved to the approved Status (Todo) — the worker queue."
  [cfg project-id]
  (let [want (get-in cfg [:projects :approved-status])]
    (->> (items cfg project-id)
         (keep (fn [it]
                 (when-let [c (get it "content")]
                   {:item-id (get it "id")   :number (get c "number")
                    :title   (get c "title") :node-id (get c "id")
                    :url     (get c "url")   :state (get c "state")
                    :status  (get-in it ["status" "name"])})))
         (filter #(and (= want (:status %)) (= "OPEN" (:state %))))
         vec)))

(defn status-field [cfg project-id]
  (first (filter #(= "Status" (get % "name")) (fields cfg project-id))))

(defn set-status!
  "Move an item to a built-in Status option (e.g. \"In Progress\", \"Done\")."
  [cfg project-id item-id status-name]
  (let [f   (status-field cfg project-id)
        opt (first (filter #(= status-name (get % "name")) (get f "options")))]
    (gql cfg "mutation($p:ID!,$i:ID!,$f:ID!,$o:String!){ updateProjectV2ItemFieldValue(input:{projectId:$p,itemId:$i,fieldId:$f,value:{singleSelectOptionId:$o}}){ projectV2Item { id } } }"
         {:p project-id :i item-id :f (get f "id") :o (get opt "id")})))

(defn clear-status!
  "Clear an item's Status -> No Status (= Backlog). Planner files here so new
   proposals wait for human triage instead of GitHub's default -> Todo workflow."
  [cfg project-id item-id]
  (let [f (status-field cfg project-id)]
    (gql cfg "mutation($p:ID!,$i:ID!,$f:ID!){ clearProjectV2ItemFieldValue(input:{projectId:$p,itemId:$i,fieldId:$f}){ projectV2Item { id } } }"
         {:p project-id :i item-id :f (get f "id")})))

(defn delete-project! [cfg project-id]
  (gql cfg "mutation($p:ID!){ deleteProjectV2(input:{projectId:$p}){ projectV2 { id } } }"
       {:p project-id}))

(defn- user-id [cfg login]
  (-> (gql cfg "query($l:String!){ user(login:$l){ id } }" {:l login})
      (get-in ["user" "id"])))

(defn ensure-collaborator!
  "Add the human's personal account as a WRITER on the project so they can drag
   cards from their own GitHub (repo-collaboration does NOT grant project access)."
  [cfg project-id]
  (when-let [login (get-in cfg [:projects :human-login])]
    (gql cfg "mutation($p:ID!,$u:ID!){ updateProjectV2Collaborators(input:{projectId:$p,collaborators:[{userId:$u,role:WRITER}]}){ collaborators { totalCount } } }"
         {:p project-id :u (user-id cfg login)})))

(defn ensure-project!
  "Find the project by name, or create it; then ensure the human is a WRITER.
   (Linking to the repo needs repo write, which the project-only token lacks by
   design — link once in the UI.) Returns the project node {id number title}."
  [cfg]
  (let [p (or (find-project cfg)
              (create-project! cfg (get (owner cfg) "id") (get-in cfg [:projects :name])))]
    (ensure-collaborator! cfg (get p "id"))
    p))
