(ns devops.bb.cli
  "devops-bb: devops.el from the command line, for agents and scripts.

  Each command asks the operator's Emacs one question through
  devops.bb.emacs and prints the answer as one line of JSON.  An error
  goes to stderr as `error: ...', with exit status 1.  `follow' is the
  exception: it reads the log, not Emacs, and prints a line per event.

  Feed mode, in a sandbox: with DEVOPS_BB_FEED=DIR or --feed DIR, never
  guessed, `status', `follow', `run-output' and `modified' read the feed
  one attached workbook publishes there (devops.bb.feed), `ack' moves
  the agent's cursor in it, and every other command fails: no command
  asks Emacs."
  (:require
   [babashka.fs :as fs]
   [cheshire.core :as json]
   [clojure.string :as str]
   [devops.bb.emacs :as emacs]
   [devops.bb.feed :as feed]
   [devops.bb.log :as log]
   [devops.bb.publish :as publish])
  (:import
   [java.time Duration OffsetDateTime]))

(defn- elisp-str [s] (pr-str (str s)))

(defn- elisp-opt [s] (if s (elisp-str s) "nil"))

(defn- abs [file] (str (fs/absolutize (fs/expand-home file))))

(defn- fail [msg]
  (binding [*out* *err*] (println (str "error: " msg)))
  (System/exit 1))

(defn- answer
  "Print what FORM, elisp, evaluates to in Emacs, as JSON."
  [form]
  (try
    (println (json/generate-string (emacs/eval-json form)))
    (catch Exception e (fail (ex-message e)))))

;;; Feed mode

(def needs-emacs "needs Emacs, which this sandbox cannot reach")

(defn- no-emacs!
  "Fail when OPTS put the command in feed mode."
  [opts]
  (when (feed/configured opts) (fail needs-emacs)))

(defn- feed-status [dir]
  (or (feed/status dir) (fail (str "no feed at " dir ": status.json is missing"))))

