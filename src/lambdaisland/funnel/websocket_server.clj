(ns lambdaisland.funnel.websocket-server
  (:gen-class
   :extends org.java_websocket.server.WebSocketServer
   :implements [java.io.Closeable clojure.lang.IDeref]
   :state handler
   :init init
   :constructors {[java.util.Map] [java.net.InetSocketAddress int]}
   :prefix "ws-")
  (:import (java.net InetSocketAddress)
           (org.java_websocket.handshake ClientHandshake)
           (org.java_websocket.server WebSocketServer)
           (org.java_websocket WebSocket)))

(defprotocol Handler
  (on-open [this ^WebSocket conn ^ClientHandshake handshake])
  (on-close [this ^WebSocket conn code ^String reason remote?])
  (on-message [this ^WebSocket conn ^String message])
  (on-error [this ^WebSocket conn ^Exception ex])
  (on-start [this server])
  (on-deref [this]))

(defn ws-init [{:keys [handler host port decoder-count]
                :or   {decoder-count (.. Runtime getRuntime availableProcessors)}
                :as   opts}]
  [[^InetSocketAddress
    (if host
      (InetSocketAddress. ^String host ^long port)
      (InetSocketAddress. port))
    (long
     decoder-count)]
   handler])

(defn ws-onOpen [this ^WebSocket conn ^ClientHandshake handshake]
  (on-open (.handler this) conn handshake))

(defn ws-onClose [this ^WebSocket conn code ^String reason remote?]
  (on-close (.handler this) conn code reason remote?))

(defn ws-onMessage [this ^WebSocket conn ^String message]
  (try
    (on-message (.handler this) conn message)
    (catch Exception e
      (on-error (.handler this) conn e))))

(defn ws-onError [this ^WebSocket conn ^Exception ex]
  (on-error (.handler this) conn ex))

(defn ws-onStart [this]
  (on-start (.handler this) this))

(defn ws-close [this]
  (.stop ^WebSocketServer this))

(defn ws-deref [this]
  (on-deref (.handler this)))

(comment
  (compile (ns-name *ns*)))
