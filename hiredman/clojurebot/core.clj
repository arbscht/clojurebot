;;
;; Thus spake the master programmer:
;;   "Though a program be but three lines long, someday it will have to be
;;   maintained."
;; 
;;
;; [01:30] <uhelp> lexxan: Since Mon May  2 17:22:46 2005,
;;                 there have been 0 modifications and 0 questions.
;;                 I have been awake for 7 minutes and 36 seconds
;;                 this session, and currently reference 19
;;                 factoids. Addressing is in optional mode.


;java -server -ms16m -mx64m -Xss128m

(ns hiredman.clojurebot.core
    (:use (hiredman sandbox))
    (:require [hiredman.pqueue :as pq]
              [hiredman.schedule :as sched]
              [hiredman.utilities :as util]
              [hiredman.words :as w])
    (:import (org.jibble.pircbot PircBot)
             (java.util Date Timer TimerTask)
             (java.util.concurrent ScheduledThreadPoolExecutor TimeUnit)))

(def *bots* (ref {})) ; This will hold bot objects
(def start-date (Date.))

(def task-runner sched/task-runner)

;; dictionaries for storing relationships
;; 'are' dict is not used right now.
(def dict-is (ref {}))
(def dict-are (ref {}))

;; this struct is used to pass around messages
(defstruct junks :channel :sender :login :hostname :message)

(defn randth
      "random item from sequence"
      [se]
      (let [s (seq se)]
        (first (drop (rand-int (count se)) se))))

;; responses that can be randomly selected from
(def input-accepted ["'Sea, mhuise." "In Ordnung" "Ik begrijp" "Alles klar" "Ok." "Roger." "You don't have to tell me twice." "Ack. Ack." "c'est bon!"])
(def befuddl ["Titim gan éirí ort." "Gabh mo leithscéal?" "No entiendo"  "excusez-moi" "Excuse me?" "Huh?" "I don't understand." "Pardon?" "It's greek to me."])

(defn ok
      "random input-accepted sort of string"
      []
      (randth input-accepted))

(defn befuddled
      "random \"Huh?\" sort of string"
      []
      (randth befuddl))

;;(defn inits
;;      "this is Chouser's fault"
;;      [s]
;;      (map first
;;           (take-while second
;;                       (map split-at
;;                            (iterate inc 0)
;;                            (repeat (lazy-cat s [nil]))))))

