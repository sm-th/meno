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
  "adapter: {:list-bots :create-bot! :list-streams :subscribe!}
   agents:  {agent-key {:chat \"Full Name\" :streams [\"blog\" …]}}
   Returns {agent-key {:email :api-key}}. (GET /bots exposes :username =
   the bot email + :api_key, but no user_id, so we read the key rather than
   rotate it.)"
  [{:keys [list-bots create-bot! list-streams subscribe!]} agents]
  (let [existing (set (list-streams))
        have0    (into {} (map (juxt :full_name identity)) (list-bots))]
    ;; 1. create any missing bots
    (doseq [[_ {:keys [chat]}] agents]
      (when-not (have0 chat)
        (create-bot! {:full-name chat :short-name (slug chat)})))
    ;; 2. re-list -> canonical records (:username = email, :api_key); subscribe by email
    (let [have (into {} (map (juxt :full_name identity)) (list-bots))]
      (into {}
            (for [[k {:keys [chat streams]}] agents]
              (let [bot     (have chat)
                    email   (:username bot)
                    present (filter existing streams)]
                (when (seq present) (subscribe! email (vec present)))
                (doseq [s (remove existing streams)]
                  (println "WARN provision:" k "→ stream" (pr-str s)
                           "does not exist; skipped (not created)"))
                [k {:email email :api-key (:api_key bot)}]))))))
