(ns lambdaisland.funnel
  (:gen-class)
  (:require [cognitect.transit :as transit]
            [clojure.tools.cli :as cli]
            [clojure.java.io :as io])
  (:import (com.cognitect.transit DefaultReadHandler
                                  WriteHandler)
           (java.io ByteArrayInputStream
                    ByteArrayOutputStream
                    FileInputStream)
           (java.net InetSocketAddress)
           (java.nio ByteBuffer)
           (java.security KeyStore)
           (javax.net.ssl SSLContext
                          KeyManagerFactory)
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

(def state (atom {}))

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


(defn websocket-server
  ^WebSocketServer
  [{:keys [event-handler host port decoder-count]
    :or   {decoder-count (.. Runtime getRuntime availableProcessors)}}]
  (proxy [WebSocketServer] [^InetSocketAddress
                            (doto (if host
                                    (InetSocketAddress. ^String host ^long port)
                                    (InetSocketAddress. port)) prn)
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

(defn ssl-context [keystore password]
  (let [key-manager (KeyManagerFactory/getInstance (KeyManagerFactory/getDefaultAlgorithm))
        key-store (KeyStore/getInstance (KeyStore/getDefaultType))
        pw (.toCharArray ^String password)]

    (with-open [fs (io/input-stream keystore)]
      (.load key-store fs pw))

    (.init key-manager key-store pw)

    (doto (SSLContext/getInstance "TLS")
      (.init (.getKeyManagers key-manager) nil nil))))

(def option-specs
  [[nil "--keystore FILE" "Location of the keystore.jks file, necessary to enable SSL"]
   [nil "--keystore-password PASSWORD" "Password to load the keystore, defaults to \"funnel\" "
    :default "funnel"]
   ["-h" "--help" "Output this help information."]])

(defn print-help [summary]
  (println "Usage: funnel [OPTS]")
  (println)
  (println summary))

(defn -main [& args]
  (let [{:keys [options arguments summary]} (cli/parse-opts args option-specs)]
    (if (:help options)
      (print-help summary)
      (let [ws-server (websocket-server {:port ws-port
                                         :event-handler prn})
            wss-server (doto (websocket-server {:port wss-port
                                                :event-handler prn})
                         (.setWebSocketFactory
                          (DefaultSSLWebSocketServerFactory.
                           (ssl-context (:keystore options (io/resource "keystore.jks"))
                                        (:keystore-password options)))))]
        (reset! state
                (cond-> {:ws-server ws-server}
                  wss-server
                  (assoc :wss-server wss-server)))
        (prn "starting")
        (.start ws-server)
        (prn "started")
        (when wss-server
          (prn "starting2")
          (.start wss-server)
          (prn "started2"))))))

;;socket = new WebSocket("wss://localhost:44221")
