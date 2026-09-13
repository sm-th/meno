(ns publisher.zulip
  "Zulip source adapter — the #blog stream is the input. A new post is a topic
   that is neither resolved (✔, a human closed it) nor already carries the
   publisher's reaction. Publishing sends replies into the thread and adds the
   reaction (📢) — it does NOT resolve, so the thread stays open for the
   researcher. Talks to the Zulip REST API with HTTP Basic auth (bot email : key).

   Builds the generic :source port {:list-new :reply! :mark-published!}, so
   another bus (Discourse, …) can be swapped in by providing the same shape."
  (:require [shared.http :as http]
            [clojure.data.json :as json]
            [clojure.string :as str])
  (:import (java.util Base64)
           (java.net URLEncoder)
           (java.time Instant)))

(def ^:private done-mark "\u2714 ")            ; ✔ — Zulip's resolved-topic prefix

(defn- basic [email api-key]
  (str "Basic " (.encodeToString (Base64/getEncoder)
                                 (.getBytes (str email ":" api-key) "UTF-8"))))

(defn- url [site path] (str site "/api/v1" path))

(defn- qs [query]
  (when (seq query)
    (str "?" (str/join "&" (map (fn [[k v]]
                                  (str (name k) "=" (URLEncoder/encode (str v) "UTF-8")))
                                query)))))

(defn- GET [{:keys [site email api-key]} path query]
  (:body (http/request {:method  :get
                        :url     (url site (str path (qs query)))
                        :headers {"Authorization" (basic email api-key)}
                        :timeout 60})))

(defn- send-form [{:keys [site email api-key]} method path form]
  (let [{:keys [status body]}
        (http/request {:method  method
                       :url     (url site path)
                       :headers {"Authorization" (basic email api-key)}
                       :form    form
                       :timeout 60})]
    (when-not (and (= 200 status) (= "success" (:result body)))
      (throw (ex-info "zulip: request failed" {:status status :path path :body body})))
    body))

(defn- stream-id [cfg stream]
  (:stream_id (GET cfg "/get_stream_id" {:stream stream})))

(defn- topics [cfg sid]
  (:topics (GET cfg (str "/users/me/" sid "/topics") nil)))

(defn- first-message
  "Oldest message in a topic = the note. `apply_markdown=false` returns the raw
   Markdown the author wrote (not rendered HTML)."
  [cfg stream topic]
  (let [narrow (json/write-str [{:operator "stream" :operand stream}
                                {:operator "topic"  :operand topic}])
        res (GET cfg "/messages" {:anchor "oldest" :num_before 0 :num_after 1
                                  :apply_markdown false :narrow narrow})]
    (first (:messages res))))

(defn adapter
  "Build the :source port from cfg {:site :email :api-key :stream :skip
   :published-emoji}."
  [{:keys [stream skip] :or {stream "blog"} :as cfg}]
  (let [skip  (set skip)
        emoji (get cfg :published-emoji "loudspeaker")]
    {:list-new
     (fn []
       (let [sid (stream-id cfg stream)]
         (->> (topics cfg sid)
              (remove #(str/starts-with? (str (:name %)) done-mark))   ; resolved by a human
              (remove #(skip (:name %)))
              (keep (fn [{:keys [name]}]
                      (when-let [m (first-message cfg stream name)]
                        (when-not (some #(= emoji (:emoji_name %)) (:reactions m))
                          {:id      (:id m)
                           :topic   name
                           :stream  stream
                           :content (:content m)
                           :at      (Instant/ofEpochSecond (:timestamp m))}))))
              vec)))

     :reply!
     (fn [post text]
       (send-form cfg :post "/messages"
                  {:type "stream" :to (:stream post) :topic (:topic post) :content text}))

     :mark-published!
     (fn [post]
       (send-form cfg :post (str "/messages/" (:id post) "/reactions")
                  {:emoji_name emoji}))}))
