(ns publisher.telegram
  "Telegram channel adapter — repost a post to a channel as a Rich Message
   (Bot API 10.1+): a heading for the title, one block per paragraph, and a footer
   with the site link. No images, no splitting."
  (:require [shared.http :as http]
            [publisher.richmessage :as rich]
            [clojure.string :as str]))

(defn- api [token method]
  (str "https://api.telegram.org/bot" token "/" method))

(defn send-post!
  "Send a post {:title :body :site-url} as a Rich Message. cfg:
     :token        bot token
     :chat-id      \"@channel\" or -100…
     :username     channel username for t.me permalinks
     :disable-notification   default false
   Returns {:message-id int :url permalink-or-nil}."
  [{:keys [token chat-id username disable-notification]} post]
  (let [resp (http/request {:method  :post
                            :url     (api token "sendRichMessage")
                            :json    {:chat_id      chat-id
                                      :rich_message (rich/build (:title post) (:body post) (:site-url post))
                                      :disable_notification (boolean disable-notification)}
                            :timeout 60})
        {:keys [status] rb :body} resp]
    (when-not (and (= 200 status) (:ok rb))
      (throw (ex-info "telegram: sendRichMessage failed" {:status status :body rb})))
    (let [mid (get-in rb [:result :message_id])]
      {:message-id mid
       :url (when username
              (str "https://t.me/" (str/replace username #"^@" "") "/" mid))})))
