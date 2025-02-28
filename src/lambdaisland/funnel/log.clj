(ns lambdaisland.funnel.log
  "Minimal logging shim to replace pedestal.log, since it pulls in stuff that's
  not GraalVM compatible."
  (:import
   (java.util.logging Logger Level)))

(def ^Logger logger (Logger/getLogger "lambdaisland.funnel"))

(defn error [& kvs]
  (.log logger Level/SEVERE (pr-str (apply array-map kvs))))

(defn warn [& kvs]
  (.log logger Level/WARNING (pr-str (apply array-map kvs))))

(defn info [& kvs]
  (.log logger Level/INFO (pr-str (apply array-map kvs))))

(defn debug [& kvs]
  (.log logger Level/FINE (pr-str (apply array-map kvs))))

(defn trace [& kvs]
  (.log logger Level/FINER (pr-str (apply array-map kvs))))
