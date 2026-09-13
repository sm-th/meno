(ns provision
  "Generic identity reconcile. Given desired agents and an identity adapter
   (owner-cred), ensure each agent's bot exists (create if missing), rotate its
   key on boot (nothing persisted — the orchestrator holds it in memory), and
   subscribe it to its declared streams (EXISTING ones only; warn on missing,
   never create). Idempotent: a re-run converges. Returns
   {agent-key -> {:email :api-key :user-id}}."
  (:require [clojure.string :as str]))

(defn- slug [s]
  (-> (str/lower-case (str s))
      (str/replace #"[^a-z0-9]+" "-")
      (str/replace #"^-+|-+$" "")))

(defn reconcile!
  "adapter: {:list-bots :create-bot! :regenerate! :list-streams :subscribe!}
   agents:  {agent-key {:chat \"Full Name\" :streams [\"blog\" …]}}"
  [{:keys [list-bots create-bot! regenerate! list-streams subscribe!]} agents]
  (let [have     (into {} (map (juxt :full_name identity)) (list-bots))
        existing (set (list-streams))]
    (into {}
          (for [[k {:keys [chat streams]}] agents]
            (let [bot     (or (have chat)
                              (create-bot! {:full-name chat :short-name (slug chat)}))
                  api-key (regenerate! (:user_id bot))          ; rotate-on-boot
                  present (filter existing streams)             ; existing only
                  missing (remove existing streams)]
              (when (seq present) (subscribe! (:email bot) (vec present)))
              (doseq [s missing]
                (println "WARN provision:" k "→ stream" (pr-str s)
                         "does not exist; skipped (not created)"))
              [k {:email (:email bot) :api-key api-key :user-id (:user_id bot)}])))))
