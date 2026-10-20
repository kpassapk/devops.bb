(ns devops.bb.feed
  "The feed: what one attached workbook tells its sandbox.

    FEED/
      status.json         feed name, workbook, devops-mode, attached, publisher heartbeat
      events.jsonl        the events, paths mapped, numbered by seq, appended in order
      outputs/<id>.json   run-output answer, one file per finished run

  A sink is where the feed is written, with two verbs: `put' replaces a
  file atomically, `append' adds one line to events.jsonl.  The
  publisher (devops.bb.publish) writes through a sink only, so a sink
  that runs a command elsewhere (--via, --ssh) is one more `Sink'.

  The reader -- `status', `events-after', `output' -- is everything the
  sandbox side reads, and the one place a NATS reader would replace."
  (:require
   [babashka.fs :as fs]
   [cheshire.core :as json]
   [clojure.string :as str]
   [devops.bb.log :as log])
  (:import
   [java.nio.file Files StandardCopyOption]))

;;; Layout

(def status-file "status.json")
(def events-file "events.jsonl")

(defn output-file [id] (str "outputs/" id ".json"))

(def ^:private uuid-re
  #"(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

(defn valid-id?
  "Whether ID can name an output: a UUID, so never a path."
  [id]
  (boolean (and (string? id) (re-matches uuid-re id))))

(defn configured
  "The feed dir feed mode reads: --feed, else DEVOPS_BB_FEED, else nil.
  Never guessed: without either, commands ask Emacs."
  [opts]
  (let [dir (or (:feed opts) (System/getenv "DEVOPS_BB_FEED"))]
    (when-not (str/blank? dir)
      (str (fs/absolutize (fs/expand-home dir))))))

;;; Sinks

(defprotocol Sink
  (put [sink path s]
    "Replace PATH, relative to the feed, with the string S, atomically.")
  (append [sink line]
    "Append LINE, one JSON line, to events.jsonl."))

(defrecord LocalDir [dir]
  Sink
  (put [_ path s]
    (let [f   (fs/path dir path)
          tmp (fs/path (str f ".tmp"))]
      (fs/create-dirs (fs/parent f))
      (spit (str tmp) s)
      (Files/move tmp f (into-array [StandardCopyOption/ATOMIC_MOVE
                                     StandardCopyOption/REPLACE_EXISTING]))))
  (append [_ line]
    ;; One write of the whole line, so a reader never sees half of it
    ;; joined to the next.
    (fs/create-dirs dir)
    (spit (str (fs/path dir events-file)) (str line "\n") :append true)))

(defn local-dir
  "The sink that writes the feed into DIR, on this machine."
  [dir]
  (->LocalDir (str (fs/absolutize (fs/expand-home dir)))))

;;; Reader

(defn status
  "status.json of the feed in DIR, read, or nil when there is none."
  [dir]
  (let [f (fs/file dir status-file)]
    (when (fs/exists? f)
      (try (json/parse-string (slurp f) true)
           (catch Exception _ nil)))))

(defn events-after
  "The events appended to DIR's events.jsonl since byte OFFSET whose
  :seq is above SEQ: {:events [...] :offset n :seq last-seen :reset bool}.
  A retried append may land twice; its seq is not above the last, so
  it is skipped.  A file shorter than OFFSET is a new feed: it is read
  from the start, and SEQ from 0, with :reset true."
  [dir {after :seq :keys [offset] :or {offset 0 after 0}}]
  (let [{:keys [events offset reset]} (log/read-from (str (fs/path dir events-file)) offset)]
    (loop [[e & more :as es] events, last-seq (if reset 0 after), out []]
      (if (empty? es)
        {:events out :offset offset :seq last-seq :reset (boolean reset)}
        (let [n (:seq e)]
          (if (and (number? n) (> n last-seq))
            (recur more n (conj out e))
            (recur more last-seq out)))))))

(defn output
  "outputs/ID.json of the feed in DIR, as written, or nil.  Throws for
  an ID that is not a UUID."
  [dir id]
  (when-not (valid-id? id)
    (throw (ex-info (str "not a run ID: " id) {})))
  (let [f (fs/file dir (output-file id))]
    (when (fs/exists? f) (slurp f))))
