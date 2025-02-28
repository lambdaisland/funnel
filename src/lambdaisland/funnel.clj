(ns lambdaisland.funnel
  (:gen-class)
  (:require
   [clojure.core.async :as async]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.pprint :as pprint]
   [clojure.tools.cli :as cli]
   [cognitect.transit :as transit]
   [charred.api :as charred]
   [lambdaisland.funnel.log :as log]
   [lambdaisland.funnel.version :as version])
  (:import
   (com.cognitect.transit DefaultReadHandler
                          WriteHandler)
   (java.io ByteArrayInputStream
            ByteArrayOutputStream
            FileInputStream
            FileOutputStream
            PrintStream
            IOException)
   (java.net InetSocketAddress Socket)
   (java.nio ByteBuffer)
   (java.nio.file Files Path Paths)
   (java.security KeyStore)
   (java.util Comparator)
   (javax.net.ssl SSLContext
                  KeyManagerFactory)
   (lambdaisland.funnel Daemon)
   (org.java_websocket WebSocket
                       WebSocketAdapter
                       WebSocketImpl)
   (org.java_websocket.drafts Draft_6455)
   (org.java_websocket.handshake Handshakedata)
   (org.java_websocket.handshake ClientHandshake)
   (org.java_websocket.server DefaultWebSocketServerFactory
                              DefaultSSLWebSocketServerFactory
                              WebSocketServer)
   (sun.misc Signal)))

(set! *warn-on-reflection* true)

;; Arbitrary high ports. I hope nobody was using these, they are ours now. This
;; is where clients expect to find us.

(def ws-port  44220)
(def wss-port 44221)

(Thread/setDefaultUncaughtExceptionHandler
 (reify Thread$UncaughtExceptionHandler
   (uncaughtException [_ thread ex]
     (log/error :uncaught-exception {:thread (.getName thread)} :exception ex))))

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

(defmulti encode (fn [format value] format))
(defmulti decode (fn [format value] format))

(defmethod encode :transit [_ value]
  (try
    (let [out (ByteArrayOutputStream. 4096)
          writer (transit/writer out :json {:handlers {TaggedValue tagged-write-handler}})]
      (transit/write writer value)
      (.toString out))
    (catch Exception e
      [::error e])))

(defmethod decode :transit [_ ^String transit]
  (try
    (let [in (ByteArrayInputStream. (.getBytes transit))
          reader (transit/reader in :json {:default-handler tagged-read-handler})]
      (transit/read reader))
    (catch Exception e
      [::error e])))

(defmethod encode :edn [_ value]
  (pr-str value))

(defmethod decode :edn [_ ^String edn]
  (edn/read-string edn))

(defmethod encode :json [_ value]
  (charred/write-json-str value))

(defmethod decode :json [_ ^String json]
  (charred/read-json json :key-fn keyword))

(defn match-selector? [whoami selector]
  (cond
    (nil? whoami)      false
    (true? selector)   true
    (vector? selector) (= (second selector) (get whoami (first selector)))
    (map? selector)    (reduce (fn [_ [k v]]
                                 (if (= v (get whoami k))
                                   true
                                   (reduced false)))
                               nil
                               selector)))

