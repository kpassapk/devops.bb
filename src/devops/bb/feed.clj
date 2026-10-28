(ns devops.bb.feed
  "The feed: what one attached workbook tells its sandbox.

    FEED/
      status.json         feed name, workbook, devops-mode, attached, publisher heartbeat
      events.jsonl        the events, paths mapped, numbered by seq, appended in order
      events.<epoch>.jsonl  an earlier feed's events, kept by the next publisher
      outputs/<id>.json   run-output answer, one file per finished run

  A feed's epoch is its `started'; every event carries it, so a reader
  can tell the feed it read from a new one, whose seq starts again at 1.
  A new publisher keeps the old events.jsonl as the old epoch's file,
  and the one of the epoch the agent last acked, and removes the rest.

  A sink is where the feed is written, with four verbs: `put' replaces a
  file atomically, `append' adds one line to events.jsonl, `rotate'
  starts a new events.jsonl, keeping the old one, and `fetch' reads a
  file where the feed is.  The
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
   [java.time OffsetDateTime]
   [java.util.concurrent TimeUnit]))

;;; Layout

(def status-file "status.json")
(def events-file "events.jsonl")

(defn output-file [id] (str "outputs/" id ".json"))

(defn rotated-file
  "Where the feed of EPOCH keeps its events once a new one started."
  [epoch]
  (str "events." (str/replace (str epoch) #"[^A-Za-z0-9.+-]" "_") ".jsonl"))

(def ^:private rotated-glob "events.*.jsonl")

(defn cursor-file
  "The name of the file that holds the agent's cursor in the feed NAME."
  [name]
  (str (str/replace (str name) #"[^A-Za-z0-9._-]" "_") ".json"))

(defn- instant [epoch]
  (try (.toInstant (OffsetDateTime/parse (str epoch))) (catch Exception _ nil)))

(defn epoch-compare
  "Compare two epochs, `started' timestamps, by the instant they name."
  [a b]
  (let [ia (instant a) ib (instant b)]
    (if (and ia ib) (compare ia ib) (compare (str a) (str b)))))

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
    "Append LINE, one JSON line, to events.jsonl.")
  (rotate [sink epoch keep]
    "Start an empty events.jsonl.  The old one, if any, becomes EPOCH's
  rotated file, unless EPOCH is nil; every other rotated file goes but
  those of the epochs in KEEP.")
  (fetch [sink path]
    "PATH, relative to the feed or absolute, where the feed is: its first
  64 KB as a string, or nil when it is not a file."))

(def ^:private fetch-limit (* 64 1024))

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
    (spit (str (fs/path dir events-file)) (str line "\n") :append true))
  (rotate [this epoch keep]
    (let [ev   (fs/path dir events-file)
          keep (set (map rotated-file (remove nil? (cons epoch keep))))]
      (fs/create-dirs dir)
      (when (and epoch (fs/exists? ev))
        (Files/move ev (fs/path dir (rotated-file epoch))
                    (into-array [StandardCopyOption/ATOMIC_MOVE
                                 StandardCopyOption/REPLACE_EXISTING])))
      (doseq [f (fs/glob dir rotated-glob)
              :when (not (keep (fs/file-name f)))]
        (fs/delete-if-exists f))
      (put this events-file "")))
  (fetch [_ path]
    (let [f (fs/file (fs/path dir path))]
      (when (fs/regular-file? f)
        (let [s (slurp f)]
          (subs s 0 (min (count s) fetch-limit)))))))

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

(defn- rotate-sh
  "sh that moves TO/events.jsonl to EPOCH's rotated file, removes the
  rotated files of epochs not in KEEP, and starts an empty events.jsonl,
  refusing symlinks."
  [to epoch keep]
  (let [keep (distinct (map rotated-file (remove nil? (cons epoch keep))))]
    (str "set -e; d=" (sh-quote to) "; f=\"$d/" events-file "\"; "
         (refuse-links "\"$d\"" "\"$f\"" "\"$f.tmp\"")
         "mkdir -p \"$d\"; "
         (when epoch
           (str "r=\"$d/\"" (sh-quote (rotated-file epoch)) "; "
                (refuse-links "\"$r\"")
                "if [ -f \"$f\" ]; then mv -f \"$f\" \"$r\"; fi; "))
         "for g in \"$d\"/" rotated-glob "; do"
         " if [ -e \"$g\" ] || [ -L \"$g\" ]; then "
         (if (seq keep)
           (str "case \"${g##*/}\" in " (str/join "|" (map sh-quote keep)) ") ;; *) rm -f \"$g\";; esac;")
           "rm -f \"$g\";")
         " fi; done; "
         "rm -f \"$f.tmp\"; set -C; : > \"$f.tmp\"; mv -f \"$f.tmp\" \"$f\"")))

