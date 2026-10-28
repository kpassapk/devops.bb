(ns devops.bb.cli
  "devops-bb: devops.el from the command line, for agents and scripts.

  Each command asks the operator's Emacs one question through
  devops.bb.emacs and prints the answer as one line of JSON.  An error
  goes to stderr as `error: ...', with exit status 1.  `follow' is the
  exception: it reads the log, not Emacs, and prints a line per event.

  Feed mode, in a sandbox: with DEVOPS_BB_FEED=DIR or --feed DIR, never
  guessed, `status', `follow', `run-output' and `modified' read the feed
  one attached workbook publishes there (devops.bb.feed), `ack' moves
  the agent's cursor in it, `wrote' records the agent's writes, and
  every other command fails: no command asks Emacs."
  (:require
   [babashka.fs :as fs]
   [cheshire.core :as json]
   [clojure.string :as str]
   [devops.bb.emacs :as emacs]
   [devops.bb.feed :as feed]
   [devops.bb.log :as log]
   [devops.bb.publish :as publish])
  (:import
   [java.security MessageDigest]
   [java.time Duration]))

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
  "One short line for event E: what, how it went, who caused it, where,
  and the run ID."
  [e]
  (str/join " " (remove str/blank?
                        [(name (:event e))
                         (cond (:error e)               "error"
                               (= :drift (:event e))    (if (every? #(= "same" (:status %)) (:files e))
                                                          "same" "drift")
                               (= :tangle (:event e))   "done"
                               :else                    (str (:status e)))
                         (some-> (:origin e) name)
                         (str (:file e) ":" (if (true? (:all e)) "all" (or (:line e) "all")))
                         (:id e)])))

(def stale-after
  "A heartbeat that has not changed for this long is stale; the
  publisher writes one every 3 s."
  (Duration/ofSeconds 15))

(defn- dropped
  "Event E as follow prints it: a `dropped' event, the publisher's word
  for the seqs before it that it had to drop, as a notice."
  [e]
  (if (and (= :dropped (:event e)) (number? (:from e)) (number? (:to e)))
    (str (inc (- (:to e) (:from e))) " events dropped (seq " (:from e) "-" (:to e) ")")
    e))

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
                                (map dropped evs))))
          (feed/tails dir epoch after new)))

