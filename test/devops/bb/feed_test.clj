(ns devops.bb.feed-test
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [cheshire.core :as json]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
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
  "This environment, with FAKE first on PATH and no Emacs socket."
  [fake extra]
  (-> (into {} (System/getenv))
      (dissoc "EMACS_SOCKET_NAME" "DEVOPS_BB_FEED")
      (assoc "PATH" (str fake ":" (System/getenv "PATH")))
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
  (testing "an output whose id is not a UUID is dropped, never a path"
    (binding [*err* (java.io.StringWriter.)]
      (is (nil? (:output (publish/item {:event "result" :id "../../x" :output "boom"} 1
                                       (assoc publish/defaults :maps []))))))))

;;; The publisher

(defn- recording-sink
  "A sink that records its calls in OPS, an atom, and calls BEFORE first."
  [ops before]
  (reify feed/Sink
    (put [_ path s] (before :put path) (swap! ops conj [:put path s]))
    (append [_ line] (before :append line) (swap! ops conj [:append line]))))

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
    (is (= [[:put "events.jsonl"] [:put "status.json"]
            [:append 1] [:put (feed/output-file id1)] [:append 2]
            [:put "status.json"]]
           (mapv (fn [[op x s]]
                   (if (= :append op) [op (:seq (json/parse-string x true))] [op x]))
                 @ops)))
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
  (let [release (promise)
        sink    (recording-sink (atom []) (fn [op _] (when (= :append op) @release)))
        {:keys [state]} (binding [*err* (java.io.StringWriter.)]
                          (publish/start! {:sink sink :to "/feed" :max-queued 5 :heartbeat-ms 60000}
                                          (apply lines-in (repeat 20 {:event "save"}))))]
    (Thread/sleep 300)
    (is (:eof @state))
    (is (= 5 (:enqueued @state)))
    (is (= 15 (:dropped @state)))
    (deliver release true)))

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
        (testing "a new feed starts again from seq 1"
          (spit (str (fs/path dir "events.jsonl")) (str (ev 1) "\n"))
          (let [r2 (feed/events-after dir r)]
            (is (:reset r2))
            (is (= [1] (map :seq (:events r2))))))))
    (testing "output by ID"
      (is (nil? (feed/output dir id1)))
      (feed/put (feed/local-dir dir) (feed/output-file id1) "{\"id\":1}")
      (is (= "{\"id\":1}" (feed/output dir id1)))
      (is (thrown? Exception (feed/output dir "../status"))))
    (testing "status"
      (is (nil? (feed/status dir)))
      (feed/put (feed/local-dir dir) "status.json" "{\"feed\":\"wb\"}")
      (is (= {:feed "wb"} (feed/status dir))))))
