(ns gen-class-version
  (:import (lambdaisland websocket_server)
           (lambdaisland.websocket_server Handler)))


(defrecord MyHandler []
  Handler
  (on-open [this conn handshake]
    (prn handshake))
  (on-close [this conn code reason remote?]
    (prn [:close]))
  (on-message [this  conn message]
    (prn [:message message]))
  (on-error [this  conn ex]
    (prn [:error ex])
    )
  (on-start [this server]
    (prn [:started! server])))


(def s (lambdaisland.websocket_server. {:port 12346
                                        :handler (->MyHandler)}))
(.start s)
