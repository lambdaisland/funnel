(ns lambdaisland.funnel-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer :all]
            [lambdaisland.funnel :as funnel]
            [lambdaisland.funnel.test-util :refer [*port*
                                                   available-port
                                                   test-client
                                                   test-server
                                                   with-available-port
                                                   will match?]]
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
  (testing "messages get forwarded to subscriber"
    (with-open [_ (test-server)
                c1 (test-client)
                c2 (test-client)
                c3 (test-client)]
      (c1 {:funnel/whoami {:id 1}
           :funnel/subscribe [:id 2]})
      (c2 {:funnel/whoami {:id 2}
           :foo :bar})
      (c3 {:funnel/whoami {:id 3}})
      (c2 {:foo :baz})
      (will (= @(:history c1) [{:funnel/whoami {:id 2}
                                :foo :bar}
                               {:funnel/whoami {:id 2}
                                :foo :baz}]))
      (will (= @(:history c2) []))
      (will (= @(:history c3) [])))))

(deftest unsubscribe-test
  (let [state (atom {})]
    (with-open [s (test-server state)
                c (test-client)]
      (c {:funnel/subscribe [:foo :bar]})
      (will (= [{:subscriptions #{[:foo :bar]}}] (vals @state)))
      (c {:funnel/unsubscribe [:foo :bar]})
      (will (= [{:subscriptions #{}}] (vals @state))))))

(deftest match-selector-test
  (is (funnel/match-selector? {:id 123} [:id 123]))
  (is (not (funnel/match-selector? nil [:id 123])))
  (is (funnel/match-selector? nil true))
  (is (not (funnel/match-selector? {:id 123} [:id 456]))))

(deftest destinations-test
  (let [state {:ws1 {:whoami {:id :ws1}
                     :subscriptions #{[:id :ws2]}}
               :ws2 {:whoami {:id :ws2}}
               :ws3 {:whoami {:id :ws3}}}]

    (is (= [:ws1] (funnel/destinations :ws2 nil state)))
    (is (match? (m/in-any-order [:ws1 :ws3])
                (funnel/destinations :ws2 true state)))
    (is (match? (m/in-any-order [:ws1 :ws3])
                (funnel/destinations :ws2 [:id :ws3] state)))
    (is (= [:ws3] (funnel/destinations :ws1 [:id :ws3] state)))))

(deftest query-test
  (with-open [s (test-server)
              c1 (test-client)
              c2 (test-client)
              c3 (test-client)]
    (c1 {:funnel/query true})
    (will (= [{:funnel/clients []}] @(:history c1)))

    (c1 {:funnel/whoami {:id 123 :type :x}})
    (c2 {:funnel/whoami {:id 456 :type :x}})
    (c3 {:funnel/whoami {:id 789 :type :y}})

    (reset! (:history c1) [])
    (c1 {:funnel/query true})
    (will (= [{:funnel/clients
               [{:id 456 :type :x}
                {:id 789 :type :y}]}]
             @(:history c1)))

    (c2 {:funnel/query [:id 789]})
    (will (= [{:funnel/clients
               [{:id 789 :type :y}]}]
             @(:history c2)))

    (c3 {:funnel/query [:type :x]})
    (will (= [{:funnel/clients
               [{:id 123 :type :x}
                {:id 456 :type :x}]}]
             @(:history c3)))
    )
  )


(comment
  (require '[kaocha.repl :as kaocha])

  (kaocha.repl/run `destinations-test)

  (kaocha.repl/run 'lambdaisland.funnel-test/query-test)

  (alter-var-root #'*port* (constantly (available-port)))

  (def state (atom {}))
  (def s (test-server state))

  (def c1 (test-client))
  (def c2 (test-client))
  (def c3 (test-client))

  (c1 {:funnel/whoami {:id :c1}})
  (c2 {:funnel/whoami {:id :c2}})
  (c3 {:funnel/whoami {:id :c3}})

  (c1 {:funnel/broadcast true
       :foo :bar})

  (c1 {:funnel/query true})
  (vals @state)
  @(:history c1)

  (do
    (.close s)
    (.close c1)
    (.close c2)
    (.close c3))

  )
