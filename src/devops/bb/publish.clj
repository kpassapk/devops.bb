(ns devops.bb.publish
  "devops-bb publish: JSON lines on stdin, from one attached workbook's
  Emacs, pushed to its feed (devops.bb.feed).

  Reads no log and asks no Emacs: what it pushes, Emacs handed it.  A
  line is an entry as devops/bb/log.clj builds it, maybe with an
  `output': the run-output answer, {session, status, id, input,
  output}, or only its output text.  A `status' line names the
  workbook and devops-mode; it goes to status.json, not to the events.

  Two threads, so Emacs never waits: the reader drains stdin into a
  queue, capping each output as it takes it, and numbers the events;
  the writer pushes them to the sink in order, retrying with backoff,
  and to a `Batched' sink every queued event in one go.  A sink that
  hangs fills the queue, never the pipe.  An event dropped when the
  queue is full still takes its seq, so the feed has a gap there, and a
  `dropped' event {from, to}, queued with the next event or at EOF,
  says which seqs it lost.

  Each event carries the feed's epoch, its `started'.  A new feed keeps
  the old events.jsonl, as the old epoch's file, and the file of the
  epoch the agent's cursor is in; the rest go.  With --cursors DIR, the
  dir where the agent keeps its cursor, as `devops-bb ack' writes it,
  read through the sink, the heartbeat also prints `{\"behind\":N}' on
  stdout whenever N, the events pushed and not acked, changes: Emacs
  shows it in the mode line."
  (:require
   [babashka.fs :as fs]
   [cheshire.core :as json]
   [clojure.string :as str]
   [devops.bb.feed :as feed])
  (:import
   [java.io BufferedReader]
   [java.time OffsetDateTime]
   [java.util.concurrent LinkedBlockingQueue]))

(def defaults
  {:max-lines    500
   :max-bytes    (* 64 1024)
   :heartbeat-ms 3000
   ;; Past this many unpushed events the reader drops lines, and counts
   ;; them, rather than block stdin or grow without bound.
   :max-queued   10000
   ;; At most this many events, and about this many bytes of them, go in
   ;; one push: the script grows with the events, and one argument is at
   ;; most 128 KB on Linux.
   :batch-max    100
   :batch-bytes  (* 1024 1024)})

(defn- warn [& xs]
  (binding [*out* *err*] (println (str "publish: " (str/join " " xs)))))

;;; The cap

(defn- utf8-len [^String s] (alength (.getBytes s "UTF-8")))

(defn- take-bytes
  "The longest prefix of S within N bytes of UTF-8, whole code points."
  [^String s n]
  (loop [i 0 b 0]
    (if (>= i (count s))
      s
      (let [cp (.codePointAt s i)
            w  (utf8-len (String. (Character/toChars cp)))]
        (if (> (+ b w) n) (subs s 0 i) (recur (+ i (Character/charCount cp)) (+ b w)))))))

(defn- take-last-bytes [^String s n]
  (-> (StringBuilder. s) .reverse str (take-bytes n) StringBuilder. .reverse str))

(defn- fit
  "The first lines of LINES, at most LIMIT of them, within BUDGET bytes."
  [lines limit budget]
  (loop [[l & more :as ls] lines, out [], b 0]
    (let [b (+ b (utf8-len (str l)) 1)]
      (if (or (empty? ls) (>= (count out) limit) (> b budget))
        out
        (recur more (conj out l) b)))))

(defn cap
  "OUTPUT, a string, within MAX-LINES and MAX-BYTES: as is when it fits,
  else its first and last MAX-LINES/2 lines, each half within half the
  bytes, with one `[… N lines cut …]' line between them.  Returns
  {:output s}, plus :truncated true and the full :lines and :bytes when
  cut.  A line too long for its half on its own is cut short, and
  counted as cut."
  [output {:keys [max-lines max-bytes]}]
  (let [trailing (str/ends-with? output "\n")
        body     (cond-> output trailing (subs 0 (dec (count output))))
        lines    (if (= "" output) [] (str/split body #"\n" -1))
        n        (count lines)
        bytes    (utf8-len output)]
    (if (and (<= n max-lines) (<= bytes max-bytes))
      {:output output}
      (let [head-limit (quot max-lines 2)
            tail-limit (- max-lines head-limit)
            ;; Room for the marker line.
            budget     (quot (max 0 (- max-bytes 64)) 2)
            head       (fit lines head-limit budget)
            head-part  (when (and (empty? head) (pos? head-limit) (seq lines))
                         (take-bytes (first lines) budget))
            more       (drop (+ (count head) (if head-part 1 0)) lines)
            tail       (reverse (fit (reverse more) tail-limit budget))
            tail-part  (when (and (empty? tail) (pos? tail-limit) (seq more))
                         (take-last-bytes (last more) budget))
            cut        (- n (count head) (count tail))]
        {:output    (str (str/join "\n" (concat head
                                                (when head-part [head-part])
                                                [(str "[… " cut " lines cut …]")]
                                                (when tail-part [tail-part])
                                                tail))
                         (when trailing "\n"))
         :truncated true
         :lines     n
         :bytes     bytes}))))

;;; Path mapping

(defn parse-maps
  "--map HOST=SANDBOX values, longest HOST first."
  [maps]
  (->> (if (string? maps) [maps] maps)
       (map (fn [m]
              (let [[host sandbox] (str/split (str m) #"=" 2)]
                (when (or (str/blank? host) (nil? sandbox))
                  (throw (ex-info (str "--map wants HOST=SANDBOX, not " m) {})))
                [(str/replace host #"/+$" "") (str/replace sandbox #"/+$" "")])))
       (sort-by (comp - count first))))

(defn map-path
  "FILE, a host path, as the sandbox sees it under MAPS."
  [maps file]
  (or (when (string? file)
        (some (fn [[host sandbox]]
                (when (or (= file host) (str/starts-with? file (str host "/")))
                  (str sandbox (subs file (count host)))))
              maps))
      file))

;;; Lines to items

(defn- answer
  "The run-output answer for entry E, whose :output is the answer or
  its text; :output capped.  Nil when E carries no output.  An answer
  Emacs already cut short, with its rough head/tail cap, says so with
  :truncated and the full :lines and :bytes, which are kept."
  [e opts]
  (let [o (:output e)
        a (cond (map? o)    o
                (string? o) {:session (:session e) :status (or (:status e) "done")
                             :input (:input e) :output o})]
    (when a
      (let [{:keys [output truncated lines bytes]} (cap (str (:output a)) opts)]
        (cond-> (array-map :session (:session a)
                           :status  (or (:status a) "done")
                           :id      (or (:id a) (:id e))
                           :input   (:input a)
                           :output  (when (some? (:output a)) output))
          truncated      (assoc :truncated true :lines lines :bytes bytes)
          (:truncated a) (assoc :truncated true :lines (:lines a) :bytes (:bytes a)))))))

(defn item
  "What the writer pushes for entry E, numbered SEQ, in the feed (:epoch
  OPTS):
  {:seq n :id id :output json-or-nil :event json-line}."
  [e seq-n {:keys [maps name epoch] :as opts}]
  (let [a  (answer e opts)
        id (or (:id a) (:id e))
        a  (when a
             (if (feed/valid-id? id)
               a
               (do (warn "seq" seq-n "has output but its id is not a UUID; output dropped:" (pr-str id))
                   nil)))
        ev (cond-> (dissoc e :output)
             (string? (:output e)) (dissoc :input)
             (:file e)             (update :file #(map-path maps %))
             true                  (assoc :seq seq-n :feed name :epoch epoch))]
    {:seq    seq-n
     :id     id
     :output (some-> a json/generate-string)
     :event  (json/generate-string ev)}))

;;; Threads

(defn- now [] (str (OffsetDateTime/now)))

(defn status-map
  "status.json, from the publisher's STATE."
  [{:keys [name]} state]
  (let [{:keys [workbook devops-mode eof started enqueued pushed dropped]} @state]
    (array-map :feed           name
               :workbook       workbook
               :devops-mode    devops-mode
               :attached       (not eof)
               :started        started
               :publisher-time (now)
               :queued         (- enqueued pushed)
               :dropped        dropped)))

(defn- retry!
  "Call F until it returns without throwing, backing off up to 5 s."
  [what f]
  (loop [delay 100]
    (let [ex (try (f) nil (catch Exception ex ex))]
      (when ex
        (warn what "failed, retrying:" (ex-message ex))
        (Thread/sleep delay)
        (recur (min 5000 (* 2 delay)))))))

(defn- put-status! [{:keys [sink status-lock] :as opts} state]
  (locking status-lock
    (feed/put sink feed/status-file (json/generate-string (status-map opts state)))))

(defn- put-gap!
  "Queue a `dropped' event for the seqs dropped since the last one, if
  any; it takes the next seq, after them.  It is queued even past the
  cap: one event, saying what the cap cost."
  [^LinkedBlockingQueue q state opts]
  (when-let [[a b] (:gap @state)]
    (let [n (inc (:seq @state))]
      (.put q (item {:event "dropped" :from a :to b} n opts))
      (swap! state #(-> % (assoc :seq n :gap nil) (update :enqueued inc) (update :gaps conj [a b]))))))

(defn- eof! [^LinkedBlockingQueue q state opts]
  (put-gap! q state opts)
  (swap! state assoc :eof true)
  (.put q ::eof))

(defn- read-lines!
  "Drain IN into Q, a line at a time, until EOF."
  [in ^LinkedBlockingQueue q state {:keys [maps max-queued] :as opts}]
  (doseq [line (line-seq (BufferedReader. in))
          :when (not (str/blank? line))]
    (let [e (try (json/parse-string line true)
                 (catch Exception _ (warn "not JSON, skipped:" (subs line 0 (min 80 (count line)))) nil))]
      (cond
        (not (map? e)) nil

        (= "status" (:event e))
        (swap! state merge
               (when (contains? e :file) {:workbook (map-path maps (:file e))})
               (when (contains? e :devops-mode) {:devops-mode (:devops-mode e)}))

        (>= (- (:enqueued @state) (:pushed @state)) max-queued)
        (do (when (zero? (:dropped @state)) (warn "queue full: the sink is not keeping up; dropping events"))
            (swap! state #(let [n (inc (:seq %))]
                            (-> % (assoc :seq n) (update :dropped inc)
                                (update :gap (fn [[a]] [(or a n) n]))))))

        :else
        (do (put-gap! q state opts)
            (let [n (inc (:seq @state))]
              (.put q (item e n opts))
              (swap! state #(cond-> (-> % (assoc :seq n) (update :enqueued inc))
                              (and (nil? (:workbook %)) (:file e))
                              (assoc :workbook (map-path maps (:file e))))))))))
  (eof! q state opts))

(defn- acked
  "The agent's cursor, {:epoch e :seq n}, read through the sink from
  (:cursors OPTS); nil without one.  The agent writes it: only an epoch
  string and a seq number are taken from it.  Throws when the sink does."
  [{:keys [sink cursors name]}]
  (when cursors
    (let [s (feed/fetch sink (str (str/replace cursors #"/+$" "") "/" (feed/cursor-file name)))
          {:keys [epoch seq]} (try (some-> s (json/parse-string true)) (catch Exception _ nil))]
      (when (number? seq)
        {:epoch (when (string? epoch) epoch) :seq (long seq)}))))

(defn behind
  "How many of the events of the feed EPOCH, pushed up to seq LAST, the
  cursor ACKED is short of: all of them unless it is in this feed.  The
  seqs in GAPS, [from to] of dropped events, are not events."
  [epoch last gaps acked]
  (let [from (if (= epoch (:epoch acked)) (:seq acked) 0)
        lost (reduce + (for [[a b] gaps] (max 0 (inc (- (min b last) (max a (inc from)))))))]
    (max 0 (- last from lost))))

(defn- start-feed!
  "Start the new feed: keep the old events.jsonl as its epoch's file,
  and the one of the epoch the cursor is in, then put status.json.  The
  old epoch is read from the old status.json.  Never the other way
  round: a reader that sees the new epoch finds the old file in place."
  [state {:keys [sink] :as opts}]
  (let [s   (feed/fetch sink feed/status-file)
        old (try (some-> s (json/parse-string true) :started) (catch Exception _ nil))]
    (feed/rotate sink (when (string? old) old) [(:epoch (acked opts))]))
  (put-status! opts state))

(defn- report-behind!
  "Print {\"behind\":N} on stdout when N changed since the last report."
  [state opts]
  (when (:cursors opts)
    (let [{:keys [started pushed-seq gaps reported]} @state
          n (behind started pushed-seq gaps (acked opts))]
      (when (not= n reported)
        (swap! state assoc :reported n)
        (println (json/generate-string {:behind n}))
        (flush)))))

(defn- batch
  "X and the items queued behind it, in order, at most :batch-max of
  them and about :batch-bytes of outputs and events, never fewer than X
  alone; the rest, and EOF, stay in Q."
  [x ^LinkedBlockingQueue q {:keys [batch-max batch-bytes]}]
  (let [size #(+ (count (:output %)) (count (:event %)))]
    (loop [out [x] b (size x)]
      (let [y (.peek q)]
        (if (and (map? y) (< (count out) batch-max) (<= (+ b (size y)) batch-bytes))
          (do (.poll q) (recur (conj out y) (+ b (size y))))
          out)))))

(defn- write-items!
  "Start a new feed, then push Q's items to the sink in order: each
  output before its event."
  [^LinkedBlockingQueue q state {:keys [sink] :as opts}]
  (retry! "starting the feed" #(start-feed! state opts))
  (loop []
    (let [x (.take q)]
      (when-not (= ::eof x)
        (let [batched (satisfies? feed/Batched sink)
              xs      (if batched (batch x q opts) [x])]
          (if batched
            ;; One command for them all.  A retry pushes them all again:
            ;; outputs are put again, and events that landed the first
            ;; time land twice, in order, so a reader skips them by seq.
            (retry! (str "events " (:seq (first xs)) "-" (:seq (last xs)))
                    #(feed/push sink (mapv (fn [{:keys [id output event]}]
                                             {:path (when output (feed/output-file id))
                                              :output output :event event})
                                           xs)))
            (let [{n :seq :keys [id output event]} x]
              (when output
                (retry! (str "outputs/" id ".json") #(feed/put sink (feed/output-file id) output)))
              (retry! (str "event " n) #(feed/append sink event))))
          (swap! state #(-> % (update :pushed + (count xs)) (assoc :pushed-seq (:seq (last xs)))))
          (recur)))))
  (retry! "status.json" #(put-status! opts state)))

(defn- daemon [f]
  (doto (Thread. ^Runnable (bound-fn* f)) (.setDaemon true) .start))

(defn- sink
  "The sink OPTS name: :sink, else :to through the command :via, else
  :to, a local dir."
  [{:keys [sink via to]}]
  (cond sink sink
        via  (feed/via via to)
        :else (feed/local-dir to)))

(defn start!
  "Start publishing IN, a reader of JSON lines, to (:sink OPTS), else
  (:to OPTS), through the command (:via OPTS) or a local dir.  Returns
  {:state atom :queue q :done promise}; :done is delivered once every
  item is pushed after EOF."
  [opts in]
  (let [opts  (merge defaults opts)
        opts  (assoc opts
                     :sink (sink opts)
                     :maps (parse-maps (:map opts))
                     :name (or (:name opts) (fs/file-name (fs/absolutize (:to opts))))
                     :status-lock (Object.))
        started (now)
        opts  (assoc opts :epoch started)
        ;; :seq is the last seq taken, by an event queued or dropped;
        ;; :gap the seqs dropped since the last `dropped' event, :gaps
        ;; every such [from to]; :pushed-seq the last pushed.
        state (atom {:started started :seq 0 :enqueued 0 :pushed 0 :pushed-seq 0
                     :dropped 0 :gap nil :gaps [] :eof false
                     :workbook nil :devops-mode nil})
        q     (LinkedBlockingQueue.)
        done  (promise)]
    (daemon #(try (read-lines! in q state opts)
                  (catch Throwable t
                    (warn "reading stdin:" (ex-message t))
                    (eof! q state opts))))
    (daemon #(try (write-items! q state opts)
                  (finally (deliver done true))))
    (daemon #(loop []
               (Thread/sleep (:heartbeat-ms opts))
               (when-not (realized? done)
                 (try (put-status! opts state)
                      (report-behind! state opts)
                      (catch Exception ex (warn "heartbeat:" (ex-message ex))))
                 (recur))))
    {:state state :queue q :done done :opts opts}))

(defn run
  "Publish stdin to the feed at --to, through --via if given, until
  stdin closes."
  [opts]
  @(:done (start! opts *in*))
  (System/exit 0))
