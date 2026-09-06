(ns researcher.diag
  "In-code diagnostics (no curl-with-secrets). Run under `secretspec run` so the
   creds are in env; call from the `diag` command or a REPL."
  (:require [researcher.config :as config]
            [researcher.qdrant :as qdrant]
            [researcher.linkwarden :as lw]
            [researcher.http :as http]))

(defn cfg [] (config/load-config))

(defn qdrant-count [c]
  (let [{:keys [body]}
        (http/json-request {:method :get
                            :url (str (System/getenv (get-in c [:qdrant :url-env]))
                                      "/collections/" (get-in c [:qdrant :collection]))
                            :headers {"api-key" (System/getenv (get-in c [:qdrant :api-key-env]))}})]
    (get-in body ["result" "points_count"])))

(defn run []
  (let [c (cfg)]
    (println "== Qdrant ==")
    (println "  points:" (qdrant-count c))
    (println "== Linkwarden collections ==")
    (doseq [col (lw/collections c)]
      (println "  " (get col "id") (get col "name") "owner" (get col "ownerId")))
    (println "== Linkwarden links (default listing) ==")
    (let [ls (lw/links c)]
      (println "  count:" (count ls))
      (doseq [l (take 10 ls)] (println "  " (get l "id") (get l "url"))))))

(defn dedup-collections!
  "Keep the first collection named `name`, delete the rest (fix accidental dups)."
  [name]
  (let [c (cfg)
        cols (filter #(= (get % "name") name) (lw/collections c))
        [keep & dups] cols]
    (println "keeping" (get keep "id") "deleting" (mapv #(get % "id") dups))
    (doseq [d dups] (lw/delete-collection c (get d "id")))
    (println "remaining:" (mapv (juxt #(get % "id") #(get % "name")) (lw/collections c)))))
