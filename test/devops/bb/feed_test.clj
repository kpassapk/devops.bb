(ns devops.bb.feed-test
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [cheshire.core :as json]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [devops.bb.cli :as cli]
   [devops.bb.feed :as feed]
   [devops.bb.publish :as publish]))

(def id1 "0b9e1b1a-1111-4222-8333-444455556666")
(def id2 "0b9e1b1a-1111-4222-8333-777788889999")

(defn- numbered [n] (str/join (map #(str "line " % "\n") (range 1 (inc n)))))

(defn- bytes-of [s] (count (.getBytes ^String s "UTF-8")))

(defn- temp-dir [] (str (fs/create-temp-dir)))

(defn fake-emacsclient!
  "A dir holding an `emacsclient' that leaves a `called' file in it."
  []
  (let [dir (temp-dir)
        f   (str (fs/path dir "emacsclient"))]
    (spit f (str "#!/bin/sh\ntouch " dir "/called\nexit 1\n"))
    (fs/set-posix-file-permissions f "rwxr-xr-x")
    dir))

(defn bb-env
  "This environment, with FAKE first on PATH, no Emacs socket, and the
  cursors in FAKE/cursor, not ~/.cache."
  [fake extra]
  (-> (into {} (System/getenv))
      (dissoc "EMACS_SOCKET_NAME" "DEVOPS_BB_FEED")
      (assoc "PATH" (str fake ":" (System/getenv "PATH"))
             "DEVOPS_BB_CURSOR_DIR" (str (fs/path fake "cursor")))
      (merge extra)))

(def bb-edn (str (fs/absolutize "bb.edn")))

;;; The cap

(deftest cap-lines-test
  (let [{:keys [output truncated lines bytes]} (publish/cap (numbered 2000) publish/defaults)
        kept (str/split-lines output)]
    (is truncated)
    (is (= 2000 lines))
    (is (= (bytes-of (numbered 2000)) bytes))
    (is (= 501 (count kept)))
    (is (= "line 1" (first kept)))
    (is (= "line 250" (nth kept 249)))
    (is (= "[… 1500 lines cut …]" (nth kept 250)))
    (is (= "line 1751" (nth kept 251)))
    (is (= "line 2000" (last kept)))
    (is (str/ends-with? output "\n"))))

(deftest cap-fits-test
  (is (= {:output "a\nb\n"} (publish/cap "a\nb\n" publish/defaults)))
  (is (= {:output ""} (publish/cap "" publish/defaults)))
  (is (= {:output (numbered 500)} (publish/cap (numbered 500) publish/defaults))))

(deftest cap-bytes-test
  (testing "few lines, too many bytes: within the byte cap"
    (let [s (str/join (repeat 100 (str (apply str (repeat 1000 "x")) "\n")))
          {:keys [output truncated lines bytes]} (publish/cap s publish/defaults)]
      (is truncated)
      (is (= 100 lines))
      (is (= 100100 bytes))
      (is (<= (bytes-of output) (* 64 1024)))
      (is (re-find #"\[… \d+ lines cut …\]" output))))
  (testing "one line longer than the cap is cut short"
    (let [{:keys [output truncated lines]} (publish/cap (apply str (repeat 200000 "é")) publish/defaults)]
      (is truncated)
      (is (= 1 lines))
      (is (<= (bytes-of output) (* 64 1024)))
      (is (str/ends-with? output "[… 1 lines cut …]"))))
  (testing "--max-lines and --max-bytes"
    (let [{:keys [output]} (publish/cap (numbered 30) {:max-lines 10 :max-bytes 100000})]
      (is (= ["line 1" "line 2" "line 3" "line 4" "line 5" "[… 20 lines cut …]"
              "line 26" "line 27" "line 28" "line 29" "line 30"]
             (str/split-lines output))))
    (let [{:keys [output truncated]} (publish/cap (numbered 30) {:max-lines 500 :max-bytes 128})]
      (is truncated)
      (is (<= (bytes-of output) 128)))))

;;; Lines to items

(deftest item-test
  (let [opts (merge publish/defaults {:maps (publish/parse-maps ["/h/w=/work" "/h=/other"]) :name "wb"})
        it   (publish/item {:event "result" :file "/h/w/a.org" :buffer "/h/w/a.org" :id id1
                            :session "s" :status "done"
                            :output {:session "s" :status "done" :id id1 :input "ls" :output "x\n"}}
                           7 opts)
        ev   (json/parse-string (:event it) true)]
    (testing "--map rewrites file, not buffer"
      (is (= "/work/a.org" (:file ev)))
      (is (= "/h/w/a.org" (:buffer ev))))
    (testing "the event is numbered and named, and carries no output"
      (is (= 7 (:seq ev)))
      (is (= "wb" (:feed ev)))
      (is (not (contains? ev :output))))
    (testing "the output is the run-output answer"
      (is (= "{\"session\":\"s\",\"status\":\"done\",\"id\":\"0b9e1b1a-1111-4222-8333-444455556666\",\"input\":\"ls\",\"output\":\"x\\n\"}"
             (:output it)))))
  (testing "a sync run's output text becomes the answer"
    (let [it (publish/item {:event "execute" :id id1 :sync true :input "(+ 1 2)" :output "3"} 1
                           (assoc publish/defaults :maps []))]
      (is (= {:session nil :status "done" :id id1 :input "(+ 1 2)" :output "3"}
             (json/parse-string (:output it) true)))
      (is (not (contains? (json/parse-string (:event it) true) :input)))))
  (testing "an output Emacs cut roughly keeps the full sizes Emacs sent"
    (let [it (publish/item {:event "result" :id id1
                            :output {:status "done" :id id1 :output (numbered 2000)
                                     :truncated true :lines 9000 :bytes 123456}}
                           1 (assoc publish/defaults :maps []))
          a  (json/parse-string (:output it) true)]
      (is (:truncated a))
      (is (= [9000 123456] [(:lines a) (:bytes a)]))
      (is (= 501 (count (str/split-lines (:output a)))))))
  (testing "an output whose id is not a UUID is dropped, never a path"
    (binding [*err* (java.io.StringWriter.)]
      (is (nil? (:output (publish/item {:event "result" :id "../../x" :output "boom"} 1
                                       (assoc publish/defaults :maps []))))))))

;;; The publisher

(defn- recording-sink
  "A sink that records its calls in OPS, an atom, and calls BEFORE first.
  It holds no files: `fetch' finds none."
  [ops before]
  (reify feed/Sink
    (put [_ path s] (before :put path) (swap! ops conj [:put path s]))
    (append [_ line] (before :append line) (swap! ops conj [:append line]))
    (rotate [_ epoch keep] (before :rotate epoch) (swap! ops conj [:rotate epoch keep]))
    (fetch [_ _] nil)))

(defn- lines-in [& entries]
  (java.io.StringReader. (str/join (map #(str (json/generate-string %) "\n") entries))))

(deftest output-before-event-test
  (let [ops (atom [])
        {:keys [done]} (publish/start! {:sink (recording-sink ops (fn [& _])) :to "/feed"
                                        :heartbeat-ms 60000}
                                       (lines-in {:event "execute" :id id1 :file "/w/a.org"}
                                                 {:event "result" :id id1 :file "/w/a.org"
                                                  :output {:session "s" :status "done" :id id1 :output "hi"}}))]
    (is (deref done 5000 false))
    (is (= [[:rotate nil] [:put "status.json"]
            [:append 1] [:put (feed/output-file id1)] [:append 2]
            [:put "status.json"]]
           (mapv (fn [[op x s]]
                   (if (= :append op) [op (:seq (json/parse-string x true))] [op x]))
                 @ops)))
    (testing "every event carries the feed's epoch, its started"
      (let [started (:started (json/parse-string (nth (last @ops) 2) true))]
        (is (string? started))
        (is (= [started started]
               (keep (fn [[op x]] (when (= :append op) (:epoch (json/parse-string x true)))) @ops)))))
    (testing "attached is false at EOF"
      (is (false? (:attached (json/parse-string (last (last @ops)) true)))))))

(deftest retry-test
  (let [ops   (atom [])
        fails (atom 2)
        flaky (fn [op _] (when (and (= :append op) (pos? @fails))
                           (swap! fails dec)
                           (throw (ex-info "sink down" {}))))
        {:keys [done]} (binding [*err* (java.io.StringWriter.)]
                         (publish/start! {:sink (recording-sink ops flaky) :to "/feed" :heartbeat-ms 60000}
                                         (lines-in {:event "save" :file "/w/a.org"})))]
    (is (deref done 5000 false))
    (is (= 1 (count (filter #(= :append (first %)) @ops))))))

(deftest hanging-sink-test
  (let [ops     (atom [])
        release (promise)
        sink    (recording-sink ops (fn [op _] (when (= :append op) @release)))
        n       200
        big     (apply str (repeat 100000 "x"))
        out     (java.io.PipedOutputStream.)
        in      (java.io.InputStreamReader. (java.io.PipedInputStream. out 65536) "UTF-8")
        {:keys [state queue done]}
        (publish/start! {:sink sink :to "/feed" :max-bytes 4096 :heartbeat-ms 50} in)
        writer  (future
                  (dotimes [i n]
                    (.write out (.getBytes (str (json/generate-string
                                                 {:event "result" :id id1 :output {:id id1 :output big}})
                                                "\n")
                                           "UTF-8")))
                  (.close out)
                  :wrote)]
    (testing "the publisher keeps reading stdin: 20 MB go through a 64 KB pipe"
      (is (= :wrote (deref writer 20000 :blocked))))
    (Thread/sleep 300)
    (testing "queued grows, in status.json too"
      (is (= n (:enqueued @state)))
      (is (= 0 (:pushed @state)))
      (is (<= (dec n) (:queued (json/parse-string
                                (last (keep (fn [[op path s]] (when (= "status.json" path) s)) @ops))
                                true)))))
    (testing "memory stays bounded: each queued output is capped"
      (is (every? #(<= (bytes-of (:output %)) 5000) (filter map? (.toArray queue)))))
    (deliver release true)
    (is (deref done 10000 false))
    (is (= n (count (filter #(= :append (first %)) @ops))))))

(deftest queue-full-test
  (testing "the repro: :max-queued 5, a sink taking 50 ms per append, 20 events"
    (let [dir  (temp-dir)
          d    (feed/local-dir dir)
          sink (reify feed/Sink
                 (put [_ path s] (feed/put d path s))
                 (append [_ line] (Thread/sleep 50) (feed/append d line))
                 (rotate [_ epoch keep] (feed/rotate d epoch keep))
                 (fetch [_ path] (feed/fetch d path)))
          {:keys [state done]} (binding [*err* (java.io.StringWriter.)]
                                 (publish/start! {:sink sink :to dir :max-queued 5 :heartbeat-ms 60000}
                                                 (apply lines-in (repeat 20 {:event "save"}))))]
      (is (deref done 5000 false))
      (is (= 15 (:dropped @state)))
      (is (= 15 (:dropped (feed/status dir))))
      (testing "the dropped events take seqs 6-20; a `dropped' event, seq 21, says so"
        (let [evs (:events (feed/events-after dir {}))]
          (is (= [1 2 3 4 5 21] (map :seq evs)))
          (is (= {:event :dropped :from 6 :to 20} (select-keys (last evs) [:event :from :to])))))
      (testing "follow prints it as a notice"
        (let [[_ items] (cli/feed-poll dir {} 0)]
          (is (= [1 2 3 4 5 "15 events dropped (seq 6-20)"]
                 (map #(if (map? %) (:seq %) %) (remove #(and (string? %) (str/starts-with? % "feed detached")) items))))))
      (testing "and the agent is behind by the events, not the seqs"
        (let [{:keys [started pushed-seq gaps]} @state]
          (is (= 6 (publish/behind started pushed-seq gaps nil)))
          (is (= 1 (publish/behind started pushed-seq gaps {:epoch started :seq 5})))
          (is (= 1 (publish/behind started pushed-seq gaps {:epoch started :seq 9})))
          (is (= 0 (publish/behind started pushed-seq gaps {:epoch started :seq 21})))))))
  (testing "a drop shows with the next event, before it"
    (let [release (promise)
          ops     (atom [])
          sink    (recording-sink ops (fn [op _] (when (= :append op) @release)))
          out     (java.io.PipedOutputStream.)
          in      (java.io.InputStreamReader. (java.io.PipedInputStream. out) "UTF-8")
          {:keys [state done]} (binding [*err* (java.io.StringWriter.)]
                                 (publish/start! {:sink sink :to "/feed" :max-queued 2 :heartbeat-ms 60000} in))
          send!   #(do (.write out (.getBytes (str (json/generate-string %) "\n") "UTF-8")) (.flush out))]
      (dotimes [_ 4] (send! {:event "save"}))
      (loop [n 0] (when (and (< (:dropped @state) 2) (< n 100)) (Thread/sleep 20) (recur (inc n))))
      (deliver release true)
      (loop [n 0] (when (and (< (:pushed @state) 2) (< n 100)) (Thread/sleep 20) (recur (inc n))))
      (send! {:event "modify"})
      (.close out)
      (is (deref done 5000 false))
      (is (= [[1 "save"] [2 "save"] [5 "dropped"] [6 "modify"]]
             (keep (fn [[op x]] (when (= :append op)
                                  ((juxt :seq :event) (json/parse-string x true))))
                   @ops))))))

(deftest publish-from-stdin-test
  (let [fake (fake-emacsclient!)
        dir  (str (fs/path (temp-dir) "feed"))
        in   (str/join (map #(str (json/generate-string %) "\n")
                            [{:event "status" :file "/h/w/a.org" :devops-mode true}
                             {:event "execute" :file "/h/w/a.org" :buffer "a.org" :line 3 :id id1 :status "running"}
                             {:event "result" :file "/h/w/a.org" :buffer "a.org" :line 3 :id id1 :status "done"
                              :session "s" :output {:session "s" :status "done" :id id1 :input "seq 2000"
                                                    :output (numbered 2000)}}
                             {:event "execute" :file "/h/w/a.org" :buffer "a.org" :line 9 :id id2 :sync true
                              :input "(+ 1 2)" :output "3"}]))
        r    (p/shell {:in in :out :string :err :string :continue true
                       :env (bb-env fake {})}
                      "bb" "--config" bb-edn "publish" "--to" dir "--map" "/h/w=/work" "--name" "wb")
        st   (feed/status dir)
        evs  (:events (feed/events-after dir {}))]
    (is (zero? (:exit r)) (:err r))
    (testing "events numbered by seq from 1, paths mapped"
      (is (= [1 2 3] (map :seq evs)))
      (is (every? #(= "/work/a.org" (:file %)) evs))
      (is (every? #(= "wb" (:feed %)) evs)))
    (testing "each output in place"
      (let [a (json/parse-string (feed/output dir id1) true)]
        (is (:truncated a))
        (is (= 2000 (:lines a)))
        (is (= 501 (count (str/split-lines (:output a))))))
      (is (= "3" (:output (json/parse-string (feed/output dir id2) true)))))
    (testing "status.json: heartbeat, queued, attached false at EOF"
      (is (= "wb" (:feed st)))
      (is (= "/work/a.org" (:workbook st)))
      (is (true? (:devops-mode st)))
      (is (false? (:attached st)))
      (is (= 0 (:queued st)))
      (is (string? (:publisher-time st)))
      (is (string? (:started st))))
    (testing "no .tmp is left behind"
      (is (empty? (fs/glob dir "**.tmp"))))
    (testing "no emacsclient runs"
      (is (not (fs/exists? (fs/path fake "called")))))
    (testing "no --server or --file option"
      (doseq [opt ["--server" "--file"]]
        (is (= 1 (:exit (p/shell {:in "" :out :string :err :string :continue true :env (bb-env fake {})}
                                 "bb" "--config" bb-edn "publish" "--to" dir opt "x"))))))))

;;; The reader

(deftest reader-test
  (let [dir (temp-dir)
        ev  (fn [n] (json/generate-string {:event "save" :seq n}))]
    (spit (str (fs/path dir "events.jsonl")) (str/join (map #(str (ev %) "\n") [1 2 2 3])))
    (testing "a duplicated append is skipped by seq"
      (let [r (feed/events-after dir {})]
        (is (= [1 2 3] (map :seq (:events r))))
        (is (= 3 (:seq r)))
        (testing "and resuming after a seq"
          (is (= [3] (map :seq (:events (feed/events-after dir {:seq 2})))))
          (is (= [] (:events (feed/events-after dir r)))))
        (testing "a file shorter than the offset is read from the start"
          (spit (str (fs/path dir "events.jsonl")) (str (ev 4) "\n"))
          (is (= [4] (map :seq (:events (feed/events-after dir r))))))))
    (testing "by epoch: a later one is a new feed, an earlier one skipped"
      (let [ev (fn [ep n] (json/generate-string {:event "save" :seq n :epoch ep}))
            a  "2026-10-19T10:00:00-06:00"
            b  "2026-10-19T11:00:00-06:00"]
        (spit (str (fs/path dir "events.jsonl")) (str/join (map #(str % "\n") [(ev b 1) (ev a 9) (ev b 2)])))
        (let [r (feed/events-after dir {})]
          (is (= [1 2] (map :seq (:events r))))
          (is (= b (:epoch r)))
          (is (nil? (:new-epoch r))))
        (let [r (feed/events-after dir {:epoch a :seq 5})]
          (is (= [] (:events r)))
          (is (= b (:new-epoch r))))))
    (testing "output by ID"
      (is (nil? (feed/output dir id1)))
      (feed/put (feed/local-dir dir) (feed/output-file id1) "{\"id\":1}")
      (is (= "{\"id\":1}" (feed/output dir id1)))
      (is (thrown? Exception (feed/output dir "../status"))))
    (testing "status"
      (is (nil? (feed/status dir)))
      (feed/put (feed/local-dir dir) "status.json" "{\"feed\":\"wb\"}")
      (is (= {:feed "wb"} (feed/status dir))))))

;;; A new feed keeps the old one

(def ^:private ep-a "2026-10-19T10:00:00-06:00")
(def ^:private ep-b "2026-10-19T11:00:00-06:00")
(def ^:private ep-c "2026-10-19T12:00:00-06:00")

(defn- event-files [dir] (set (map fs/file-name (fs/glob dir "events*.jsonl"))))

(deftest rotate-test
  (doseq [[what sink-of] [["a local dir" feed/local-dir] ["--via" #(feed/via "env" %)]]]
    (testing what
      (let [dir (temp-dir)
            s   (sink-of dir)
            ev  #(slurp (str (fs/path dir %)))]
        (testing "no old feed: an empty events.jsonl"
          (feed/rotate s nil [])
          (is (= #{"events.jsonl"} (event-files dir))))
        (feed/append s "{\"seq\":1}")
        (testing "the old events.jsonl becomes its epoch's file"
          (feed/rotate s ep-a [])
          (is (= #{"events.jsonl" (feed/rotated-file ep-a)} (event-files dir)))
          (is (= "" (ev "events.jsonl")))
          (is (= "{\"seq\":1}\n" (ev (feed/rotated-file ep-a)))))
        (testing "the old feed's file is kept, and the cursor's; no others"
          (feed/rotate s ep-b [ep-a])
          (is (= #{"events.jsonl" (feed/rotated-file ep-a) (feed/rotated-file ep-b)} (event-files dir)))
          (feed/rotate s ep-c [nil])
          (is (= #{"events.jsonl" (feed/rotated-file ep-c)} (event-files dir))))
        (testing "fetch: a file, relative or absolute, or nil"
          (feed/put s feed/status-file "{\"feed\":\"wb\"}")
          (is (= "{\"feed\":\"wb\"}" (feed/fetch s feed/status-file)))
          (is (= "{\"feed\":\"wb\"}" (feed/fetch s (str (fs/path dir feed/status-file)))))
          (is (nil? (feed/fetch s "nope.json")))
          (is (nil? (feed/fetch s "outputs"))))))))

(deftest reattach-test
  (let [dir  (str (fs/path (temp-dir) "feed"))
        cur  (temp-dir)
        pub! (fn [& entries]
               @(:done (publish/start! {:to dir :name "wb" :cursors cur :heartbeat-ms 60000}
                                       (apply lines-in entries)))
               (Thread/sleep 2)
               (:started (feed/status dir)))
        a    (pub! {:event "save"} {:event "save"} {:event "save"})
        b    (pub! {:event "modify"})]
    (testing "a second publisher keeps the first feed, under its epoch"
      (is (= #{"events.jsonl" (feed/rotated-file a)} (event-files dir)))
      (is (= [[1 a] [2 a] [3 a]]
             (map (juxt :seq :epoch) (:events (feed/events-after dir {:file (feed/rotated-file a)})))))
      (is (= [[1 b]] (map (juxt :seq :epoch) (:events (feed/events-after dir {}))))))
    (testing "tails: what is left of the feeds since the cursor"
      (is (= [{:epoch a :events [3]}]
             (map #(update % :events (partial map :seq)) (feed/tails dir a 2 b))))
      (is (= [{:epoch "2026-01-01T00:00:00Z" :events [] :gone true}]
             (feed/tails dir "2026-01-01T00:00:00Z" 0 a))))
    (testing "a third keeps the second's, and the one the cursor is in"
      (spit (str (fs/path cur (feed/cursor-file "wb"))) (json/generate-string {:epoch a :seq 2}))
      (let [c (pub! {:event "save"})]
        (is (= #{"events.jsonl" (feed/rotated-file a) (feed/rotated-file b)} (event-files dir)))
        (is (= [{:epoch a :events [3]} {:epoch b :events [1]}]
               (map #(update % :events (partial map :seq)) (feed/tails dir a 2 c)))
            "and every event of a feed between")
        (spit (str (fs/path cur (feed/cursor-file "wb"))) (json/generate-string {:epoch c :seq 1}))
        (pub! {:event "save"})
        (is (= #{"events.jsonl" (feed/rotated-file c)} (event-files dir)))))))

(deftest behind-test
  (is (= 2 (publish/behind "a" 3 [] {:epoch "a" :seq 1})))
  (is (= 0 (publish/behind "a" 3 [] {:epoch "a" :seq 5})))
  (is (= 3 (publish/behind "a" 3 [] {:epoch "z" :seq 9})))
  (is (= 3 (publish/behind "a" 3 [] nil)))
  (testing "dropped seqs are not events"
    (is (= 4 (publish/behind "a" 10 [[3 4] [7 8]] {:epoch "a" :seq 2})))
    (is (= 4 (publish/behind "a" 10 [[3 4] [7 8]] {:epoch "a" :seq 4})))
    (is (= 2 (publish/behind "a" 10 [[3 4] [7 8]] {:epoch "a" :seq 7})))
    (is (= 6 (publish/behind "a" 10 [[3 4] [7 8]] nil))))
  (testing "the heartbeat prints it on stdout when it changes"
    (let [dir     (str (fs/path (temp-dir) "feed"))
          cur     (temp-dir)
          out     (java.io.PipedOutputStream.)
          in      (java.io.InputStreamReader. (java.io.PipedInputStream. out) "UTF-8")
          printed (java.io.StringWriter.)
          {:keys [state done]} (binding [*out* printed]
                                 (publish/start! {:to dir :name "wb" :cursors cur :heartbeat-ms 50} in))]
      (.write out (.getBytes (str/join (repeat 3 "{\"event\":\"save\"}\n")) "UTF-8"))
      (.flush out)
      (loop [n 0] (when (and (< (:pushed @state) 3) (< n 100)) (Thread/sleep 20) (recur (inc n))))
      (spit (str (fs/path cur (feed/cursor-file "wb")))
            (json/generate-string {:epoch (:started @state) :seq 1}))
      (Thread/sleep 400)
      (.close out)
      (is (deref done 5000 false))
      (let [lines (str/split-lines (str printed))]
        (is (= "{\"behind\":2}" (last lines)))
        (is (every? (fn [[x y]] (not= x y)) (partition 2 1 lines)) "only changes")))))

;;; --via CMD

(defn- fake-via!
  "A dir holding `via', a CMD for --via that counts its calls in
  `calls', fails while `fails' holds a positive count, and then runs
  the rest of its args, the `sh -c SCRIPT', here."
  [fails]
  (let [dir (temp-dir)
        f   (str (fs/path dir "via"))]
    (spit (str (fs/path dir "fails")) (str fails))
    (spit f (str "#!/bin/sh\n"
                 "d=" (feed/sh-quote dir) "\n"
                 "echo call >> \"$d/calls\"\n"
                 "n=$(cat \"$d/fails\")\n"
                 "if [ \"$n\" -gt 0 ]; then echo $((n - 1)) > \"$d/fails\"; echo down >&2; exit 255; fi\n"
                 "exec \"$@\"\n"))
    (fs/set-posix-file-permissions f "rwxr-xr-x")
    dir))

(defn- calls [via-dir]
  (let [f (fs/file via-dir "calls")]
    (if (fs/exists? f) (count (str/split-lines (slurp f))) 0)))

(deftest via-sink-test
  (let [via (fake-via! 0)
        ;; A feed path that needs quoting.
        to  (str (fs/path (temp-dir) "it's a feed"))
        s   (feed/via (feed/sh-quote (str (fs/path via "via"))) to)]
    (feed/put s feed/events-file "")
    (feed/put s feed/status-file "{\"feed\":\"wb\"}")
    (feed/append s "{\"seq\":1}")
    (feed/push s [{:path (feed/output-file id1) :output "{\"output\":\"a\\nb\"}"
                   :event "{\"seq\":2,\"input\":\"x  'y' \\\"$z\\\" \\\\n\"}"}])
    (is (= 4 (calls via)) "one command each, a result too")
    (is (= {:feed "wb"} (feed/status to)))
    (is (= [1 2] (map :seq (:events (feed/events-after to {})))))
    (is (= "x  'y' \"$z\" \\n" (:input (second (:events (feed/events-after to {}))))))
    (is (= "{\"output\":\"a\\nb\"}" (feed/output to id1)))
    (is (empty? (fs/glob to "**.tmp")))
    (testing "a feed path is relative, never up"
      (is (thrown? Exception (feed/put s "../x" "")))
      (is (thrown? Exception (feed/put s "/etc/x" ""))))
    (testing "--to is absolute"
      (is (thrown? Exception (feed/via "env" "feed"))))
    (testing "no script waits for EOF, which docker exec -i can lose"
      ;; The pipe into the command stays open 10 s after its input.
      (let [s (feed/via "sh -c '{ cat; sleep 10 3>&1 >/dev/null 2>&1 & } | exec \"$0\" \"$@\"'"
                        to {:timeout-ms 5000})]
        (feed/put s feed/events-file "")
        (feed/put s feed/status-file "{\"feed\":\"no-eof\"}")
        (feed/push s [{:path (feed/output-file id2) :output "{\"output\":\"é\"}" :event "{\"seq\":1}"}])
        (feed/append s "{\"seq\":2}")
        (is (= {:feed "no-eof"} (feed/status to)))
        (is (= "{\"output\":\"é\"}" (feed/output to id2)))
        (is (= [1 2] (map :seq (:events (feed/events-after to {})))))))
    (testing "a command that hangs times out"
      (is (thrown-with-msg? Exception #"no answer"
                            (feed/put (feed/via "sh -c 'sleep 5' x" to {:timeout-ms 200}) "a.json" ""))))))

(deftest via-refuses-symlinks-test
  (let [elsewhere (temp-dir)
        root      (temp-dir)
        sink      #(feed/via "env" (str (fs/path root %)))]
    (testing "TO a symlink"
      (fs/create-sym-link (fs/path root "to") elsewhere)
      (is (thrown-with-msg? Exception #"symlink" (feed/append (sink "to") "{}")))
      (is (thrown-with-msg? Exception #"symlink" (feed/put (sink "to") "status.json" "{}"))))
    (testing "TO/outputs a symlink"
      (fs/create-dirs (fs/path root "a"))
      (fs/create-sym-link (fs/path root "a" "outputs") elsewhere)
      (is (thrown-with-msg? Exception #"symlink"
                            (feed/push (sink "a") [{:path (feed/output-file id1) :output "{}" :event "{\"seq\":1}"}])))
      (is (not (fs/exists? (fs/path root "a" "events.jsonl"))) "no event without its output"))
    (testing "the target a symlink"
      (fs/create-dirs (fs/path root "b"))
      (fs/create-sym-link (fs/path root "b" "events.jsonl") (fs/path elsewhere "e"))
      (fs/create-sym-link (fs/path root "b" "status.json") (fs/path elsewhere "s"))
      (is (thrown-with-msg? Exception #"symlink" (feed/append (sink "b") "{}")))
      (is (thrown-with-msg? Exception #"symlink" (feed/put (sink "b") "status.json" "{}")))
      (is (thrown-with-msg? Exception #"symlink" (feed/rotate (sink "b") ep-a []))))
    (is (empty? (fs/list-dir elsewhere)) "nothing written where a link points")))

(deftest via-retry-test
  (let [via (fake-via! 2)
        to  (str (fs/path (temp-dir) "feed"))
        {:keys [done]} (binding [*err* (java.io.StringWriter.)]
                         (publish/start! {:via (str (fs/path via "via")) :to to :heartbeat-ms 60000}
                                         (lines-in {:event "result" :id id1 :file "/w/a.org"
                                                    :output {:id id1 :output "hi"}})))]
    (is (deref done 10000 false))
    (testing "two failed calls, then each push once"
      (is (= (+ 2 5) (calls via))))
    (is (= [1] (map :seq (:events (feed/events-after to {})))))
    (is (= "hi" (:output (json/parse-string (feed/output to id1) true))))))

(deftest publish-via-test
  (let [fake (fake-emacsclient!)
        via  (fake-via! 0)
        to   (str (fs/path (temp-dir) "feed"))
        in   (str/join (map #(str (json/generate-string %) "\n")
                            [{:event "status" :file "/h/w/a.org" :devops-mode true}
                             {:event "execute" :file "/h/w/a.org" :id id1 :status "running"}
                             {:event "result" :file "/h/w/a.org" :id id1 :status "done"
                              :output {:session "s" :status "done" :id id1 :input "ls" :output "x\n"}}
                             {:event "result" :file "/h/w/a.org" :id id2 :status "done"
                              :output {:session "s" :status "done" :id id2 :input "ls" :output "y\n"}}]))
        r    (p/shell {:in in :out :string :err :string :continue true :env (bb-env fake {})}
                      "bb" "--config" bb-edn "publish" "--via" (str (fs/path via "via") " env")
                      "--to" to "--map" "/h/w=/work" "--name" "wb")
        evs  (:events (feed/events-after to {}))]
    (is (zero? (:exit r)) (:err r))
    (testing "the old status, start, status, the events, in one push if they were all queued by then, status"
      (is (<= 5 (calls via) 7)))
    (is (= [1 2 3] (map :seq evs)))
    (is (every? #(= "/work/a.org" (:file %)) evs))
    (is (= "y\n" (:output (json/parse-string (feed/output to id2) true))))
    (is (false? (:attached (feed/status to))))
    (is (not (fs/exists? (fs/path fake "called"))))))

(deftest via-push-test
  (let [via   (fake-via! 0)
        to    (str (fs/path (temp-dir) "feed"))
        v     (feed/via (str (fs/path via "via")) to)
        ;; A --via sink whose feed starts only once GATE is delivered.
        gated (fn [gate]
                (reify
                  feed/Sink
                  (put [_ path s] (feed/put v path s))
                  (append [_ line] (feed/append v line))
                  (rotate [_ epoch keep] (feed/rotate v epoch keep))
                  (fetch [_ path] @gate (feed/fetch v path))
                  feed/Batched
                  (push [_ items] (feed/push v items))))
        id9   "0b9e1b1a-1111-4222-8333-000000000009"
        burst (for [i (range 10)]
                (if (even? i)
                  {:event "save" :file "/w/a.org"}
                  {:event "result" :id (format "0b9e1b1a-1111-4222-8333-%012d" i) :file "/w/a.org"
                   :output {:status "done" :output (str "out " i "\n'$x'")}}))
        ;; Publish the burst, every event queued before the feed starts:
        ;; the commands it took to push them.
        pub!  (fn [opts]
                (fs/delete-if-exists (fs/path via "calls"))
                (let [gate (promise)
                      {:keys [state done]} (publish/start! (merge {:sink (gated gate) :to to :heartbeat-ms 60000} opts)
                                                           (apply lines-in burst))]
                  (loop [n 0] (when (and (not (:eof @state)) (< n 100)) (Thread/sleep 20) (recur (inc n))))
                  (deliver gate true)
                  (is (deref done 10000 false))
                  ;; Less the fetch, rotate and two puts of status.json.
                  (- (calls via) 4)))]
    (testing "a burst of 10 goes in one command, in order, each output in place"
      (is (= 1 (pub! {})))
      (let [evs (:events (feed/events-after to {}))]
        (is (= (range 1 11) (map :seq evs)))
        (is (= 5 (count (keep #(some->> (:id %) (feed/output to)) evs))))
        (is (= "out 9\n'$x'" (:output (json/parse-string (feed/output to id9) true))))))
    (testing "at most :batch-max events a command"
      (is (= 4 (pub! {:batch-max 3}))))
    (testing "and about :batch-bytes, never none"
      (is (= 10 (pub! {:batch-bytes 10}))))
    (testing "a batch pushed twice, as a retry may: a reader skips the repeats by seq"
      (let [items [{:path (feed/output-file id1) :output "{\"output\":1}" :event "{\"seq\":11}"}
                   {:event "{\"seq\":12}"}]]
        (feed/push v items)
        (feed/push v items)
        (is (= [11 12] (map :seq (:events (feed/events-after to {:seq 10})))))))
    (testing "an output of more than one line is refused, never split"
      (is (thrown? Exception (feed/push v [{:path (feed/output-file id1) :output "a\nb" :event "{}"}]))))))
