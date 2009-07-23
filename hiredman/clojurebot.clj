(ns hiredman.clojurebot
  (:require (hiredman.clojurebot [config :as config] [pircbot :as irc]))
  (:import
     (java.io File FileReader PushbackReader)
     (org.jibble.pircbot PircBot))
  (:gen-class))

(defmulti send-out (comp type first list))

(defn- -main [config]
  (let [config (with-open [in (-> config File. FileReader. PushbackReader.)]
                 (binding [*in* in] (read)))]
    (loop [k (keys config) bot config]
      (if (seq k)
        (recur (rest k) (config/subsystems-processor (first k) bot))
        (doto bot prn)))))
