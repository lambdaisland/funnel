(ns lambdaisland.funnel.log-formatter
  (:gen-class
   :extends java.util.logging.Formatter))

(defmacro safe-intern
  "Can't intern in Graal native image, but useful for debug"
  [ns sym val]
  (when-not *compile-files*
    `(intern ~ns ~sym ~val)))

(defn -format [this ^java.util.logging.LogRecord record]
  (let [ex (.getThrown record)
        sym (gensym "ex")]
    (when ex
      (safe-intern 'user sym ex))
    (str (.getLevel record) " ["
         (.getLoggerName record) "] "
         (.getMessage record)
         (if ex
           (str " => " (.getName (class ex)) " user/" sym)
           "")
         "\n")))
