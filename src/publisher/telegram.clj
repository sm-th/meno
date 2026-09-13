(ns publisher.telegram
  "Telegram channel adapter — repost a post's text to a channel via the Bot API.
   No images, no splitting yet: one sendMessage, bare URLs auto-link."
  (:require [shared.http :as http]
            [clojure.string :as str]))

(defn- api [token method]
  (str "https://api.telegram.org/bot" token "/" method))

(defn send-post!
  "Send `text` to the channel. cfg:
     :token        bot token (from @BotFather)
     :chat-id      \"@channel\" (public) or -100… (private)
     :username     channel username for t.me permalinks (public channels)
     :disable-preview        default true
     :disable-notification   default false
   Returns {:message-id int :url permalink-or-nil}."
  [{:keys [token chat-id username disable-preview disable-notification]
    :or   {disable-preview true}} text]
  (let [{:keys [status body]}
        (http/request {:method  :post
                       :url     (api token "sendMessage")
                       :json    (cond-> {:chat_id chat-id :text text}
                                  disable-preview      (assoc :disable_web_page_preview true)
                                  disable-notification (assoc :disable_notification true))
                       :timeout 60})]
    (when-not (and (= 200 status) (:ok body))
      (throw (ex-info "telegram: sendMessage failed" {:status status :body body})))
    (let [mid (get-in body [:result :message_id])]
      {:message-id mid
       :url (when username
              (str "https://t.me/" (str/replace username #"^@" "") "/" mid))})))
