(ns lambdaisland.funnel.test-util
  (:require [clojure.java.io :as io]
            [clojure.test :as t]
            [lambdaisland.funnel :as funnel]
            [matcher-combinators.test]
            [matcher-combinators.core :as mc])
  (:import (java.net URI)
           (java.net ServerSocket)
           (org.java_websocket.client WebSocketClient)))

(def ^:dynamic *port* 0)

(defn available-port []
  (let [sock (ServerSocket. 0)]
    (try
      (.getLocalPort sock)
      (finally
        (.close sock)))))

(defmacro with-available-port [& body]
  `(binding [*port* (available-port)]
     ~@body))

(defrecord TestClient [history ^WebSocketClient client]
  java.io.Closeable
  (close [this]
    (.close client))
  clojure.lang.IFn
  (invoke [this message]
    (.send client ^String (funnel/to-transit message))))

(defmacro will
  "Variant of [[clojure.test/is]] that gives the predicate a bit of time to become
  true."
  [expected]
  `(loop [i# 0]
     (if (and (not ~expected) (< i# 30))
       (do
         (Thread/sleep 50)
         (recur (inc i#)))
       (t/is ~expected))))

(defn match?
  "Pure predicate version of matcher-combinators, otherwise using (will (match?))
  will break."
  [expected actual]
  (mc/indicates-match? (mc/match expected actual)))

(defn test-client []
  (let [history (atom [])
        connected? (promise)
        client (proxy [WebSocketClient] [(URI. (str "ws://localhost:" *port*))]
                 (onOpen [handshake]
                   (deliver connected? true))
                 (onClose [code reason remote?])
                 (onMessage [message]
                   (swap! history conj (funnel/from-transit message)))
                 (onError [ex]
                   (println ex)
                   ))]
    (.connect client)
    @connected?
    (map->TestClient
     {:history history
      :client client})))

(defn test-server
  ([]
   (test-server (atom {})))
  ([state-atom]
   (funnel/start-server
    (doto (->  {:ws-port *port*
                :state state-atom}
               funnel/ws-server)

      (.setTcpNoDelay true)))))