(defn inits [x] (seq (map #(take % x) (range 1 (inc (count x))))))

(defn strip-is
      "return a string with everything up to the end of the
      first \"is\" removed"
      [string]
      (.trim (.substring string (+ 3 (.indexOf string " is ")))))

(defn term
      "returns the part of a string before the first occurence
      of \"is\""
      [string]
      (first (.split string " is ")))

(defn doc-lookup?
      "is this a well formed doc-string lookup?"
      [msg]
      (re-find #"^\(doc " msg))

(defn d?op
      "if string ends in a question mark return
      the string without the question mark"
      [x]
      (.replaceAll x "^(.*)\\?$" "$1"))

(defn- normalise-docstring
       [string]
       (and string (.replaceAll string "\\s+" " ")))

(defn symbol-to-var-doc
      "this returns the doc metadata from a var in the
      clojure ns or a befuddled response"
      [symb]
      (let [a (meta (find-var (symbol "clojure.core" symb)))
            x (normalise-docstring (:doc a))
            y (:arglists a)]
        (if x
          (str x (when y (str "; arglists " y)))
          (befuddled))))

(defmacro async
  "just do this, I don't care"
  [& x]
  `(send-off (agent nil) (fn [& _#] ~@x )))

(defn who
      "am I talking to someonein a privmsg, or in a channel?"
      [pojo]
      (or (:channel pojo) (:sender pojo)))

(defn sendMsg
      "send a message to a recv, a recv is a channel name or a nick"
      [this recv msg]
      (io! (.sendMessage this recv (.replace (str msg) \newline \space))))

(defn sendMsg-who
      "wrapper around sendMsg"
      [bot msg msg-to-send]
  (sendMsg (:this bot) (who msg) msg-to-send))

(defmulti send-out (fn [& x] (first x)))

(defmethod send-out :msg [_ bot recvr string]
  (io! (.sendMessage #^PircBot (:this bot) (if (map? recvr) (who recvr) recvr) (normalise-docstring (str string)))))

(defmethod send-out :action [_ bot recvr string]
  (io! (.sendAction #^PircBot (:this bot) (if (map? recvr) (who recvr) recvr) (normalise-docstring (str string)))))

(defmethod send-out :notice [_ bot recvr string]
  (io! (.sendNotice #^PircBot (:this bot) (if (map? recvr) (who recvr) recvr) (normalise-docstring (str string)))))

(defmulti new-send-out (comp type first list))

(defn send-out [one two & r]
  (apply new-send-out two one r))

(defmethod new-send-out clojure.lang.PersistentHashMap [bot msg-type recvr message]
  (condp = msg-type
    :msg
      (io! (.sendMessage #^PircBot (:this bot) (if (map? recvr) (who recvr) recvr) (normalise-docstring (.toString message))))
    :action
      (io! (.sendAction #^PircBot (:this bot) (if (map? recvr) (who recvr) recvr) (normalise-docstring (.toString message))))
    :notice
      (io! (.sendNotice #^PircBot (:this bot) (if (map? recvr) (who recvr) recvr) (normalise-docstring (.toString message))))))

(defmethod new-send-out :irc [bot msg-type recvr message]
  (new-send-out (vary-meta bot dissoc :type) msg-type recvr message))

(defn do-channels [bot fn]
      (doseq [c (.getChannels (:this bot))]
             (fn c)))

(defn term-lists
      "generates permutions of the words in string"
      [msg words-to-ignore]
      (let [x (re-seq #"\w+" msg)
            ignore #(not ((set words-to-ignore) %))]
        (filter ignore (map #(apply str (interpose " " %)) (mapcat #(reverse (inits (drop % x))) (take (count x) (iterate inc 0)))))))

(defn rlookup
      "look up terms from a seq until you find a defi"
      [terms]
      (when (seq terms)
      (if (@dict-is (first terms))
        (first terms)
        (recur (seq (rest terms))))))

(defn fuzzy-lookup
      "look up based on permutation"
      [message words-to-ignore]
      (rlookup (term-lists message words-to-ignore)))

(defn fuzzy-key-lookup
      "look up based on match part of a term"
      [term]
      (randth (filter #(when (> (.lastIndexOf % term) -1) true) (keys @dict-is))))

;;TODO recognize "clojurebot, blah bleh"
(defn addressed?
      "is this message prefixed with clojurebot: "
      [bot msg]
  (if-let [m (:addressed? (meta msg))]
    m
    (when (or (re-find #"^~" (:message msg))
              (re-find (re-pattern (str "^" (:nick bot) ":")) (:message msg))
              (nil? (:channel msg)))
       msg)))



(defn is
      "add a new definition to a term in dict-is"
      [term defi]
      (if (@dict-is term)
        (let [old (@dict-is term)
              v (if (vector? old)
                  (conj old defi)
                  [old defi])]
          (dosync (commute dict-is assoc term v)))
        (dosync (commute dict-is assoc term defi))))

(defn is-
      "add a new definition to a term"
      [bot term defi]
      (let [old  (get-in @(:store bot) [:is term])]
           (send-off (:store bot) assoc term
                     (cond
                       (vector? old)
                        (conj old defi)
                       (not (nil? old))
                        [old defi]
                       :else
                        defi))))

(defn is!
      "define a term in dict-is, overwriting anything that was there"
      [term defi]
      (if (or (= :nope (@dict-is term :nope)) true)
        (dosync (commute dict-is assoc term defi))
        (throw (java.util.prefs.BackingStoreException. "Already Defined"))))


(defn what-is
      "looks up a term in @dict-is"
      [term]
      (when-let [f (@dict-is term)]
        (if (vector? f) (randth f) f)))

(defmacro dfn 
  "Creates a dispatch fn with 'bot bound to the bot object
   and 'msg bound to a struct representing the message"
  [& body]
  `(fn [~'bot ~'msg]
     ~@body))

(defn everyone-I-see
      "returns seq like ([\"#clojure\" (\"somenick\" \"someothernicl\")])
      for ever channel the bot is in"
      [bot] 
      (for [channel (.getChannels (:this bot))]
           [channel (map (comp :nick bean) (.getUsers (:this bot) channel))]))

(defn see-nick?
      "do I see someone with the nickname nick? returns nil or a seq of channels where I see him"
      [bot nick]
      (reduce #(when %2 (if (seq? %) (conj % %2) (filter identity [% %2])))
              nil
              (map first (filter #(some (fn [y] (.equals y nick)) (last %))
                                 (everyone-I-see bot)))))

(defn random-person [bot]
      (randth (filter #(not (.equals % (:nick bot)))
                      (apply concat (map last (everyone-I-see bot))))))

(def #^{:doc "ref contains priority queue that is used for dispatching the responder multimethod"}
     *dispatchers*
     (ref pq/empty))


(defn dispatch
      "this function does dispatch for responder"
      [bot msg]
      (loop [d (pq/seq @*dispatchers*)]
            (when d
              (let [[k v] (first d)]
                (if (k bot msg)
                  v
                  (recur (seq (rest d))))))))


(defn add-dispatch-hook
  "Allows you to add your own hook to the message responder
   You *must* define a 'responder multimethod corresponding to the
   dispatch-value"
      ([dispatch-check dispatch-value]
         (add-dispatch-hook 0 dispatch-check dispatch-value))
      ([dispatch-priority dispatch-check dispatch-value]
       (dosync (commute *dispatchers* pq/conj dispatch-priority [dispatch-check dispatch-value]))))

(defn remove-dispatch-hook [dispatch-value]
  (dosync
    (alter
      *dispatchers*
      (comp (partial into pq/empty)
            (partial filter #(not= dispatch-value (last (last %))))))))

;; register legacy stuffs
(dorun
  (map #(add-dispatch-hook 0 (first %) (second %))
       [[(dfn (doc-lookup? (:message msg))) ::doc-lookup]
        [(dfn (and (addressed? bot msg) 
              (re-find #"how much do you know?" (:message msg)))) ::know]
        [(dfn (re-find #"^\([\+ / \- \*] [ 0-9]+\)" (:message msg))) ::math]]))

;;this stuff needs to come last?
(add-dispatch-hook 20 (dfn (and (addressed? bot msg) (not (:quit msg)))) ::lookup)

(defmacro defresponder [key priority fn & body]
  `(do
     (defmethod responder ~key [~'bot ~'msg]
       (let [~'msg (vary-meta ~'msg assoc ~key true)]
         ~@body))
     (add-dispatch-hook ~priority (dfn (when (not (~key (meta ~'msg))) (~fn ~'bot ~'msg))) ~key)))

(defmulti #^{:doc "currently all messages are routed though this function"} responder dispatch)

(defmethod responder nil [& _])

(defmethod responder ::math [bot pojo]
  (let [[op & num-strings] (re-seq #"[\+\/\*\-0-9]+" (:message pojo))
        nums (map #(Integer/parseInt %) num-strings)]
    (send-out :msg bot pojo
                 (let [out (apply  (find-var (symbol "clojure.core" op)) nums)]
                   (if (> out 4)
                     "*suffusion of yellow*"
                     out)))))

(defmethod responder ::doc-lookup [bot pojo]
  #(responder bot (update-in pojo [:message] (fn [x] (str "," x)))))

(defn remove-from-beginning
  "return a string with the concatenation of the given chunks removed if it is
   found at the start of the string"
  [string & chunks]
  (.replaceFirst string (apply str "^" chunks) ""))

(defn extract-message
      "removes bot name and/or ~ from the beginning of the msg"
      [bot pojo]
      (.trim (.replaceAll (:message pojo) (str "(?:" (:nick bot) ":|~)(.*)") "$1")))

;; (defmethod responder ::define-is [bot pojo]
;;   (let [a (.trim (extract-message bot pojo))
;;         term (term a)
;;         x (strip-is a)
;;         defi (remove-from-beginning x "also ")]
;;     (is- bot term defi)
;;     (try
;;       (if (re-find #"^also " x)
;;         (is term defi)
;;         (is! term defi))
;;       (send-out :msg bot pojo (ok))
;;       (catch java.util.prefs.BackingStoreException e
;;              (send-out :msg bot pojo (str "sorry, " term " may already be defined"))))))


(defn replace-with [str map]
      (reduce #(.replaceAll % (first %2) (second %2)) str map))

(defn prep-reply
      "preps a reply, does substituion of stuff like <reply> and #who"
      [sender term defi bot]
      (replace-with
        (if (re-find #"^<reply>" defi)
          (.trim (remove-from-beginning (str defi) "<reply>"))
          (str term " is " defi))
        {"#who" sender "#someone" (random-person bot)}))

(defmethod responder ::lookup [bot pojo]
  (let [msg (d?op (.trim (extract-message bot pojo)))
        result (what-is msg)
        words-to-ignore ["a" "where" "what" "is" "who" "are" (:nick bot)]]
    (cond
      result,
          (new-send-out bot :msg (who pojo) (prep-reply (:sender pojo) msg result bot))
      (fuzzy-lookup msg words-to-ignore),
        (let [term (fuzzy-lookup msg words-to-ignore)
              defi (what-is term)]
          (new-send-out bot :msg (who pojo) (prep-reply (:sender pojo) term defi bot)))
      (fuzzy-key-lookup msg),
        (let [term (fuzzy-key-lookup msg)
              defi (what-is term)]
          (send-out :msg bot (who pojo) (prep-reply (:sender pojo) term defi bot)))
      :else,
        (send-out :msg bot (who pojo) (befuddled)))))


(defmethod responder ::know [bot pojo]
  (new-send-out bot :msg pojo (str "I know " (+ (count (deref dict-is)) (count (deref dict-are))) " things")))

(defn user-watch [this]
      (let [cur (count (.getUsers this "#clojure"))
            pre (Integer/parseInt (what-is "max people"))]
        (when (> cur pre)
          (is! "max people" (str cur)))))

(defn handleMessage [this channel sender login hostname message]
      (try 
        (let [bot (get @*bots* this)
              msg (struct junks channel sender login hostname message)]
          (trampoline responder bot (vary-meta msg assoc :addressed? (addressed? bot msg))))
        (catch Exception e (.printStackTrace e))))

(defn handlePrivateMessage [this sender login hostname message]
      (handleMessage this nil sender login hostname message))

(defn join-or-part [this event channel sender login hostname]
      (try
        (trampoline responder (get @*bots* this)
                    (assoc (struct junks channel sender login hostname "") event true))
        (catch Exception e (.printStackTrace e))))

(defn pircbot [bot-config]
  (let [bot-obj 
        (proxy [PircBot] []
          (onJoin [channel sender login hostname]
                  (join-or-part this :join channel sender login hostname))
          (onPart [channel sender login hostname]
                  (join-or-part this :part channel sender login hostname))
          (onQuit [nick login hostname reason]
                  (join-or-part this :quit nil nick login hostname))
          (onMessage [channel sender login hostname message]
                     (handleMessage this channel sender login hostname message))
          (onPrivateMessage [sender login hostname message]
                            (handlePrivateMessage this sender login hostname message)))]
    (merge bot-config {:this bot-obj})))

(defn dict-file [config suffix]
  (let [file (-> (str (:dict-dir config "./") (:dict-basename config (:nick config)) suffix)
                 java.io.File.)]
    (.createNewFile file)
    file))

(defn dump-dicts [config]
  (dorun (map (fn [[rel rels]]
         (binding [*out* (java.io.FileWriter. 
                          (dict-file config rel))]
           (prn @rels)
           (.close *out*)))
       [[".is" dict-is] [".are" dict-are]])))

(defn load-dicts [config]
  (dosync
   (ref-set dict-is
            (eval
             (binding [*in* (java.io.PushbackReader. 
                             (java.io.FileReader.
                              (dict-file config ".is")))]  
               (let [a (try (read) (catch Exception e {}))]
                 (.close *in*)
                 a))))))

(defn dump-dict-is [config]
      (println "Dumping dictionaries")
      (binding [*out* (-> (dict-file config ".is")
                          java.io.FileWriter.)]
               (prn @dict-is)
               (.close *out*)))

(defn load-store [bot]
      (send (:store bot)
            (fn [& _]
              (println "Reading store")
              (binding [*in* (-> (dict-file bot ".store") java.io.FileReader. java.io.PushbackReader.)]
                (with-open [i *in*]
                  (try (read)
                    (catch Exception e
                      (println e))))))))

(defn watch-store [bot]
      (add-watch (:store bot)
                 :writer
                 (fn [key ref old-state new-state]
                   (println "Writing store")
                   (binding [*out* (-> (dict-file bot ".store") java.io.FileWriter.)]
                     (with-open [o *out*] (prn new-state))))))

(defn start-dump-thread [config]
  (sched/fixedrate {:task #(dump-dict-is config) :start-delay 1 :rate 10 :unit (:minutes sched/unit)}))

(defn start-clojurebot [attrs additional-setup]
 (let [bot (pircbot attrs)]
   (dosync (commute *bots* assoc (:this bot) bot))
   (doto (:this bot)
     (.connect (:network bot))
     (.changeNick (:nick bot))
     (.joinChannel (:channel bot)))
   (additional-setup bot)
   bot))

(defmacro run-clojurebot [botname botattrs & additional-setup]
  `(start-clojurebot ~botattrs (fn [~botname] (do ~@additional-setup))))
