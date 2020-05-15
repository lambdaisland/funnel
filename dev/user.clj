(ns user)

(defmacro jit [sym]
  `(requiring-resolve '~sym))
