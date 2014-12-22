(ns clj-slack-client.core
  (:gen-class)
  (:require [cheshire.core :as json])
  (:require [manifold.stream :as stream])
  (:require [manifold.deferred])
  (:require [byte-streams])
  (:require [aleph.http :as aleph]))


(def ^:dynamic *websocket-stream* nil)


;
; environment
;

(def team-state (atom nil))


(defn get-team-user
  [user-id]
  (->> @team-state
       :users
       (filter #(= (:id %) user-id))
       first))


;
; messaging
;

(defn message-json
  [channel text]
  (json/encode {:id 1
                :type "message"
                :channel channel
                :text text}))

(def ping-json
  (json/encode {:id 1
                :type "ping"}))



(defn send-to-websocket
  [data-json]
  (stream/put! *websocket-stream* data-json))


(defn say-message
  [message-json]
  (send-to-websocket message-json))


(defmulti handle-event :type)


(defmethod handle-event "message"
  [event]
  (let [user-id (:user event)
        user (get-team-user user-id)
        self-id (:id (:self @team-state))
        channel-id (:channel event)]
    (when (and (not= user-id self-id)
               (not (:is_bot user)))
      (say-message (message-json channel-id "That's what she said")))))


(defmethod handle-event "channel_joined"
  [event]
  (swap! team-state #(assoc-in % [:channels] (conj (:channels %) (:channel event)))))


(defmethod handle-event :default
  [event]
  nil)


(defn handle-event-json
  [event-json]
  (let [event (json/parse-string event-json true)
        event-type (:type event)]
    (when (not= event-type "pong") (println event))
    (handle-event event)))


;
; connectivity
;

(def slack-api-base-url "https://slack.com/api")
(def rtm-start-base-url (str slack-api-base-url "rtm.start"))

(def abot-api-token "xoxb-3215140999-UuVgqNVwxMDcWNrVeoOMMtxw")
(def someotherbot-api-token "xoxb-3246812512-FRBtlsTndTc2fGEhwq1rOhcD")
(def tonyvanriet-api-token "xoxp-3215134233-3215134235-3216767432-ca2d3d")


(def heartbeating (atom false))


(defn start-ping
  []
  (swap! heartbeating (fn [_] true))
  (future
    (loop []
      (Thread/sleep 5000)
      (send-to-websocket ping-json)
      (when @heartbeating (recur)))))


(defn stop-ping
  []
  (swap! heartbeating (fn [_] false)))


(defn call-slack-web-api
  ([method-name]
   (call-slack-web-api method-name {}))
  ([method-name params]
   (let [method-url-base (str slack-api-base-url "/" method-name)]
     @(aleph/get method-url-base {:query-params params}))))


(defn store-team-state
  [rtm-start-response-body]
  (swap! team-state (fn [_] rtm-start-response-body)))


(defn call-rtm-start
  [api-token]
  (let [response (call-slack-web-api "rtm.start" {:token api-token})
        response-body-bytes (:body response)
        response-body-json (byte-streams/to-string response-body-bytes)
        response-body (json/parse-string response-body-json true)]
    response-body))


(defn connect
  ([]
   (connect abot-api-token))
  ([api-token]
   (let [response-body (call-rtm-start api-token)
         ws-url (:url response-body)
         ws-stream @(aleph/websocket-client ws-url)]
     (alter-var-root (var *websocket-stream*) (fn [_] ws-stream))
     (store-team-state response-body)
     (start-ping)
     (stream/consume handle-event-json *websocket-stream*))))


(defn disconnect
  []
  (stop-ping)
  (stream/close! *websocket-stream*))






