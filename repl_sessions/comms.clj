(ns repl-sessions.comms
  (:require [lambdaisland.funnel :as funnel]
            [io.pedestal.log :as log])
  (:import (java.net URI)
           (org.java_websocket.client WebSocketClient)))

(def servers (funnel/start! {:verbose 3
                             :keystore-password "funnel"}))




(def client
  (proxy [WebSocketClient] [(URI. "ws://localhost:44220")]
    (onOpen [handshake]
      (log/info :client/open handshake))
    (onClose [code reason remote?]
      (log/info :client/close {:code code :reason reason :remote? remote?}))
    (onMessage [message]
      (log/info :client/message message))
    (onError [ex]
      (log/error :client/error true :exception ex))))

(.connect client)

(.send client (funnel/to-transit {:foo :bar}))

{:funnel/whoami {:id "firefox-123"
                 :type :kaocha.cljs2/js-runtime}}

{:funnel/whoami {:id "kaocha-cljs-abcd"
                 :type :kaocha.cljs2/test-run}
 :funnel/subscribe {:type #{:kaocha.cljs2/js-runtime}}}

{:funnel/broadcast {:id "firefox-123"}}

{:funnel/subscribe [:id "firefox-123"]}
{:funnel/subscribe [:type :kaocha.cljs2/js-runtime]}
