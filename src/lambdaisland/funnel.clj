(ns lambdaisland.funnel
  (:gen-class)
  (:require [cognitect.transit :as transit])
  (:import (com.cognitect.transit DefaultReadHandler
                                  WriteHandler)
           (java.io ByteArrayInputStream
                    ByteArrayOutputStream)
           (java.net InetSocketAddress)
           (java.nio ByteBuffer)
           (org.java_websocket WebSocket
                               WebSocketAdapter)
           (org.java_websocket.drafts Draft_6455)
           (org.java_websocket.handshake Handshakedata)
           (org.java_websocket.handshake ClientHandshake)
           (org.java_websocket.server DefaultWebSocketServerFactory
                                      DefaultSSLWebSocketServerFactory
                                      WebSocketServer)))

(set! *warn-on-reflection* true)

;; Arbitrary high ports. I hope nobody was using these, they are ours now. This
;; is where clients expect to find us.

(def ws-port  44220)
(def wss-port 44221)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Transit

;; Pass arbitrary tagged values through as-is
(deftype TaggedValue [tag rep])

(def tagged-read-handler
  (reify DefaultReadHandler
    (fromRep [_ tag rep]
      (TaggedValue. tag rep))))

(def tagged-write-handler
  (reify WriteHandler
    (tag [_ tv]
      (.-tag ^TaggedValue tv))
    (rep [_ tv]
      (.-rep ^TaggedValue tv))
    (stringRep [_ _])
    (getVerboseHandler [_])))

(defn to-transit [value]
  (try
    (let [out (ByteArrayOutputStream. 4096)
          writer (transit/writer out :json {:handlers {TaggedValue tagged-write-handler}})]
      (transit/write writer value)
      (.toString out))
    (catch Exception e
      [::error e])))

(defn from-transit [^String transit]
  (try
    (let [in (ByteArrayInputStream. (.getBytes transit))
          reader (transit/reader in :json {:default-handler tagged-read-handler})]
      (transit/read reader))
    (catch Exception e
      [::error e])))

(defn maybe-error [e]
  (when (and (vector? e) (= ::error (first e)))
    (second e)))

(def ws-factory (DefaultWebSocketServerFactory.))

(defn websocket-server
  ^WebSocketServer
  [{:keys [event-handler host port decoder-count]
    :or   {decoder-count (.. Runtime getRuntime availableProcessors)}}]
  (proxy [WebSocketServer] [^InetSocketAddress
                            (if host
                              (InetSocketAddress. ^String host ^long port)
                              (InetSocketAddress. port))
                            ^Integer
                            decoder-count]
    (onOpen [^WebSocket conn ^ClientHandshake handshake]
      (event-handler
       {:event        :ws/open
        :ws/conn      conn
        :ws/handshake handshake}))
    (onClose [^WebSocket conn code ^String reason remote?]
      (event-handler
       {:event   :ws/close
        :ws/conn conn
        :code    code
        :reason  reason
        :remote? remote?}))
    (onMessage [^WebSocket conn ^String message]
      (event-handler
       {:event   :ws/message
        :ws/conn conn
        :message (from-transit message)}))
    (onError [^WebSocket conn ^Exception ex]
      (event-handler
       {:event     :ws/error
        :ws/conn   conn
        :exception ex}))
    (onStart []
      (event-handler
       {:event :ws/start}))))

(defn -main [& args]
  (.start (websocket-server {:port ws-port
                             :event-handler prn}))
  )
