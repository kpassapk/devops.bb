(ns devops.bb.log
  "Reading `devops-execution-log': one JSON line per thing the user did.

  A line is a notification -- what happened and where -- and holds no
  output; see devops-log.el.  This namespace only turns bytes into
  event maps; what they mean together is devops.bb.model's job."
  (:require
   [cheshire.core :as json]
   [clojure.string :as str])
  (:import
   [java.io RandomAccessFile]
   [java.time OffsetDateTime]
   [java.time.format DateTimeFormatter]))

(def ^:private time-format
  ;; devops-log.el writes `format-time-string "%FT%T%z"'
  (DateTimeFormatter/ofPattern "yyyy-MM-dd'T'HH:mm:ss[.SSS]Z"))

(defn parse-time [s]
  (try (OffsetDateTime/parse s time-format)
       (catch Exception _ nil)))

(defn parse-line
  "An event map for one log line, or nil for a line that isn't JSON.
  :event is a keyword and :time an OffsetDateTime; the rest is as logged."
  [line]
  (when-not (str/blank? line)
    (try
      (let [e (json/parse-string line true)]
        (cond-> e
          (:event e) (update :event keyword)
          (:time e)  (assoc :time (parse-time (:time e))
                            :time-str (:time e))))
      (catch Exception _ nil))))

(defn read-from
  "The events appended to the log at PATH since byte OFFSET.
  Returns {:events [...] :offset n}.  A partial last line is left for
  the next read.  A file shorter than OFFSET was truncated or replaced,
  so it is read from the start, with :reset true."
  [path offset]
  (let [f (java.io.File. ^String path)]
    (if-not (.exists f)
      {:events [] :offset 0 :missing true}
      (with-open [raf (RandomAccessFile. f "r")]
        (let [len    (.length raf)
              reset? (< len offset)
              start  (if reset? 0 offset)
              buf    (byte-array (- len start))]
          (.seek raf start)
          (.readFully raf buf)
          (let [s    (String. buf "UTF-8")
                nl   (str/last-index-of s "\n")
                done (if nl (subs s 0 (inc nl)) "")]
            {:events (into [] (keep parse-line) (str/split-lines done))
             :offset (+ start (count (.getBytes ^String done "UTF-8")))
             :reset  reset?}))))))
