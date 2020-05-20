(ns lambdaisland.funnel-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer :all]
            [lambdaisland.funnel :as funnel]
            [lambdaisland.funnel.test-util :refer [test-client test-server with-available-port will match?]]
            [matcher-combinators.matchers :as m])
  (:import (java.net URI)
           (java.net Socket ServerSocket ConnectException)
           (org.java_websocket.client WebSocketClient)))

(use-fixtures :each (fn [t] (with-available-port (t))))

(deftest whoami-test
  (let [state (atom {})]
    (with-open [s (test-server state)
                c (test-client)]
      (c {:funnel/whoami {:id 123}})
      (will (= [{:whoami {:id 123}}] (vals @state)))

      (c {:funnel/whoami {:id :abc :hello :world}})
      (will (= [{:whoami {:id :abc :hello :world}}] (vals @state)))

      (with-open [c2 (test-client)]
        (c2 {:funnel/whoami {:root "/x/y/z"}})

        (will (match? (m/in-any-order [{:whoami {:id :abc
                                                 :hello :world}}
                                       {:whoami {:root "/x/y/z"}}])
                      (vals @state))))

      (testing "closing will clean up the client connection"
        (will (= [{:whoami {:id :abc :hello :world}}] (vals @state)))))))

(deftest subscribe-test
  (with-open [_ (test-server)
              c1 (test-client)
              c2 (test-client)]
    (c1 {:funnel/subscribe [:id "123"]})
    (c2 {:funnel/whoami {:id "123"}
         :foo :bar})
    (c2 {:foo :baz})
    (will (= @(:history c1) [{:funnel/whoami {:id "123"}
                              :foo :bar}
                             {:funnel/whoami {:id "123"}
                              :foo :baz}]))))

(comment
  (require '[kaocha.repl :as kaocha])

  (kaocha/run `state-test)
  (kaocha.repl/run

    'lambdaisland.funnel-test/whoami-test
    )


  (with)
  (let [state (atom {})]
    (with-open [s (test-server state)
                c (test-client)]
      (c {:funnel/whoami {:id "123"}})
      @state
      ))

  (time
   (with-available-port
     ))

  (def x (let [s (test-server)
               c (test-client)]
           (.close c)
           (.close s)
           s))

  (def x
    (let [state (atom {})
          server (test-server state)]
      server))
  (.close x)
  (def state (atom {}))
  (def s (let [s (test-server (atom {}))] s))
  (.close s)
  (def c (test-client))
  (.close c)
  (clojure.pprint/pprint)

  (c {:funnel/whoami {:foo :barrrr}
      :funnel/subscribe [:type :hello]})
  @state

  state

  (.getSelectionKey s)

  )
