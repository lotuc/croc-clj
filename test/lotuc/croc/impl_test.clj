(ns lotuc.croc.impl-test
  (:require
   [clojure.string :as string]
   [clojure.test :refer :all]
   [lotuc.croc.impl :refer [croc croc-cmd croc-event-flow line->progress
                            line->recv-event line->relay-event
                            line->send-event]]
   [missionary.core :as m]))

(deftest croc-cmd-test
  (is (= ["croc" "relay" "--ports" "1,2"]
         (croc-cmd {:relay {:ports [1 2]}})))
  (is (= ["croc" "--yes" "--ignore-stdin" "--overwrite" "send" "--code" "helloworld" "--text" "hello world"]
         (croc-cmd {:send {:code "helloworld" :text "hello world"}})))
  (is (= ["croc" "--yes" "--ignore-stdin" "--overwrite" "send" "--code" "helloworld" "/tmp"]
         (croc-cmd {:send {:code "helloworld" :files ["/tmp"]}})))
  (is (= ["croc" "--yes" "--ignore-stdin" "--overwrite" "helloworld"]
         (croc-cmd {:recv {:code "helloworld"}}))))

(deftest line->progress-test
  (is (= {:prefix "demo.bin", :percent 94, :transferred {:value 1.4, :unit "GB"}, :total {:value 1.4, :unit "GB"}, :speed {:value 4.1, :unit "GB/s"}, :suffix "[0s:0s]"}
         (line->progress "demo.bin  94% |██████████████████  | (1.4/1.4 GB, 4.1 GB/s) [0s:0s]")))
  (is (= {:prefix "demo.bin", :percent 94, :transferred {:value 1.4, :unit "MB"}, :total {:value 1.4, :unit "GB"}, :speed {:value 4.1, :unit "GB/s"}, :suffix "[0s:0s]"}
         (line->progress "demo.bin  94% |██████████████████  | (1.4 MB/1.4 GB, 4.1 GB/s) [0s:0s]"))))

(deftest line->relay-event-test
  (is (= [:relay-version "v9.6.13"]
         (line->relay-event "[info]	2024/03/06 08:41:24 starting croc relay version v9.6.13")))
  (is (= [:starting-tcp {:port 9009, :host "172.16.8.73"}]
         (line->relay-event "[info]	2024/03/05 16:24:34 starting TCP server on 172.16.8.73:9009")))
  (is (= [:starting-tcp {:port 9012}]
         (line->relay-event "[info]	2024/03/05 16:23:51 starting TCP server on :9012"))))

(deftest line->send-event-test
  (is (= [:code "0153-ammonia-candid-amazon"]
         (line->send-event "Code is: 0153-ammonia-candid-amazon")))
  (is (= [:recv-hint "croc --relay 127.0.0.1:9009 0153-ammonia-candid-amazon"]
         (line->send-event "croc --relay 127.0.0.1:9009 0153-ammonia-candid-amazon")))
  (is (= [:sending-to {:host "172.16.8.73", :port 62926}]
         (line->send-event "Sending (->172.16.8.73:62926)")))
  (is (= [:progress {:prefix "foo.db", :percent 100, :transferred {:value 8.2, :unit "kB"}, :total {:value 8.2, :unit "kB"}, :speed {:value 19.0, :unit "MB/s"}, :suffix ""}]
         (line->send-event "foo.db 100% |████████████████████| (8.2/8.2 kB, 19 MB/s)")))
  (is (= [:counting {:nfile 5, :size {:value 891.1, :unit "MB"}}]
         (line->send-event "Sending 5 files (891.1 MB)")))
  (is (= [:counting {:nfile 25, :size {:value 3.7, :unit "GB"}, :nfolder 1}]
         (line->send-event "Sending 25 files and 1 folders (3.7 GB)"))))

(deftest line->recv-event-test
  (is (= {:filename "dnscache.json", :size {:value 7.0, :unit "kB"}}
         (line->recv-event "Receiving 'dnscache.json' (7.0 kB)")))
  (is (= [:progress {:prefix "demo", :percent 100, :transferred {:value 7.2, :unit "kB"}, :total {:value 7.2, :unit "kB"}, :speed {:value 4.8, :unit "MB/s"}, :suffix ""}]
         (line->recv-event "demo 100% |████████████████████| (7.2/7.2 kB, 4.8 MB/s)")))
  (is (= [:counting {:nfile 25, :size {:value 3.7, :unit "GB"}}]
         (line->recv-event "Receiving 25 files (3.7 GB)")))
  (is (= {:filename "dnscache.json", :size {:value 7.0, :unit "kB"}}
         (line->recv-event "Receiving 'dnscache.json' (7.0 kB)"))))

