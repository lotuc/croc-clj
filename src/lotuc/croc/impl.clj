(ns lotuc.croc.impl
  (:require
   [babashka.process :as p]
   [clojure.java.io :as io]
   [clojure.string :as string]
   [missionary.core :as m])
  (:import
   [java.util.concurrent LinkedBlockingQueue]))

;; (set! *warn-on-reflection* true)

(defn croc-cmd
  [options]
  (letfn [(port? [v]
            (and (integer? v) (< 0 v 65536)))
          (file-exists? [v]
            (or (and (instance? java.io.File v) (.exists ^java.io.File v))
                (and (string? v) (.exists (io/file v)))))
          (add-booleans [cmd m ks]
            (reduce (fn [cmd k] (cond-> cmd (m k) (conj (str "--" (name k))))) cmd ks))
          (add-values [cmd m ks]
            (reduce (fn [cmd k] (let [v (m k)] (cond-> cmd v (conj (str "--" (name k)) v)))) cmd ks))
          (adjust-defaults [{:keys [send recv] :as options}]
            (if (or recv send)
              (-> options
                  (update-in [:global :yes]          (fn [v] (if (some? v) v true)))
                  (update-in [:global :ignore-stdin] (fn [v] (if (some? v) v true)))
                  (update-in [:global :overwrite]    (fn [v] (if (some? v) v true))))
              options))
          (check-recv-options [{:keys [recv] :as options}]
            (assert (:code recv) ":recv code not set")
            options)
          (check-send-options [{:keys [send] :as options}]
            (let [{:keys [text files port]} send]
              (assert (->> [text files] (filter some?) count (= 1))
                      (str ":send's :text or :files must be provided, but got " options))
              (when (some? files)
                (assert (and (vector? files) (every? file-exists? files))
                        (str ":send's :files must be a vector of existing files, but got " files)))
              (cond-> options
                true  (update :global dissoc :out)
                files (assoc-in [:send :files] (map (comp str io/file) files))
                port  (assoc-in [:send :port] (str port)))))
          (check-relay-options [{:keys [relay] :as options}]
            (let [ports (:ports relay)]
              (assert (and (vector? ports) (every? port? ports))
                      (str ":relay's :ports must be a vector of valid ports, but got " ports))
              (-> options
                  (update-in [:relay :ports] #(string/join "," (map str %)))
                  (update :global dissoc :relay :out))))
          (check-options [{:keys [relay recv send] :as options}]
            (assert (->> [relay recv send] (filter some?) count (= 1))
                    (str "Exactly one of :relay, :recv, or :send must be provided, but got " options))
            (cond-> (adjust-defaults options)
              recv  (check-recv-options)
              send  (check-send-options)
              relay (check-relay-options)))]
    (let [{:keys [croc global send recv relay]} (check-options options)
          files (and send (:files send))]
      (cond-> [(or croc "croc")]
        global (add-booleans global [:internal-dns :remember :debug :yes :stdout :no-compress :ask :local :ignore-stdin :overwrite :testing])
        global (add-values global [:curve :ip :relay :relay6 :out :pass :socks5 :connect :throttleUpload])

        recv   (conj (:code recv))

        send   (conj "send")
        send   (add-booleans send [:zip :no-local :no-multi :git])
        send   (add-values send [:code :hash :port :transfers :text])
        files  (as-> $ (apply conj $ files))

        relay  (conj "relay")
        relay  (add-values relay [:host :ports])))))

(defn- parse-size [s]
  (let [[_ n _ unit] (re-matches #"(\S+)(\s+(.+))?" s)]
    {:value (parse-double n) :unit unit}))

(defn line->progress [line]
  (letfn [(parse-details [line]
            (when-some [[_ transferred total speed suffix] (re-matches #"\s*\((.+)/(.+),\s+(.+)\)\s*(.*)" line)]
              (let [total (parse-size total)]
                {:transferred (update (parse-size transferred) :unit #(or % (:unit total)))
                 :total total
                 :speed (parse-size speed)
                 :suffix (string/trim suffix)})))]
    (when-some [[_ prefix progress suffix] (re-matches #"(.*)\s+(\d+)%\s+\|.*\|(.*)" line)]
      (merge {:prefix (string/trim prefix) :percent (parse-long progress)}
             (parse-details suffix)))))

(defn line->relay-event [line]
  (letfn [(version []
            (when-some [[_ version] (re-matches #".*starting croc relay version\s+(\S+).*" line)]
              [:relay-version version]))
          (host-port []
            (when-some [[_ host port] (re-matches #".*starting TCP server on\s+(\S+)?:(\d+).*" line)]
              [:starting-tcp (cond-> {:port (parse-long port)} host (assoc :host host))]))]
    (or (host-port) (version))))

(defn line->send-event [line]
  (letfn [(code-is []
            (when-some [[_ code] (re-matches #"Code is:\s+(\S+).*" line)]
              [:code code]))
          (send-counting []
            (when-some [[_ nfile _ nfolder size] (re-matches #"Sending\s+(\d+)\s+files(\s+and\s+(\d+)\s+folders)?\s+\((.+)\)" line)]
              [:counting (cond-> {:nfile (parse-long nfile) :size (parse-size size)}
                           nfolder (assoc :nfolder (parse-long (or nfolder "0"))))]))
          (recv-hint []
            (when (string/starts-with? line "croc")
              [:recv-hint line]))
          (send-start []
            (when-some [[_ host port] (re-matches #"Sending\s+\(->(\S+):(\d+).*" line)]
              [:sending-to {:host host :port (parse-long port)}]))
          (progress []
            (when-some [p (line->progress line)]
              [:progress p]))]
    (or (progress) (send-counting) (code-is) (recv-hint) (send-start))))

(defn line->recv-event [line]
  (letfn [(connecting []
            (when (string/starts-with? line "connecting")
              [:connecting line]))
          (securing-channel []
            (when (string/starts-with? line "securing")
              [:securing line]))
          (room-not-ready []
            (when (string/includes? line "room not ready")
              [:room-not-ready line]))
          (recv-file []
            (when-some [[_ f size] (re-matches #"Receiving\s+'(.+)'\s+\((.+)\)" line)]
              {:filename f :size (parse-size size)}))
          (recv-counting []
            (when-some [[_ nfile _ nfolder size] (re-matches #"Receiving\s+(\d+)\s+files(\s+and\s+(\d+)\s+folders)?\s+\((.+)\)" line)]
              [:counting (cond-> {:nfile (parse-long nfile) :size (parse-size size)}
                           nfolder (assoc :nfolder (parse-long (or nfolder "0"))))]))
          (recv-start []
            (when-some [[_ host port] (re-matches #"Receiving\s+\(<-(\S+):(\d+).*" line)]
              [:receiving-from {:host host :port (parse-long port)}]))
          (recv-folder []
            (when (string/ends-with? line "/")
              [:receiving-folder line]))
          (progress []
            (when-some [p (line->progress line)]
              [:progress p]))]
    (or (progress)
        (recv-file) (recv-counting) (recv-start)
        (recv-folder) (room-not-ready) (securing-channel) (connecting))))

(defn- stream->line-flow
  "line | :eof | Throwable."
  [s]
  (letfn [(->queue [s]
            (let [q (LinkedBlockingQueue. 1)]
              (future (with-open [^java.io.BufferedReader rdr  (io/reader s)]
                        (loop []
                          (when-some [line (.readLine rdr)]
                            (.put q line)
                            (recur)))
                        (.put q :eof)))
              q))
          (t [^LinkedBlockingQueue q] (.take q))]
    (m/ap (let [q (->queue s)]
            (loop []
              (let [v (m/? (m/via m/blk (t q)))]
                (if (= v :eof)
                  (m/amb v)
                  (m/amb v (recur)))))))))

(defn- make-rf [line->event]
  (fn [rf]
    (fn
      ([] (rf))
      ([r] (rf r))
      ([r v]
       (let [[source val] v]
         (if (or (= val :eof) (instance? Throwable val))
           (rf r [:stopped [source val]])
           (do
             (rf r v)
             (when-some [e (line->event source val)]
               (rf r e)))))))))

(defn- merge-stdout-stderr
  [{:keys [out err] :as process} event-rf]
  (->> (m/ap (let [c (atom [])
                   f (->> (m/seed [(->> (stream->line-flow out)
                                        (m/eduction (map (fn [v] [:stdout v])) event-rf))
                                   (->> (stream->line-flow err)
                                        (m/eduction (map (fn [v] [:stderr v])) event-rf))])
                          (m/?> ##Inf))
                   v (m/?> f)]
               (if (= (first v) :stopped)
                 ;; record stream `:stopped` event (stdout & stderr)
                 ;; wait for both stream ending & emit `:stopped` once
                 (let [x (swap! c conj v)]
                   (if (= 2 (count x))
                     (m/amb [:stopped process x])
                     (m/amb)))
                 (m/amb v))))
       (m/eduction (fn [rf]
                     (fn
                       ([] (rf))
                       ([r] (rf r))
                       ([r i] (cond-> (rf r i) (= :stopped (first i)) reduced)))))))

(defn croc-event-flow
  [{:keys [send recv relay] :as options} process]
  (->> (cond send (make-rf (fn [s line] (when (= s :stderr) (line->send-event line))))
             recv (make-rf (fn [s line] (when (= s :stderr) (line->recv-event line))))
             relay (make-rf (fn [_ line] (line->relay-event line)))
             :else (throw (ex-info "invalid croc options" {:options options})))
       (merge-stdout-stderr process)))

(defn croc
  [{:keys [croc global send recv relay process-options]
    :as options}]
  (fn [ok-cont err-cont]
    (let [process (try (apply p/process process-options (croc-cmd options))
                       (catch Throwable t (err-cont t)))]
      (when process (ok-cont process))
      (fn [] (when process (p/destroy-tree process))))))