(defn feed-poll
  "One look at the feed at DIR, from ST, the last look's state, at NOW,
  in ns on this machine's monotonic clock (System/nanoTime): [st items],
  ITEMS the new events and notices, strings, in the order to print
  them.  ST's :epoch and :seq are where reading is: the feed, by its
  `started', and the last seq read.

  A later epoch, in status.json or on an event, is a new feed: what is
  left of the old one comes first, then a notice, then the new feed
  from seq 1, on the first look too.  A `dropped' event is a notice.  A
  notice also says once what the events cannot: the heartbeat is stale,
  the publisher is behind, or it is detached.  The heartbeat is stale
  when `publisher-time' has not changed for `stale-after' on NOW's
  clock: it is never compared with this machine's time, which may be
  off the host's."
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
        st       (cond-> st (not= publisher-time (:beat st)) (assoc :beat publisher-time :beat-at now))
        old      (Duration/ofNanos (- now (:beat-at st)))
        stale?   (and (true? attached) publisher-time (pos? (compare old stale-after)))
        behind?  (and (number? queued) (pos? queued)
                      (some? (:queued-at st)) (not= publisher-time (:queued-at st)))
        notices  (cond-> []
                   (and (nil? status) (not (:missing st)))
                   (conj (str "no feed yet at " dir))

                   new
                   (into (concat tail [(str "feed restarted at " new ": seq starts again at 1")]))

                   (and stale? (not (:stale st)))
                   (conj (str "publisher heartbeat has not changed for " (.getSeconds old) " s"
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
     (into notices (map dropped (:events r)))]))

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

;;; The agent's writes

(def wrote-kept
  "How many of the agent's last writes `wrote' keeps."
  20)

(defn- wrote-path
  "Where the agent's writes to the feed at DIR are kept: beside its
  cursor, FEED.wrote."
  [dir opts]
  (str (fs/strip-ext (cursor-path dir opts)) ".wrote"))

(defn- sha256 [file]
  (let [d (.digest (MessageDigest/getInstance "SHA-256") (fs/read-all-bytes file))]
    (apply str (map #(format "%02x" (bit-and % 0xff)) d))))

(defn- wrote-entries
  "The agent's last writes to the feed at DIR, oldest first, maps."
  [dir opts]
  (let [f (wrote-path dir opts)]
    (when (fs/exists? f)
      (keep #(try (json/parse-string % true) (catch Exception _ nil))
            (str/split-lines (slurp f))))))

(defn wrote
  "FILE: you just wrote it; its sha256 is kept, so follow marks the
  revert Emacs makes of it `origin agent'"
  {:org.babashka/cli {:args->opts [:file] :require [:file]}}
  [{:keys [file] :as opts}]
  (let [dir   (or (feed/configured opts)
                  (fail "wrote needs a feed: set DEVOPS_BB_FEED, or pass --feed"))
        _     (when-not (fs/regular-file? file) (fail (str "no such file: " file)))
        entry (array-map :file (abs file) :sha256 (sha256 file))
        f     (wrote-path dir opts)
        tmp   (str f ".tmp")]
    (fs/create-dirs (fs/parent f))
    (spit tmp (str/join (map #(str (json/generate-string %) "\n")
                             (take-last wrote-kept (concat (wrote-entries dir opts) [entry])))))
    (fs/move tmp f {:replace-existing true :atomic-move true})
    (println (json/generate-string entry))))

(defn mark-agent
  "ITEMS, events and notices, with each revert whose sha256 is in
  HASHES marked `origin agent': Emacs reloaded a file the agent wrote."
  [items hashes]
  (map #(if (and (map? %) (= :revert (:event %)) (contains? hashes (:sha256 %)))
          (assoc % :origin "agent")
          %)
       items))

;;; --wake

(def wake-quiet
  "How long --wake waits after the last event, by default."
  (Duration/ofMillis 750))

(def wake-cap
  "How long --wake waits at most after the first event of a burst."
  (Duration/ofSeconds 3))

(defn- hidden?
  "Whether event E needs no action: a modify, an async execute (its
  result comes), or a save or revert Emacs or the agent caused."
  [e]
  (or (= :modify (:event e))
      (and (= :execute (:event e)) (= "running" (:status e)))
      (contains? #{"auto" "agent"} (:origin e))))

(defn- coalesce
  "ITEMS with only the last save of each file: an edit saved twice is
  one thing to act on."
  [items]
  (let [save? #(and (map? %) (= :save (:event %)))
        lst   (into {} (keep-indexed #(when (save? %2) [(:file %2) %1]) items))]
    (keep-indexed #(when (or (not (save? %2)) (= %1 (lst (:file %2)))) %2) items)))

(defn wake-step
  "--wake's debounce, over WS, its state, ITEMS, what this poll read,
  and NOW, in ns: [ws items], ITEMS what to print now.  ITEMS gather
  until QUIET ns have passed with none, or CAP since the first; then
  the batch is printed, its hidden events left out.

  The agent acks the highest seq of a batch.  When that is a hidden
  event, a notice `ack N' ends the batch, N its seq; a batch that is
  all hidden prints nothing, and its last event is carried: the next
  batch's highest seq passes it, or, with no events, that batch ends
  `ack N' for it.  A restart drops what is carried: an ack in the new
  feed passes the old."
  [ws items now {:keys [quiet cap]}]
  (let [ws (if (seq items)
             (-> ws (update :pending (fnil into []) items)
                 (assoc :last-at now)
                 (update :first-at #(or % now)))
             ws)]
    (if-not (and (seq (:pending ws))
                 (or (>= (- now (:last-at ws)) quiet) (>= (- now (:first-at ws)) cap)))
      [ws []]
      (let [batch (:pending ws)
            last  (reduce #(cond (map? %2) %2
                                 (str/starts-with? %2 "feed restarted") nil
                                 :else %1)
                          (:carry ws) batch)
            shown (coalesce (remove #(and (map? %) (hidden? %)) batch))
            ack?  (and last (hidden? last) (number? (:seq last)))]
        (if (empty? shown)
          [{:carry (when ack? last)} []]
          [{} (cond-> (vec shown) ack? (conj (str "ack " (:seq last))))])))))

(defn- wake-opts
  "--wake's QUIET and CAP, in ns, from OPTS; nil without --wake.  WAKE
  is true, for `wake-quiet', or ms; CAP is `wake-cap', or QUIET if
  longer."
  [{:keys [wake raw]}]
  (when (and wake raw) (fail "--wake and --raw: pass one"))
  (when wake
    (let [ms (cond (true? wake) (.toMillis wake-quiet)
                   (and (int? wake) (>= wake 0)) wake
                   :else (fail (str "--wake MS: not a number of ms: " wake)))]
      {:quiet (* ms 1000000)
       :cap   (* (max ms (.toMillis wake-cap)) 1000000)})))

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
  not acked.  A revert of a file as `wrote' last saw it is the agent's.
  With --wake, what to act on, in batches (`wake-step')."
  [dir {:keys [after json] :as opts}]
  (let [wake   (wake-opts opts)
        cursor (read-cursor dir opts)
        from   (if after
                 (write-cursor! dir opts {:epoch (or (:epoch cursor) (epoch-of dir after))
                                          :seq after})
                 (or cursor {:seq 0}))]
    (follow-loop (fn [[st ws]]
                   (let [now (System/nanoTime)
                         [st items] (feed-poll dir st now)
                         items (if (some #(= :revert (:event %)) (filter map? items))
                                 (mark-agent items (set (keep :sha256 (wrote-entries dir opts))))
                                 items)
                         [ws items] (if wake (wake-step ws items now wake) [ws items])]
                     [[st ws] (map #(cond (string? %) (str "! " %)
                                          json        (json-event %)
                                          :else       (str (:seq %) " " (summary-line %)))
                                   items)]))
                 [{:epoch (:epoch from) :seq (:seq from) :offset 0} {}]
                 opts)))

(defn follow
  "Print a line per new event in the execution log, for files under DIR.
  In feed mode, every event of the feed after the cursor, the last one
  acked, or after --after SEQ, which moves the cursor.  It stops when
  stdout is closed, after the first poll with events (--once), or after
  N events (--count N).  --wake [MS], in feed mode: only what needs
  action, MS (750) after the last event; --raw, every event, the
  default"
  {:org.babashka/cli {:coerce {:after :long :count :long :once :boolean :raw :boolean}}}
  [{:keys [dir log json] :as opts}]
  (when-let [feed-dir (feed/configured opts)]
    (follow-feed feed-dir opts))
  (when (:wake opts) (fail "--wake needs a feed: set DEVOPS_BB_FEED, or pass --feed"))
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
