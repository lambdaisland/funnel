(ns lambdaisland.funnel
  (:gen-class)
  (:require [clojure.core.async :as async]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.tools.cli :as cli]
            [cognitect.transit :as transit]
            [io.pedestal.log :as log])
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
                               WebSocketAdapter
                               WebSocketImpl)
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

(defn maybe-error [e]
  (when (and (vector? e) (= ::error (first e)))
    (second e)))

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

(defn handle-message [state ^WebSocket conn raw-msg]
  (let [msg (from-transit raw-msg)
        inbox (:inbox (.getAttachment conn))]
    (log/trace :message msg)
    (if (map? msg)
      (do
        (when-let [whoami (:funnel/whoami msg)]
          (swap! state assoc-in [conn :whoami] whoami))
        (when-let [selector (:funnel/subscribe msg)]
          (swap! state update-in [conn :subscriptions] (fnil conj #{}) selector))
        (when-let [selector (:funnel/unsubscribe msg)]
          (swap! state update-in [conn :subscriptions] (fnil disj #{}) selector))

        (async/>!! inbox (to-transit
                          (if-let [whomai (:whoami (get @state conn))]
                            (assoc msg :funnel/whoami whomai)
                            msg))))
      (async/>!! inbox raw-msg))))

(defn handle-open [state ^WebSocket conn handshake]
  (let [inbox (async/chan)
        outbox (async/chan)]
    (.setAttachment conn {:inbox inbox
                          :outbox outbox})
    (async/go-loop []
      (let [^String msg (async/<! outbox)]
        (.send conn msg)
        (recur)))
    (async/go-loop []
      (let [^String msg (async/<! inbox)]
        (doseq [^WebSocket c (keys @state)
                :when (not= c conn)]
          (async/>! (:outbox (.getAttachment c)) msg))
        (recur)))))

(defn handle-close [state conn code reason remote?]
  (swap! state dissoc conn))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; WS Server

(defn websocket-server
  "Create an instance of WebSocketServer, without starting it. Implements
  Closeable so it can be used it [[with-open]], implements IDeref to make it
  easy to block until the server has fully booted up."
  ^WebSocketServer
  [{:keys [state host port decoder-count]
    :or   {decoder-count (.. Runtime getRuntime availableProcessors)}
    :as   opts}]
  (let [started? (promise)
        server (proxy [WebSocketServer java.io.Closeable clojure.lang.IDeref clojure.lang.IMeta]
                   [^InetSocketAddress
                    (if host
                      (InetSocketAddress. ^String host ^long port)
                      (InetSocketAddress. port))
                    ^Integer
                    decoder-count]
                 (onOpen [^WebSocket conn ^ClientHandshake handshake]
                   (log/trace :ws-socket/open {:conn conn :handshake handshake})
                   (handle-open state conn handshake))
                 (onClose [^WebSocket conn code ^String reason remote?]
                   (log/trace :ws-socket/close {:conn conn :code code :reason reason :remote? remote?})
                   (handle-close state conn code reason remote?))
                 (onMessage [^WebSocket conn ^String message]
                   (try
                     (handle-message state conn message)
                     (catch Exception e
                       (log/error :handle-message-error {:message message} :exception e))))
                 (onError [^WebSocket conn ^Exception ex]
                   (log/error :ws-server/error opts :exception ex)
                   (when-not (realized? started?)
                     (deliver started? ex)))
                 (onStart []
                   (deliver started? this))
                 (close []
                   (try
                     (log/info :stopping-server this)
                     (.stop ^WebSocketServer this)
                     (catch Exception e
                       (log/error :error-while-stopping-server this :exception e))))
                 (deref []
                   @started?)
                 (meta []
                   {:type ::server})
                 (toString []
                   (pr-str
                    {:type ::server
                     :opts opts
                     :started? (realized? started?)})))]
    #_(.addMethod ^clojure.lang.MultiFn pprint/simple-dispatch (class server) (fn [s] (print s)))
    server))

(defmethod print-method ::server [v ^java.io.Writer w]
  (.write w (.toString ^Object v)))

(defmethod print-method WebSocketImpl [^WebSocketImpl s ^java.io.Writer w]
  (.write w "#org.java_websocket.WebSocket ")
  (.write w (pr-str {:open? (.isOpen s)
                     :closing? (.isClosing s)
                     :flush-and-close? (.isFlushAndClose s)
                     :closed? (.isClosed s)
                     :selection-key (.getSelectionKey s)
                     :remote-socket-address (.getRemoteSocketAddress s)
                     :local-socket-address (.getLocalSocketAddress s)
                     :resource-descriptor (.getResourceDescriptor s)})))

(defn ssl-context [keystore password]
  (let [key-manager (KeyManagerFactory/getInstance (KeyManagerFactory/getDefaultAlgorithm))
        key-store (KeyStore/getInstance (KeyStore/getDefaultType))
        pw (.toCharArray ^String password)]

    (with-open [fs (io/input-stream keystore)]
      (.load key-store fs pw))

    (.init key-manager key-store pw)

    (doto (SSLContext/getInstance "TLS")
      (.init (.getKeyManagers key-manager) nil nil))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; CLI

(defn init-logging [level]
  (let [root  (java.util.logging.Logger/getLogger "")
        level (case (long  level)
                0 java.util.logging.Level/WARNING
                1 java.util.logging.Level/INFO
                2 java.util.logging.Level/FINE
                java.util.logging.Level/FINEST)]
    (run! #(.removeHandler root %) (.getHandlers root))
    (.setLevel root level)
    (.addHandler root
                 (doto (java.util.logging.ConsoleHandler.)
                   (.setLevel level)
                   (.setFormatter
                    (proxy [java.util.logging.Formatter] []
                      (format [^java.util.logging.LogRecord record]
                        (let [ex (.getThrown record)
                              sym (gensym "ex")]
                          #_(when ex
                              (intern 'user sym ex))
                          (str (.getLevel record) " ["
                               (.getLoggerName record) "] "
                               (.getMessage record)
                               (if ex
                                 (str " => " (.getName (class ex)) " user/" sym)
                                 "")
                               "\n")))))))))

(def option-specs
  [[nil "--keystore FILE" "Location of the keystore.jks file, necessary to enable SSL"]
   [nil "--keystore-password PASSWORD" "Password to load the keystore, defaults to \"funnel\" "]
   ["-v" "--verbose" "Increase verbosity, -vvv is the maximum."
    :default 0
    :update-fn inc]
   ["-h" "--help" "Output this help information."]])

(defn print-help [summary]
  (println "Usage: funnel [OPTS]")
  (println)
  (println summary))

(defn ws-server [options]
  (let [ws-port   (:ws-port options ws-port)
        state     (:state options (atom {}))]
    (websocket-server {:port ws-port
                       :state state})))

(defn wss-server [options]
  (let [wss-port (:wss-port options wss-port)
        state (:state options (atom {}))]
    (doto (websocket-server {:port wss-port
                             :state state})
      (.setWebSocketFactory
       (DefaultSSLWebSocketServerFactory.
        (ssl-context (:keystore options (io/resource "keystore.jks"))
                     (:keystore-password options "funnel")))))))

(defn start-server [server]
  (log/info :ws-server/starting server)
  (.start ^WebSocketServer server)
  (log/trace :ws-server/started server)
  (let [s @server]
    (if (instance? Throwable s)
      (throw s)
      s)))

(defn -main [& args]
  (let [{:keys [options arguments summary]} (cli/parse-opts args option-specs)]
    (init-logging (:verbose options))
    (if (:help options)
      (print-help summary)
      (let [opts (assoc options :state (atom {}))]
        (start-server (ws-server opts))
        (start-server (wss-server opts))))))

;;socket = new WebSocket("wss://localhost:44221")
(comment

  (init-logging 3)

  (intern 'user 'foo 123)

  (log/error :foo :bar :exception (Exception. "123"))
  )