(defn destinations [source broadcast-sel conns]
  (let [whoami (get-in conns [source :whoami])]
    (map key
         (filter
          (fn [[c m]]
            (and (or (match-selector? (:whoami m) broadcast-sel)
                     (some #(match-selector? whoami %) (:subscriptions m)))
                 (not= c source)))
          conns))))

(defn outbox [^WebSocket conn]
  (.getAttachment conn))

(defn handle-query [conn selector conns]
  (let [msg {:funnel/clients
             (keep (comp :whoami val)
                   (filter
                    (fn [[c m]]
                      (and (match-selector? (:whoami m) selector)
                           (not= c conn)))
                    conns))}]
    (async/>!! (outbox conn) msg)))

(defn handle-message [state ^WebSocket conn raw-msg]
  (let [msg (decode (get-in @state [conn :format]) raw-msg)]
    (when-let [e (maybe-error msg)]
      (log/warn :message-decoding-failed {:raw-msg raw-msg :desc "Raw message will be forwarded"} :exception e))
    (let [[msg broadcast]
          (if-not (map? msg)
            (do
              (log/warn :forwarding-raw-message raw-msg)
              [raw-msg nil])
            (do
              (log/debug :message msg)
              (when-let [whoami (:funnel/whoami msg)]
                (swap! state assoc-in [conn :whoami] whoami))
              (when-let [selector (:funnel/subscribe msg)]
                (swap! state update-in [conn :subscriptions] (fnil conj #{}) selector))
              (when-let [selector (:funnel/unsubscribe msg)]
                (swap! state update-in [conn :subscriptions] (fnil disj #{}) selector))
              (when-let [selector (:funnel/query msg)]
                (handle-query conn selector @state))

              [(if-let [whomai (:whoami (get @state conn))]
                 (assoc msg :funnel/whoami whomai)
                 msg)
               (:funnel/broadcast msg)]))]
      (if-let [e (maybe-error msg)]
        (log/error :message-encoding-failed {:msg msg} :exception e)
        (do
          (let [conns @state
                dests (destinations conn broadcast conns)]
            (log/trace :message msg :sending-to (map (comp :whoami conns) dests))
            (doseq [^WebSocket c dests]
              (async/>!! (outbox c) msg))))))))

(defn handle-open [state ^WebSocket conn handshake]
  (log/info :connection-opened {:remote-socket-address (.getRemoteSocketAddress conn)})
  (let [path (.getResourceDescriptor conn)
        format (case path
                 "/?content-type=json" :json
                 "/?content-type=edn" :edn
                 "/?content-type=transit" :transit
                 :transit)
        outbox (async/chan 8 (map (partial encode format)))]
    (.setAttachment conn outbox)
    (swap! state assoc-in [conn :format] format)
    (async/go-loop []
      (when-let [^String msg (async/<! outbox)]
        (when (.isOpen conn)
          (.send conn msg))
        (recur)))))

(defn handle-close [state ^WebSocket conn code reason remote?]
  (log/info :connection-closed {:whoami (:whoami (get @state conn))
                                :code code
                                :reason reason
                                :closed-by-remote? remote?})
  (swap! state dissoc conn)
  (async/close! (outbox conn)))

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
    (doto server
      (.setReuseAddr true))))

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

(defmethod print-method InetSocketAddress [^InetSocketAddress a ^java.io.Writer w]
  (.write w "#InetSocketAddress \"")
  (.write w (.getHostString a))
  (.write w (str ":" (.getPort a) "\"")))

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

(defn init-logging [level logfile]
  (let [root  (java.util.logging.Logger/getLogger "")
        level (case (long level)
                0 java.util.logging.Level/WARNING
                1 java.util.logging.Level/INFO
                2 java.util.logging.Level/FINE
                java.util.logging.Level/FINEST)]
    (run! #(.removeHandler root %) (.getHandlers root))
    (.setLevel root level)
    (let [handler (if (and logfile (not= "-" logfile))
                    (java.util.logging.FileHandler. logfile)
                    (java.util.logging.ConsoleHandler.))
          formatter (proxy [java.util.logging.Formatter] []
                      (format [^java.util.logging.LogRecord record]
                        (let [ex (.getThrown record)
                              sym (gensym "ex")]
                          #_(when ex
                              (intern 'user sym ex))
                          (str (.getLevel record) " ["
                               (.getLoggerName record) "] "
                               (.formatMessage ^java.util.logging.Formatter this record)
                               (if ex
                                 (str " => " (.getName (class ex)) " user/" sym)
                                 "")
                               "\n"))))]
      (doto  ^java.util.logging.Handler handler
        (.setLevel level)
        (.setFormatter formatter))
      (.addHandler root handler))))

(defn native-image? []
  (= ["runtime" "executable"]
     [(System/getProperty "org.graalvm.nativeimage.imagecode")
      (System/getProperty "org.graalvm.nativeimage.kind")]))

(defn option-specs []
  (cond-> [
           ["-k" "--keystore FILE" "Location of the keystore.jks file, necessary to enable SSL."]
           [nil  "--keystore-password PASSWORD" "Password to load the keystore, defaults to \"password\"." :default "password"]
           [nil  "--ws-port PORT" "Port for the websocket server (non-SSL)" :default ws-port :parse-fn #(Integer/parseInt %)]
           [nil  "--wss-port PORT" "Port for the websocket server (SSL)" :default wss-port :parse-fn #(Integer/parseInt %)]
           ["-v" "--verbose" "Increase verbosity, -vvv is the maximum." :default 0 :update-fn inc]
           [nil  "--version" "Print version information and exit."]
           [nil  "--logfile FILE" "Redirect logs to file. Default is stdout, or when daemonized: /tmp/funnel.log"]]
    (native-image?)
    (conj ["-d" "--daemonize" "Run as a background process."])
    :->
    (conj ["-h" "--help" "Output this help information."])))

(defn print-version []
  (let [{:keys [version date sha]} version/VERSION]
    (println "lambdaisland/funnel" version (str "(" date " / " sha ")"))))

(defn print-help [summary]
  (println "Usage: funnel [OPTS]")
  (println)
  (println summary)
  (println)
  (print-version))

(defn ws-server [opts]
  (let [ws-port   (:ws-port opts)
        state     (:state opts (atom {}))]
    (websocket-server {:port ws-port
                       :state state})))

(defn wss-server [{:keys [keystore keystore-password wss-port] :as opts}]
  (when (and keystore keystore-password)
    (let [state (:state opts (atom {}))]
      (doto (websocket-server {:port wss-port :state state})
        (.setWebSocketFactory
         (DefaultSSLWebSocketServerFactory.
          (ssl-context keystore keystore-password)))))))

(defn start-server [server]
  (when server
    (.start ^WebSocketServer server)
    (let [s @server]
      (if (instance? Throwable s)
        (throw s)
        s))))

(defn port-in-use! []
  (println "Address already in use, is Funnel already running?")
  (System/exit 42))

(defn start-servers [{:keys [ws-port wss-port] :as opts}]
  (try
    (let [ws (ws-server opts)
          wss (wss-server opts)]
      (start-server ws)
      (start-server wss)
      (log/info :started (cond-> [(str "ws://localhost:" ws-port)]
                           wss
                           (conj (str "wss://localhost:" wss-port)))))
    (catch java.net.BindException e
      (port-in-use!))))

(defn extract-native-lib! []
  (let [libdir (io/file (System/getProperty "java.io.tmpdir") (str "funnel-" (rand-int 9999999)))]
    (.mkdirs libdir)
    (doseq [libfile ["libDaemon.so" "libDaemon.dylib"]
            :let [resource (io/resource libfile)]
            :when resource]
      (io/copy (io/input-stream resource) (io/file libdir libfile)))
    (System/setProperty "java.library.path" (str libdir))
    libdir))

(defn check-port-in-use! [opts]
  (try
    (let [sock (Socket. "localhost" (long (:ws-port opts ws-port)))]
      (.close sock)
      (port-in-use!))
    (catch IOException e)))

(defn -main [& args]
  (let [{:keys [options arguments summary]} (cli/parse-opts args (option-specs))]
    (init-logging (:verbose options) (:logfile options
                                               (when (:daemonize options)
                                                 (str (io/file (System/getProperty "java.io.tmpdir") "funnel.log")))))
    (cond
      (:help options)
      (do
        (print-help summary)
        (System/exit 0))

      (:version options)
      (do
        (print-version)
        (System/exit 0))

      :else
      (let [opts (assoc options :state (atom {}))]
        (log/trace :starting options)
        (if (:daemonize opts)
          (do
            (check-port-in-use! opts)
            (let [libdir (extract-native-lib!)
                  pid (Daemon/daemonize)]
              (if (= 0 pid)
                (do
                  (.close System/out)
                  (.close System/err)
                  (.close System/in)
                  ;; Does not seem to work in the native-image build, sigh.
                  (Signal/handle (Signal. "INT")
                                 (reify sun.misc.SignalHandler
                                   (handle [this signal]
                                     (run! #(io/delete-file % true) (file-seq libdir))
                                     (System/exit 0))))
                  (start-servers opts))
                (prn pid))))
          (start-servers opts))))))



(comment

  (init-logging 3)

  (intern 'user 'foo 123)

  (log/error :foo :bar :exception (Exception. "123"))

  (start-servers {:ws-port 2234 :wss-port 2235})
  )
