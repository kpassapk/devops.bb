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
  the writer pushes them to the sink in order, retrying with backoff.
  A sink that hangs fills the queue, never the pipe."
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
   :max-queued   10000})

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
  its text; :output capped.  Nil when E carries no output."
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
          truncated (assoc :truncated true :lines lines :bytes bytes))))))

(defn item
  "What the writer pushes for entry E, numbered SEQ:
  {:seq n :id id :output json-or-nil :event json-line}."
  [e seq-n {:keys [maps name] :as opts}]
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
             true                  (assoc :seq seq-n :feed name))]
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
            (swap! state update :dropped inc))

        :else
        (let [n (inc (:enqueued @state))]
          (.put q (item e n opts))
          (swap! state #(cond-> (assoc % :enqueued n)
                          (and (nil? (:workbook %)) (:file e))
                          (assoc :workbook (map-path maps (:file e)))))))))
  (swap! state assoc :eof true)
  (.put q ::eof))

(defn- write-items!
  "Start a new feed, then push Q's items to the sink in order: each
  output before its event."
  [^LinkedBlockingQueue q state {:keys [sink] :as opts}]
  (retry! "starting the feed" #(feed/put sink feed/events-file ""))
  (retry! "status.json" #(put-status! opts state))
  (loop []
    (let [x (.take q)]
      (when-not (= ::eof x)
        (let [{n :seq :keys [id output event]} x]
          (when output
            (retry! (str "outputs/" id ".json") #(feed/put sink (feed/output-file id) output)))
          (retry! (str "event " n) #(feed/append sink event))
          (swap! state update :pushed inc)
          (recur)))))
  (retry! "status.json" #(put-status! opts state)))

(defn- daemon [f]
  (doto (Thread. ^Runnable (bound-fn* f)) (.setDaemon true) .start))

(defn start!
  "Start publishing IN, a reader of JSON lines, to (:sink OPTS), else a
  local dir at (:to OPTS).  Returns {:state atom :queue q :done promise};
  :done is delivered once every item is pushed after EOF."
  [opts in]
  (let [opts  (merge defaults opts)
        opts  (assoc opts
                     :sink (or (:sink opts) (feed/local-dir (:to opts)))
                     :maps (parse-maps (:map opts))
                     :name (or (:name opts) (fs/file-name (fs/absolutize (:to opts))))
                     :status-lock (Object.))
        state (atom {:started (now) :enqueued 0 :pushed 0 :dropped 0 :eof false
                     :workbook nil :devops-mode nil})
        q     (LinkedBlockingQueue.)
        done  (promise)]
    (daemon #(try (read-lines! in q state opts)
                  (catch Throwable t
                    (warn "reading stdin:" (ex-message t))
                    (swap! state assoc :eof true)
                    (.put q ::eof))))
    (daemon #(try (write-items! q state opts)
                  (finally (deliver done true))))
    (daemon #(loop []
               (Thread/sleep (:heartbeat-ms opts))
               (when-not (realized? done)
                 (try (put-status! opts state)
                      (catch Exception ex (warn "heartbeat:" (ex-message ex))))
                 (recur))))
    {:state state :queue q :done done :opts opts}))

(defn run
  "Publish stdin to the feed at --to until stdin closes."
  [opts]
  @(:done (start! opts *in*))
  (System/exit 0))
