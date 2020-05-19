(ns lambdaisland.funnel-test
  (:require [lambdaisland.funnel :as funnel]
            [clojure.test :refer :all]
            [clojure.java.io :as io])
  (:import (java.net URI)
           (org.java_websocket.client WebSocketClient)
           (java.net Socket ConnectException)))

;; Thanks babashka!
(defn wait-for-port
  "Waits for TCP connection to be available on host and port. Options map
  supports `:timeout` and `:pause`. If `:timeout` is provided and reached,
  `:default`'s value (if any) is returned. The `:pause` option determines
  the time waited between retries."
  ([host port]
   (wait-for-port host port nil))
  ([^String host ^long port {:keys [:default :timeout :pause] :as opts}]
   (let [opts (merge {:host host
                      :port port}
                     opts)
         t0 (System/currentTimeMillis)]
     (loop []
       (let [v (try (with-open [_ (Socket. host port)]
                      (- (System/currentTimeMillis) t0))
                    (catch ConnectException _e
                      (let [took (- (System/currentTimeMillis) t0)]
                        (if (and timeout (>= took timeout))
                          :wait-for-port.impl/timed-out
                          :wait-for-port.impl/try-again))))]
         (cond (identical? :wait-for-port.impl/try-again v)
               (do (Thread/sleep (or pause 100))
                   (recur))
               (identical? :wait-for-port.impl/timed-out v)
               default
               :else
               (assoc opts :took v)))))))

(use-fixtures :once (fn [t]
                      (let [server (funnel/start! {:ws-port 55660
                                                   :wss-port 55661})]
                        (try
                          (wait-for-port "localhost" 55660)
                          (Thread/sleep 1000)
                          (t)
                          (finally
                            (funnel/stop! server))))))

(defrecord TestClient [history ^WebSocketClient client]
  java.io.Closeable
  (close [this]
    (.close client))
  clojure.lang.IFn
  (invoke [this message]
    (.send client ^String (funnel/to-transit message))))

(defn test-client []
  (let [history (atom [])
        connected? (promise)
        client (proxy [WebSocketClient] [(URI. "ws://localhost:55660")]
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

(deftest subscribe-test
  (with-open [c1 (test-client)
              c2 (test-client)]
    (c1 {:funnel/subscribe [:id "123"]})
    (c2 {:funnel/whoami {:id "123"}
         :foo :bar})
    (c2 {:foo :baz})
    (is (= @(:history c1) [{:funnel/whoami {:id "123"}
                            :foo :bar}
                           {:foo :baz}]))))

(comment
  (require '[kaocha.repl :as kaocha])

  (kaocha/run)


  (def server (funnel/start! {:ws-port 55660
                              :wss-port 55661}))

  (funnel/stop! server)

  (def c (test-client))

  (c {:hello 123})
  )
