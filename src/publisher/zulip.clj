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

(defn- topic-messages
  "All messages in a topic (oldest first), raw Markdown."
  [cfg stream topic]
  (let [narrow (json/write-str [{:operator "stream" :operand stream}
                                {:operator "topic"  :operand topic}])]
    (:messages (GET cfg "/messages" {:anchor "oldest" :num_before 0 :num_after 200
                                     :apply_markdown false :narrow narrow}))))

(defn register-queue!
  "Register a Zulip event queue for message events in `stream`. Returns the body
   {:queue_id :last_event_id ...}."
  [cfg stream]
  (send-form cfg :post "/register"
             {:event_types    (json/write-str ["message"])
              :narrow         (json/write-str [["stream" stream]])
              :apply_markdown "false"}))

(defn poll-events
  "Long-poll a registered queue. Blocks server-side until an event or a heartbeat,
   bounded by `timeout` s. Returns the parsed body: {:events [...]} on success, or
   {:result \"error\" :code \"BAD_EVENT_QUEUE_ID\"} if the queue expired."
  [{:keys [site email api-key]} queue-id last-event-id timeout]
  (:body (http/request {:method  :get
                        :url     (url site (str "/events"
                                                (qs {:queue_id      queue-id
                                                     :last_event_id last-event-id})))
                        :headers {"Authorization" (basic email api-key)}
                        :timeout timeout})))

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

     :find-link
     ;; A URL containing `substr` already posted as a REPLY in the thread (a prior
     ;; step's receipt) — lets a step skip itself idempotently. Scans replies only
     ;; (skips the original note, so the note's own links don't false-positive).
     (fn [post substr]
       (->> (topic-messages cfg (:stream post) (:topic post))
            rest
            (mapcat #(re-seq #"https?://[^\s)]+" (str (:content %))))
            (filter #(str/includes? % substr))
            first))

     :reply!
     (fn [post text]
       (send-form cfg :post "/messages"
                  {:type "stream" :to (:stream post) :topic (:topic post) :content text}))

     :mark-published!
     (fn [post]
       (send-form cfg :post (str "/messages/" (:id post) "/reactions")
                  {:emoji_name emoji}))

     :events!
     ;; One bounded long-poll of the event queue, held in `state` (an atom). Returns
     ;; {:wake? bool} — true when new posts may exist: a #blog message arrived, or the
     ;; queue was just (re)registered (scan the backlog once). Re-registers on expiry.
     (fn [state]
       (try
         (if (nil? @state)
           (let [{:keys [queue_id last_event_id]} (register-queue! cfg stream)]
             (reset! state {:queue queue_id :last last_event_id})
             {:wake? true})
           (let [{:keys [queue last]} @state
                 body (poll-events cfg queue last 90)]
             (if (= "error" (:result body))
               (do (reset! state nil) {:wake? true})
               (let [evs (:events body)]
                 (when (seq evs)
                   (swap! state assoc :last (reduce max last (map :id evs))))
                 {:wake? (boolean (some #(= "message" (:type %)) evs))}))))
         (catch Throwable t
           (println "!! zulip events:" (.getMessage t))
           (Thread/sleep 2000)
           {:wake? false})))}))