(defn- edited?
  "Whether the last modify, save, revert or kill event in the feed at
  DIR is a modify: Emacs holds unsaved edits to the workbook."
  [dir]
  (= :modify (->> (:events (feed/events-after dir {}))
                  (filter (comp #{:modify :save :revert :kill} :event))
                  last
                  :event)))

;;; Commands

(defn status
  "Whether Emacs is reachable, devops-mode and devops-bb-log-mode are on, and where it logs"
  [opts]
  (if-let [dir (feed/configured opts)]
    (println (json/generate-string (feed-status dir)))
    (answer "(progn (require 'devops-bb)
             (list (cons :devops-mode (if (bound-and-true-p devops-mode) t :json-false))
                   (cons :log-mode (if (bound-and-true-p devops-bb-log-mode) t :json-false))
                   (cons :execution-log (and devops-bb-execution-log
                                             (expand-file-name devops-bb-execution-log)))))")))

(defn output
  "What the src block at LINE of FILE printed: session, else #+RESULTS"
  {:org.babashka/cli {:args->opts [:file :line]
                      :coerce {:line :long}
                      :require [:file :line]}}
  [{:keys [file line tag] :as opts}]
  (no-emacs! opts)
  (answer (format "(progn (require 'devops-bb-scripting)
                     (devops-bb-scripting-block-output %s %d %s))"
                  (elisp-str (abs file)) line (elisp-opt tag))))

(defn run-output
  "What the run ID printed, from its session alone"
  {:org.babashka/cli {:args->opts [:id] :require [:id]}}
  [{:keys [id session] :as opts}]
  (when-let [dir (feed/configured opts)]
    (when-not (feed/valid-id? id) (fail (str "not a run ID: " id)))
    (println (or (some-> (feed/output dir id) str/trim-newline)
                 (json/generate-string (array-map :session nil :status "not-found"
                                                  :id id :input nil :output nil))))
    (System/exit 0))
  ;; Without SESSION, every live session is asked: an ID is unique.
  (answer (format "(progn (require 'devops-bb-scripting)
                     (let ((id %s) (session %s))
                       (if session
                           (devops-bb-scripting-run-output session id)
                         (or (seq-some
                              (lambda (s)
                                (let ((r (devops-bb-scripting-run-output (alist-get :name s) id)))
                                  (and (not (eq (alist-get :status r) :not-found)) r)))
                              (devops-bb-scripting-sessions))
                             (list (cons :session nil) (cons :status :not-found)
                                   (cons :id id) (cons :input nil) (cons :output nil))))))"
                  (elisp-str id) (elisp-opt session))))

(defn sessions
  "Every live async session: name, directory, state, prompt, id"
  [opts]
  (no-emacs! opts)
  (answer "(progn (require 'devops-bb-scripting) (vconcat (devops-bb-scripting-sessions)))"))

(defn modified
  "Whether Emacs holds unsaved edits to FILE: true, or false.
  In feed mode FILE may be left out, and must be the workbook."
  {:org.babashka/cli {:args->opts [:file]}}
  [{:keys [file] :as opts}]
  (when-let [dir (feed/configured opts)]
    (let [workbook (:workbook (feed-status dir))]
      (when (and file (not= (abs file) (some-> workbook abs)))
        (fail (str file ": not this sandbox's workbook, " workbook)))
      (println (json/generate-string (edited? dir)))
      (System/exit 0)))
  (when-not file (fail "FILE is required"))
  (answer (format "(let ((b (find-buffer-visiting %s)))
                     (if (and b (buffer-modified-p b)) t :json-false))"
                  (elisp-str (abs file)))))

(defn revert
  "Reload FILE's buffer from disk, if Emacs visits it and it has no unsaved edits"
  {:org.babashka/cli {:args->opts [:file] :require [:file]}}
  [{:keys [file] :as opts}]
  (no-emacs! opts)
  (answer (format "(let ((b (find-buffer-visiting %s)))
                     (cond ((not b) :not-visited)
                           ((buffer-modified-p b) (error \"%%s has unsaved edits\" (buffer-name b)))
                           (t (with-current-buffer b (revert-buffer t t t)) :reverted)))"
                  (elisp-str (abs file)))))

(defn tools
  "The tools.org blocks loaded for #+call:, matching REGEXP if given"
  {:org.babashka/cli {:args->opts [:regexp]}}
  [{:keys [regexp] :as opts}]
  (no-emacs! opts)
  (answer (format "(progn (require 'devops)
                     (vconcat
                      (mapcar (lambda (e)
                                (list (cons :name (format \"%%s\" (nth 0 e)))
                                      (cons :language (nth 1 e))
                                      (cons :vars (vconcat (mapcar #'cdr (nth 2 e))))))
                              (devops-org-tool-blocks %s))))"
                  (elisp-opt regexp))))

(defn drift
  "Compare what FILE tangles with each target: all headings, or one"
  {:org.babashka/cli {:args->opts [:file] :require [:file]}}
  [{:keys [file heading custom-id] :as opts}]
  (no-emacs! opts)
  (answer (format "(progn (require 'devops-drift)
                     (vconcat %s))"
                  (cond heading   (format "(devops-drift-headline %s %s)"
                                          (elisp-str (abs file)) (elisp-str heading))
                        custom-id (format "(devops-drift-custom-id %s %s)"
                                          (elisp-str (abs file)) (elisp-str custom-id))
                        :else     (format "(devops-drift-all %s)" (elisp-str (abs file)))))))

;;; Publishing a feed

(defn publish
  "Push the JSON lines on stdin to the feed at --to, for one sandbox;
  with --via CMD, CMD runs `sh' where the feed is; with --cursors DIR,
  read the agent's acks there and print how far behind it is"
  {:org.babashka/cli {:require [:to]
                      :coerce {:map [] :max-lines :long :max-bytes :long}
                      :restrict [:to :via :map :max-lines :max-bytes :name :cursors]}}
  [opts]
  (try
    (publish/run opts)
    (catch clojure.lang.ExceptionInfo e (fail (ex-message e)))))

;;; Following the log

(defn summary-line
  "One short line for event E: what, how it went, where, and the run ID."
  [e]
  (str/join " " (remove str/blank?
                        [(name (:event e))
                         (cond (:error e)               "error"
                               (= :drift (:event e))    (if (every? #(= "same" (:status %)) (:files e))
                                                          "same" "drift")
                               (= :tangle (:event e))   "done"
                               :else                    (str (:status e)))
                         (str (:file e) ":" (if (true? (:all e)) "all" (or (:line e) "all")))
                         (:id e)])))

(def stale-after
  "A heartbeat older than this is stale; the publisher writes one every 3 s."
  (Duration/ofSeconds 15))

(defn- age [time now]
  (try (Duration/between (OffsetDateTime/parse time) now)
       (catch Exception _ nil)))

(defn- tail-items
  "Notices and events for what is left of the feeds from ST's epoch, up
  to the feed NEW, oldest first."
  [dir {:keys [epoch] after :seq} new]
  (mapcat (fn [{e :epoch evs :events gone :gone}]
            (cond
              gone        [(str "feed " e " is gone: any events of it after seq " after " are lost")]
              (seq evs)   (cons (str "the rest of feed " e ", which has ended: seq "
                                     (:seq (first evs)) "-" (:seq (last evs))
                                     "; ack these with --epoch " e)
                                evs)))
          (feed/tails dir epoch after new)))

(defn feed-poll
  "One look at the feed at DIR, from ST, the last look's state, at NOW:
  [st items], ITEMS the new events and notices, strings, in the order
  to print them.  ST's :epoch and :seq are where reading is: the feed,
  by its `started', and the last seq read.

  A later epoch, in status.json or on an event, is a new feed: what is
  left of the old one comes first, then a notice, then the new feed
  from seq 1, on the first look too.  A notice also says once what the
  events cannot: the heartbeat is stale, the publisher is behind, or it
  is detached."
  [dir st now]
  (let [{:keys [started attached publisher-time queued] :as status} (feed/status dir)
        st       (cond-> st (and started (nil? (:epoch st))) (assoc :epoch started))
        r        (feed/events-after dir st)
        new      (or (:new-epoch r)
                     (when (and started (pos? (feed/epoch-compare started (:epoch st)))) started))
        tail     (when new (tail-items dir st new))
        st       (cond-> st
                   new (-> (assoc :epoch new :seq 0 :offset 0) (dissoc :detached :stale :behind)))
        r        (if new (feed/events-after dir st) r)
        st       (merge st (select-keys r [:offset :seq :epoch]))
        old      (some-> publisher-time (age now))
        stale?   (and (true? attached) old (pos? (compare old stale-after)))
        behind?  (and (number? queued) (pos? queued)
                      (some? (:queued-at st)) (not= publisher-time (:queued-at st)))
        notices  (cond-> []
                   (and (nil? status) (not (:missing st)))
                   (conj (str "no feed yet at " dir))

                   new
                   (into (concat tail [(str "feed restarted at " new ": seq starts again at 1")]))

                   (and stale? (not (:stale st)))
                   (conj (str "publisher heartbeat is " (.getSeconds ^Duration old) " s old"
                              (when (pos? (or queued 0)) (str "; publisher is " queued " events behind"))))

                   (and behind? (not stale?) (not (:behind st)))
                   (conj (str "publisher is " queued " events behind"))

                   (and (false? attached) (not (:detached st)))
                   (conj "feed detached: the workbook's publisher has stopped; no new events until the operator attaches again"))]
    [(cond-> (assoc st
                    :missing (nil? status)
                    :stale (boolean stale?)
                    :detached (false? attached)
                    :queued-at (when (and (number? queued) (pos? queued))
                                 (or (:queued-at st) publisher-time)))
       (or behind? (:behind st)) (assoc :behind (boolean (and (number? queued) (pos? queued)))))
     (into notices (:events r))]))

;;; The cursor

(defn- cursor-path
  "Where the cursor of the feed at DIR is kept: --cursor-dir, else
  DEVOPS_BB_CURSOR_DIR, else ~/.cache/devops-bb/cursor, then the feed's
  name."
  [dir opts]
  (str (fs/path (fs/expand-home (or (:cursor-dir opts) (System/getenv "DEVOPS_BB_CURSOR_DIR")
                                    "~/.cache/devops-bb/cursor"))
                (feed/cursor-file (or (:feed (feed/status dir)) (fs/file-name dir))))))

(defn read-cursor
  "The cursor of the feed at DIR, {:epoch e :seq n}: the last event the
  agent acked.  Nil when there is none."
  [dir opts]
  (let [f (cursor-path dir opts)]
    (when (fs/exists? f)
      (let [{:keys [epoch seq]} (try (json/parse-string (slurp f) true) (catch Exception _ nil))]
        (when (number? seq)
          {:epoch (when (string? epoch) epoch) :seq (long seq)})))))

(defn- write-cursor!
  "Make CURSOR the feed at DIR's, by rename; CURSOR."
  [dir opts cursor]
  (let [f   (cursor-path dir opts)
        tmp (str f ".tmp")]
    (fs/create-dirs (fs/parent f))
    (spit tmp (json/generate-string (array-map :feed (fs/file-name (fs/strip-ext f))
                                               :epoch (:epoch cursor)
                                               :seq (:seq cursor))))
    (fs/move tmp f {:replace-existing true :atomic-move true})
    cursor))

(defn- ahead?
  "Whether the cursor A is past B: a later feed, or a later seq in it."
  [a b]
  (or (nil? b)
      (let [c (feed/epoch-compare (:epoch a) (:epoch b))]
        (if (and (:epoch a) (:epoch b) (not (zero? c)))
          (pos? c)
          (> (:seq a) (:seq b))))))

(defn ack
  "SEQ [--epoch E]: you handled the feed's events up to SEQ; the cursor
  moves there, never back.  E names an earlier feed, for its rest"
  {:org.babashka/cli {:args->opts [:seq] :coerce {:seq :long} :require [:seq]}}
  [{n :seq :keys [epoch] :as opts}]
  (let [dir     (or (feed/configured opts)
                    (fail "ack needs a feed: set DEVOPS_BB_FEED, or pass --feed"))
        started (:started (feed-status dir))
        last    (:seq (feed/events-after dir {:epoch started}))]
    (when (and (nil? epoch) (> n last))
      (fail (str "seq " n " is past this feed's last, " last
                 "; for an earlier feed's, pass --epoch")))
    (let [cursor (read-cursor dir opts)
          new    {:epoch (or epoch started) :seq n}]
      (println (json/generate-string
                (if (ahead? new cursor) (write-cursor! dir opts new) cursor))))))

;;; follow

(defn- json-event [e]
  (json/generate-string (-> e (dissoc :time) (assoc :time (:time-str e)) (dissoc :time-str))))

(defn- notice? [line] (str/starts-with? line "! "))

(defn- print-lines!
  "Print LINES on stdout, and exit 0 if stdout is closed: a write to a
  closed pipe only sets System/out's error flag, so `follow | head -n1'
  would otherwise run on.  *out* writes through System/out."
  [lines]
  (doseq [l lines] (println l))
  (flush)
  (when (.checkError System/out) (System/exit 0)))

(defn- take-events
  "LINES up to the Nth that is not a notice, and how many are left."
  [lines n]
  (loop [[l & more :as ls] lines, out [], n n]
    (if (or (empty? ls) (<= n 0))
      [out n]
      (recur more (conj out l) (if (notice? l) n (dec n))))))

(defn- follow-loop
  "From ST, every 300 ms, print the lines (POLL st) says, [st lines],
  until stdout is closed, the first poll with events with --once, or
  --count N events.  Notices, lines starting `! ', print but do not
  count, and the loop waits on."
  [poll st {:keys [once] n :count}]
  (loop [st st left n]
    (let [[st lines] (poll st)
          [lines left] (if left (take-events lines left) [lines nil])]
      (print-lines! lines)
      (when (or (and once (some (complement notice?) lines)) (and left (<= left 0)))
        (System/exit 0))
      (Thread/sleep 300)
      (recur st left))))

(defn- epoch-of
  "The feed seq N is of, with no cursor to say: this one, unless N is
  past its last; then the one before, if it is kept."
  [dir n]
  (let [started (:started (feed/status dir))]
    (if (> n (:seq (feed/events-after dir {:epoch started})))
      (or (:epoch (last (feed/rotated dir))) started)
      started)))

(defn- follow-feed
  "Follow the feed at DIR from the cursor, else from its start; with
  --after N, from N, in the cursor's feed, and the cursor is moved
  there.  The cursor only moves on --after and `ack': follow keeps
  where it read in memory, so a restarted follow gives again what was
  not acked."
  [dir {:keys [after json] :as opts}]
  (let [cursor (read-cursor dir opts)
        from   (if after
                 (write-cursor! dir opts {:epoch (or (:epoch cursor) (epoch-of dir after))
                                          :seq after})
                 (or cursor {:seq 0}))]
    (follow-loop (fn [st]
                   (let [[st items] (feed-poll dir st (OffsetDateTime/now))]
                     [st (map #(cond (string? %) (str "! " %)
                                     json        (json-event %)
                                     :else       (str (:seq %) " " (summary-line %)))
                              items)]))
                 {:epoch (:epoch from) :seq (:seq from) :offset 0}
                 opts)))

(defn follow
  "Print a line per new event in the execution log, for files under DIR.
  In feed mode, every event of the feed after the cursor, the last one
  acked, or after --after SEQ, which moves the cursor.  It stops when
  stdout is closed, after the first poll with events (--once), or after
  N events (--count N)"
  {:org.babashka/cli {:coerce {:after :long :count :long :once :boolean}}}
  [{:keys [dir log json] :as opts}]
  (when-let [feed-dir (feed/configured opts)]
    (follow-feed feed-dir opts))
  (let [path (or (some-> log fs/expand-home str) (emacs/log-path)
                 (fail "no log: devops-bb-execution-log is nil or Emacs is out of reach; pass --log"))
        dir  (str (fs/normalize (fs/absolutize (or dir "."))) "/")]
    (follow-loop (fn [offset]
                   (let [{:keys [events offset]} (log/read-from path offset)]
                     [offset (for [e events
                                      :when (str/starts-with? (str (:file e)) dir)]
                                  (if json (json-event e) (summary-line e)))]))
                 (:offset (log/read-from path 0))
                 opts)))
