(ns lotuc.croc
  (:require
   [lotuc.croc.impl :as impl]))

(defn croc
  "Call into [croc](https://github.com/schollz/croc). returns a [task](https://github.com/leonoel/task) value.

  options `send`, `recv` & `relay` are mutually exclusive.

  croc options:
  - `croc`: bin path (defaults to \"croc\")
  - `global` - croc --help
     - `:yes`, `:ignore-stdin`, `overwrite` defaults to be `true`
  - `relay`  - croc relay --help
     - `:ports` should be a vector of integer (ports)
  - `send`   - croc send --help
     - `:files` should be a vector of file paths (string/java.io.File)"
  [{:keys [croc global send recv relay
           on-progress on-log on-out on-send-ready]
    :as options}]
  (impl/croc options))