(defn- pinfo [{:keys [proc]}]
  (let [alive (.isAlive proc)]
    (cond-> {:pid (.pid proc) :alive alive}
      (not alive) (assoc :exit-value (.exitValue proc)))))

(defn- print-event [prefix e]
  (if (vector? e)
    (case (first e)
      :startprocess (println
                     (with-out-str
                       (println)
                       (println prefix (string/join "" (repeat 70 "-")))
                       (prn prefix :startprocess (pinfo (second e)))
                       (println prefix (string/join "" (repeat 70 "-")))))
      :stopped      (let [[_ process ms] e]
                      (println
                       (with-out-str
                         (println)
                         (println prefix (string/join "" (repeat 70 "-")))
                         (prn prefix :stopped (pinfo process))
                         (prn prefix :stopped-process (with-out-str (print (:cmd process))))
                         (println prefix (string/join "" (repeat 70 "-")))))
                      (doseq [m ms]
                        (prn prefix :stopped-std m)))
      :stdout      (apply prn prefix '> (rest e))
      :stderr      (apply prn prefix '! (rest e))
      (apply prn prefix e))
    (prn prefix e)))

(defn r!
  ([p]        (m/? (m/reduce {} nil p)))
  ([prefix p] (m/? (m/reduce (fn [_ e] (print-event prefix e)) nil p))))

(defn- start-process [options]
  (->> (m/ap (let [p (m/?< (m/observe (fn [!] ((croc options) ! #(prn :err %)))))]
               (m/amb [:startprocess p] (m/?> (croc-event-flow options p)))))
       (m/eduction (fn [rf]
                     (fn
                       ([] (rf))
                       ([r] (rf r))
                       ([r i] (cond-> (rf r i) (= :stopped (first i)) reduced)))))))

(defn start-relay [relay-pass ports]
  (start-process {:global {:pass relay-pass} :relay {:ports ports}}))

(defn send-text [relay relay-pass code text]
  (start-process {:global {:relay relay :pass relay-pass} :send {:code code :text text}}))

(defn recv [relay relay-pass code]
  (start-process {:global {:relay relay :pass relay-pass} :recv {:code code}}))

(deftest send-text-via-relay-test
  (let [text "hello\nworld\n!"
        relay-p (future (r! :relay (start-relay "hello" [9009])))
        send-p  (future (r! :send (send-text "127.0.0.1:9009" "hello" "kMyr7R4Kzx" text)))
        recv-task (->> (recv "127.0.0.1:9009" "hello" "kMyr7R4Kzx")
                       (m/eduction
                        (fn [rf]
                          (let [!r (atom [])]
                            (fn
                              ([] (rf))
                              ([r] (rf r))
                              ([r [e v0 & _ :as i]]
                               (when (= e :stdout)
                                 (swap! !r conj v0))
                               (cond (and (= e :stopped) (zero? (.exitValue (:proc v0))))
                                     (reduced (rf r [:ok (string/join "\n" @!r)]))
                                     (= e :stopped)
                                     (reduced (rf r [:err i]))
                                     :else
                                     (rf r i)))))))
                       (m/reduce (fn [_ [e _r :as i]]
                                   (if (#{:ok :err} e) i (println i)))
                                 nil))]
    (try (Thread/sleep 1000)            ; wait for relay & send process ready
         (is (= [:ok text] (m/? recv-task)))
         (finally (future-cancel relay-p)
                  (future-cancel send-p)))))

(comment
  ;; starting relay
  (def p0 (future (r! :relay (start-relay "hello" [9009]))))
  ;; sending text via relay
  (def p1 (future (r! :send (send-text "127.0.0.1:9009" "hello" "kMyr7R4Kzx" "hello\nworld\n!!"))))
  ;; recv text via relay
  (r! :recv (recv "127.0.0.1:9009" "hello" "kMyr7R4Kzx")))
