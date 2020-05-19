(ns lambdaisland.funnel.client
  (:import (java.net URI)
           (org.java_websocket.client WebSocketClient)))

(def client
  (proxy [WebSocketClient] [(URI. "ws://localhost:44220")]
    (onOpen [handshake])
    (onClose [code reason remote?])
    (onMessage [message])
    (onError [ex])))

;; (.connect client)

;; (.send client (lambdaisland.funnel/to-transit {:foo :bar}))
