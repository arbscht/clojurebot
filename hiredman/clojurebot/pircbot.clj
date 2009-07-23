(ns hiredman.clojurebot.pircbot
  (:require (hiredman.clojurebot [config :as config]))
  (:import (org.jibble.pircbot PircBot)))

(defn pircbot [config]
  (doto
    (proxy [PircBot] [])
    (.connect (:server config))
    (.changeNick (:name config))
    (.joinChannel (:channel config))))

(defmethod config/subsystems-processor :irc [_ config]
  (assoc-in config [:irc :bot] (pircbot (:irc config))))
