(ns devops.bb.feed
  "The feed: what one attached workbook tells its sandbox.

    FEED/
      status.json         feed name, workbook, devops-mode, attached, publisher heartbeat
      events.jsonl        the events, paths mapped, numbered by seq, appended in order
      outputs/<id>.json   run-output answer, one file per finished run

  A sink is where the feed is written, with two verbs: `put' replaces a
  file atomically, `append' adds one line to events.jsonl.  The
  publisher (devops.bb.publish) writes through a sink only, so a sink
  that runs a command elsewhere (--via, --ssh) is one more `Sink'.  Such
  a sink is also a `Batched' one: a result's output and event go in one
  command, so a run costs one round trip.

  The reader -- `status', `events-after', `output' -- is everything the
  sandbox side reads, and the one place a NATS reader would replace."
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [cheshire.core :as json]
   [clojure.string :as str]
   [devops.bb.log :as log])
  (:import
   [java.nio.file Files StandardCopyOption]
   [java.util.concurrent TimeUnit]))

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

(defprotocol Batched
  (put-append [sink path s line]
    "`put' PATH, then `append' LINE, in one go: never the line without
  the file."))

;;; --via CMD: a command that runs `sh' where the feed is

(defn sh-quote
  "S as one sh word."
  [s]
  (str "'" (str/replace (str s) "'" "'\\''") "'"))

(defn- refuse-links
  "sh that exits 3 when any of PATHS, sh words, is a symlink."
  [& paths]
  (str "for p in " (str/join " " paths) "; do"
       " if [ -L \"$p\" ]; then echo \"refusing to write through a symlink: $p\" >&2; exit 3; fi;"
       " done; "))

(defn- utf8-len [^String s] (alength (.getBytes s "UTF-8")))

;; The scripts never wait for EOF on stdin: `docker exec -i' can lose
;; an EOF that comes before the command starts, and the command would
;; hang.  A line is taken with `read', which stops at its newline, and
;; a file, always last, by its length, with `head -c'.

(defn- put-sh
  "sh that takes the next N bytes of stdin, the rest of it, to TO/PATH
  by rename, refusing symlinks."
  [to path n]
  (let [f (str to "/" path)]
    (str "set -e; f=" (sh-quote f) "; "
         (refuse-links (sh-quote to) (sh-quote (str to "/outputs")) "\"$f\"" "\"$f.tmp\"")
         "mkdir -p \"$(dirname \"$f\")\"; "
         ;; noclobber: a .tmp that turns up after the check is not
         ;; written through.
         "rm -f \"$f.tmp\"; set -C; "
         (if (pos? n) (str "head -c " n) ":") " > \"$f.tmp\"; "
         "if [ $(wc -c < \"$f.tmp\") -ne " n " ]; then echo \"short input for $f\" >&2; exit 4; fi; "
         "mv -f \"$f.tmp\" \"$f\"")))

(defn- append-sh
  "sh that appends $ev, a line, to TO/events.jsonl, refusing symlinks."
  [to]
  (let [f (str to "/" events-file)]
    (str "set -e; f=" (sh-quote f) "; "
         (refuse-links (sh-quote to) "\"$f\"")
         "mkdir -p " (sh-quote to) "; "
         "printf '%s\\n' \"$ev\" >> \"$f\"")))

(defn- valid-path!
  "PATH, relative to the feed, or throw: no `..', never absolute."
  [path]
  (when (or (str/starts-with? path "/") (some #{".."} (str/split path #"/")))
    (throw (ex-info (str "not a feed path: " path) {})))
  path)

(defn- run-sh!
  "Run CMD, a vector, then `sh -c SCRIPT', with IN on stdin; throw
  unless it exits 0 within TIMEOUT-MS."
  [cmd script in timeout-ms]
  (let [proc (p/process (into (vec cmd) ["sh" "-c" script])
                        {:in in :out :string :err :string})]
    (when-not (.waitFor ^Process (:proc proc) timeout-ms TimeUnit/MILLISECONDS)
      (p/destroy-tree proc)
      (throw (ex-info (str (first cmd) ": no answer in " timeout-ms " ms") {})))
    (let [r @proc]
      (when-not (zero? (:exit r))
        (throw (ex-info (str (first cmd) " exited " (:exit r) ": "
                             (str/trim (str (:err r))))
                        {:exit (:exit r)}))))))

(defrecord Via [cmd to timeout-ms]
  Sink
  (put [_ path s]
    (run-sh! cmd (put-sh to (valid-path! path) (utf8-len s)) s timeout-ms))
  (append [_ line]
    (run-sh! cmd (str "IFS= read -r ev; " (append-sh to)) (str line "\n") timeout-ms))

  Batched
  (put-append [_ path s line]
    ;; stdin is the event line, then the output.  The output is renamed
    ;; in place before the event is appended, in the one command.
    (run-sh! cmd
             (str "set -e; IFS= read -r ev; "
                  "(" (put-sh to (valid-path! path) (utf8-len s)) "); "
                  (append-sh to))
             (str line "\n" s)
             timeout-ms)))

(defn via
  "The sink that writes the feed into TO, an absolute path, by running
  CMD, a shell-words string such as \"docker exec -i box\", followed by
  `sh -c SCRIPT': one command per put, append or result."
  ([cmd to] (via cmd to {}))
  ([cmd to {:keys [timeout-ms] :or {timeout-ms 30000}}]
   (let [words (p/tokenize (str cmd))
         to    (str/replace (str to) #"(.)/+$" "$1")]
     (when (empty? words) (throw (ex-info "--via wants a command" {})))
     (when-not (str/starts-with? to "/")
       (throw (ex-info (str "--to with --via must be absolute, not " to) {})))
     (->Via words to timeout-ms))))

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