(defn- fetch-sh
  "sh that prints `+' and the first bytes of FILE, or nothing when FILE
  is not a file."
  [file]
  (str "f=" (sh-quote file) "; "
       "if [ -f \"$f\" ]; then printf +; head -c " fetch-limit " \"$f\"; fi"))

(defn- run-sh!
  "Run CMD, a vector, then `sh -c SCRIPT', with IN on stdin; its
  stdout.  Throw unless it exits 0 within TIMEOUT-MS."
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
                        {:exit (:exit r)})))
      (:out r))))

(defrecord Via [cmd to timeout-ms]
  Sink
  (put [_ path s]
    (run-sh! cmd (put-sh to (valid-path! path) (utf8-len s)) s timeout-ms))
  (append [_ line]
    (run-sh! cmd (str "IFS= read -r ev; " (append-sh to)) (str line "\n") timeout-ms))
  (rotate [_ epoch keep]
    (run-sh! cmd (rotate-sh to epoch keep) "" timeout-ms))
  (fetch [_ path]
    (let [file (if (str/starts-with? path "/") path (str to "/" (valid-path! path)))
          out  (run-sh! cmd (fetch-sh file) "" timeout-ms)]
      (when (str/starts-with? out "+") (subs out 1))))

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
  "The events appended to DIR's FILE, events.jsonl by default, since
  byte OFFSET whose :seq is above SEQ: {:events [...] :offset n :seq
  last-seen :epoch e}.  A retried append may land twice; its seq is not
  above the last, so it is skipped.  A file shorter than OFFSET was
  replaced: it is read from the start.

  Events are of the feed EPOCH, the first one's when EPOCH is nil.  An
  event of a later epoch is a new feed: reading stops there, with
  :new-epoch its epoch, and the new feed is for the caller to read from
  the start.  One of an earlier epoch, a late write of a publisher that
  was stopping, is skipped.  An event with no epoch, from an older
  publisher, is taken by seq alone."
  [dir {after :seq :keys [offset epoch file] :or {offset 0 after 0 file events-file}}]
  (let [{:keys [events offset]} (log/read-from (str (fs/path dir file)) offset)]
    (loop [[e & more :as es] events, last-seq after, epoch epoch, out []]
      (let [done {:events out :offset offset :seq last-seq :epoch epoch}
            n    (:seq e)
            e-ep (:epoch e)]
        (cond
          (empty? es)                              done
          (and e-ep epoch (not= e-ep epoch))       (if (pos? (epoch-compare e-ep epoch))
                                                     (assoc done :new-epoch e-ep)
                                                     (recur more last-seq epoch out))
          (and (number? n) (> n last-seq))         (recur more n (or epoch e-ep) (conj out e))
          :else                                    (recur more last-seq (or epoch e-ep) out))))))

(defn rotated
  "The rotated files in DIR, oldest epoch first: [{:epoch e :file f}],
  each epoch read from the file's first event."
  [dir]
  (->> (fs/glob dir rotated-glob)
       (keep (fn [f]
               (let [name (fs/file-name f)
                     ep   (:epoch (first (:events (log/read-from (str f) 0))))]
                 (when (and ep (= name (rotated-file ep)))
                   {:epoch ep :file name}))))
       (sort-by :epoch epoch-compare)))

(defn tails
  "What DIR still holds of the feeds from EPOCH, after SEQ, up to the
  feed NEW, oldest first: [{:epoch e :events [...]}], with :gone true
  for EPOCH when its file is not kept.  EPOCH's events after AFTER, then
  every event of each feed between EPOCH and NEW."
  [dir epoch after new]
  (when epoch
    (let [own   (rotated-file epoch)
          its   (if (fs/exists? (fs/path dir own))
                  {:epoch epoch :events (:events (events-after dir {:file own :seq after :epoch epoch}))}
                  {:epoch epoch :events [] :gone true})
          later (for [{e :epoch f :file} (rotated dir)
                      :when (and (pos? (epoch-compare e epoch))
                                 (or (nil? new) (neg? (epoch-compare e new))))]
                  {:epoch e :events (:events (events-after dir {:file f :epoch e}))})]
      (into [its] later))))

(defn output
  "outputs/ID.json of the feed in DIR, as written, or nil.  Throws for
  an ID that is not a UUID."
  [dir id]
  (when-not (valid-id? id)
    (throw (ex-info (str "not a run ID: " id) {})))
  (let [f (fs/file dir (output-file id))]
    (when (fs/exists? f) (slurp f))))
