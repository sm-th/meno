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
  (let [existing (set (list-streams))
        have0    (into {} (map (juxt :full_name identity)) (list-bots))]
    ;; 1. create any missing bots (POST /bots returns no :email, so we re-list next)
    (doseq [[_ {:keys [chat]}] agents]
      (when-not (have0 chat)
        (create-bot! {:full-name chat :short-name (slug chat)})))
    ;; 2. re-list -> canonical records (:email, :user_id); rotate + subscribe by user id
    (let [have (into {} (map (juxt :full_name identity)) (list-bots))]
      (into {}
            (for [[k {:keys [chat streams]}] agents]
              (let [bot     (have chat)
                    api-key (regenerate! (:user_id bot))          ; rotate-on-boot
                    present (filter existing streams)]
                (when (seq present) (subscribe! (:user_id bot) (vec present)))
                (doseq [s (remove existing streams)]
                  (println "WARN provision:" k "→ stream" (pr-str s)
                           "does not exist; skipped (not created)"))
                [k {:email (:email bot) :api-key api-key :user-id (:user_id bot)}]))))))
