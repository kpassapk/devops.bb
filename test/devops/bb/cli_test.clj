(ns devops.bb.cli-test
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [cheshire.core :as json]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [devops.bb.cli :as cli]
   [devops.bb.feed :as feed]
   [devops.bb.feed-test :as ft]
   [devops.bb.publish :as publish])
  (:import
   [java.time OffsetDateTime]))

(deftest summary-line-test
  (is (= "result done /w/a.org:12 a1"
         (cli/summary-line {:event :result :status "done" :file "/w/a.org" :line 12 :id "a1"})))
  (is (= "execute error /w/a.org:3"
         (cli/summary-line {:event :execute :error "No target" :file "/w/a.org" :line 3})))
  (is (= "drift drift /w/a.org:all"
         (cli/summary-line {:event :drift :all true :file "/w/a.org" :line 9
                            :files [{:status "same"} {:status "missing"}]})))
  (is (= "tangle done /w/a.org:9"
         (cli/summary-line {:event :tangle :all false :file "/w/a.org" :line 9 :targets []})))
  (testing "with who caused it"
    (is (= "save auto /w/a.org:all"
           (cli/summary-line {:event :save :origin "auto" :file "/w/a.org" :sha256 "ab"})))))

;;; Feed mode

(def id ft/id1)

(defn- publish-to!
  "Publish ENTRIES to the feed at DIR, a new feed of it; DIR."
  [dir & entries]
  @(:done (publish/start! {:to dir :map ["/h/w=/work"] :name "wb" :heartbeat-ms 60000}
                          (java.io.StringReader.
                           (str/join (map #(str (json/generate-string %) "\n") entries)))))
  ;; The next feed's epoch, its started, is a later one.
  (Thread/sleep 2)
  dir)

(defn- publish!
  "A feed in a new dir, published from ENTRIES; the dir."
  [& entries]
  (apply publish-to! (str (fs/path (str (fs/create-temp-dir)) "feed")) entries))

(def entries
  [{:event "status" :file "/h/w/a.org" :devops-mode true}
   {:event "execute" :file "/h/w/a.org" :buffer "a.org" :line 3 :id id :status "running"
    :time "2026-10-19T10:00:00.000-0600"}
   {:event "result" :file "/h/w/a.org" :buffer "a.org" :line 3 :id id :status "done" :session "s"
    :time "2026-10-19T10:00:02.000-0600"
    :output {:session "s" :status "done" :id id :input "ls" :output "a\nb\n"}}
   {:event "modify" :file "/h/w/a.org" :buffer "a.org" :time "2026-10-19T10:00:03.000-0600"}])

(defn- bb [fake dir & args]
  (apply p/shell {:in "" :out :string :err :string :continue true
                  :env (ft/bb-env fake {"DEVOPS_BB_FEED" dir})}
         "bb" "--config" ft/bb-edn args))

(deftest harness-test
  (testing "the fake emacsclient trips when a command asks Emacs"
    (let [fake (ft/fake-emacsclient!)]
      (bb fake "" "status")
      (is (fs/exists? (fs/path fake "called"))))))

(deftest feed-mode-test
  (let [fake (ft/fake-emacsclient!)
        dir  (apply publish! entries)
        out  (fn [& args] (let [r (apply bb fake dir args)]
                            [(:exit r) (str/trim (:out r)) (str/trim (:err r))]))]
    (testing "status prints status.json: the feed and the workbook"
      (let [[exit o] (out "status")
            st (json/parse-string o true)]
        (is (= 0 exit))
        (is (= "wb" (:feed st)))
        (is (= "/work/a.org" (:workbook st)))))
    (testing "--feed DIR works as the variable does"
      (let [r (p/shell {:in "" :out :string :err :string :continue true
                        :env (ft/bb-env fake {})}
                       "bb" "--config" ft/bb-edn "status" "--feed" dir)]
        (is (= "wb" (:feed (json/parse-string (:out r) true))))))
    (testing "run-output prints the output file as is"
      (is (= [0 (slurp (str (fs/path dir (feed/output-file id)))) ""] (out "run-output" id))))
    (testing "run-output of an unknown run: the not-found shape"
      (is (= [0 (str "{\"session\":null,\"status\":\"not-found\",\"id\":\"" ft/id2
                     "\",\"input\":null,\"output\":null}") ""]
             (out "run-output" ft/id2))))
    (testing "run-output refuses an ID that is not a UUID"
      (is (= [1 "" "error: not a run ID: ../status"] (out "run-output" "../status"))))
    (testing "modified: the last modify, save, revert or kill"
      (is (= [0 "true" ""] (out "modified")))
      (is (= [0 "true" ""] (out "modified" "/work/a.org")))
      (let [[exit _ err] (out "modified" "/work/b.org")]
        (is (= 1 exit))
        (is (str/includes? err "not this sandbox's workbook")))
      (let [saved (apply publish! (conj entries {:event "save" :file "/h/w/a.org"}))]
        (is (= "false" (str/trim (:out (bb fake saved "modified")))))))
    (testing "the other commands need Emacs"
      (doseq [args [["output" "/work/a.org" "3"] ["sessions"] ["revert" "/work/a.org"]
                    ["tools"] ["drift" "/work/a.org"]]]
        (is (= [1 "" "error: needs Emacs, which this sandbox cannot reach"] (apply out args))
            (pr-str args))))
    (testing "follow --after SEQ prints each event with its seq"
      (let [log  (str (fs/path (str (fs/create-temp-dir)) "out"))
            errs (str log ".err")
            proc (p/process {:out :write :out-file (fs/file log) :err :write :err-file (fs/file errs)
                             :env (ft/bb-env fake {"DEVOPS_BB_FEED" dir})}
                            "bb" "--config" ft/bb-edn "follow" "--after" "1")]
        (Thread/sleep 2500)
        (p/destroy-tree proc)
        @proc
        (is (= [(str "2 result done /work/a.org:3 " id)
                "3 modify /work/a.org:all"]
               (remove #(str/starts-with? % "! ") (str/split-lines (slurp log)))))
        (testing "and says the feed is detached, on stdout"
          (is (str/starts-with? (first (str/split-lines (slurp log))) "! feed detached"))
          (is (= "" (slurp errs))))))
    (testing "--once: the first poll with events, then exit 0"
      (let [[exit o err] (out "follow" "--after" "1" "--once")]
        (is (= 0 exit))
        (is (= ["! feed detached: the workbook's publisher has stopped; no new events until the operator attaches again"
                (str "2 result done /work/a.org:3 " id)
                "3 modify /work/a.org:all"]
               (str/split-lines o)))
        (is (= "" err))))
    (testing "--count N: N event lines, then exit 0"
      (let [[exit o] (out "follow" "--after" "0" "--count" "2")]
        (is (= 0 exit))
        (is (= ["1 execute running" "2 result done"]
               (->> (str/split-lines o)
                    (remove #(str/starts-with? % "! "))
                    (map #(str/join " " (take 3 (str/split % #" ")))))))))
    (testing "--wake: what to act on, and an ack past the hidden modify"
      (let [[exit o err] (out "follow" "--after" "0" "--wake" "0" "--once")]
        (is (= [0 ""] [exit err]))
        (is (= ["! feed detached: the workbook's publisher has stopped; no new events until the operator attaches again"
                (str "2 result done /work/a.org:3 " id)
                "! ack 3"]
               (str/split-lines o))))
      (is (= (out "follow" "--after" "0" "--once") (out "follow" "--after" "0" "--once" "--raw")))
      (is (= 1 (first (out "follow" "--wake" "--raw"))))
      (is (= 1 (first (out "follow" "--wake" "fast")))))
    (testing "no emacsclient ran"
      (is (not (fs/exists? (fs/path fake "called")))))))

(defn- put-status! [dir m] (feed/put (feed/local-dir dir) feed/status-file (json/generate-string m)))
(defn- append! [dir & seqs]
  (doseq [n seqs] (feed/append (feed/local-dir dir) (json/generate-string {:event "save" :seq n}))))

(deftest follow-wakes-test
  (let [fake (ft/fake-emacsclient!)
        dir  (apply publish! entries)
        env  (ft/bb-env fake {"DEVOPS_BB_FEED" dir})
        fresh! #(put-status! dir (assoc (feed/status dir) :attached true
                                        :publisher-time (str (OffsetDateTime/now))))
        save (fn [n] (append! dir n) (fresh!))]
    (fresh!)
    (testing "follow | head -n1 ends, follow too, at its first write once head is gone"
      (let [proc (p/process {:out :string :err :string :env env}
                            "bash" "-c" (str "set -o pipefail; bb --config " ft/bb-edn
                                             " follow --after 0 | head -n1"))]
        (Thread/sleep 2000)
        (save 4)
        (let [r (deref proc 3000 nil)]
          (when-not r (p/destroy-tree proc))
          (is (some? r) "still running a poll after the next event")
          (is (= 0 (:exit r)))
          (is (= (str "1 execute running /work/a.org:3 " id) (str/trim (:out r)))))))
    (testing "--once, from the cursor: waits for the next poll with an event"
      (p/shell {:out :string :env env} "bb" "--config" ft/bb-edn "ack" "4")
      (let [proc (p/process {:out :string :err :string :env env}
                            "bb" "--config" ft/bb-edn "follow" "--once")]
        (Thread/sleep 1500)
        (is (p/alive? proc))
        (save 5)
        (let [r (deref proc 3000 nil)]
          (when-not r (p/destroy-tree proc))
          (is (= 0 (:exit r)))
          (is (= ["5 save :all"] (str/split-lines (:out r))))
          (is (= "" (:err r))))))))

(defn- append-ep! [dir epoch & seqs]
  (doseq [n seqs]
    (feed/append (feed/local-dir dir) (json/generate-string {:event "save" :seq n :epoch epoch}))))

(deftest feed-poll-test
  (let [dir  (str (fs/create-temp-dir))
        now  (OffsetDateTime/parse "2026-10-19T10:00:00-06:00")
        at   (fn [s] (str (.plusSeconds now s)))
        ;; feed-poll's clock is this machine's monotonic one, in ns.
        ns-at (fn [secs] (* secs 1000000000))
        poll-in (fn [dir st secs]
                  (let [[st items] (cli/feed-poll dir st (ns-at secs))]
                    [st (filterv map? items) (filterv string? items)]))
        poll (partial poll-in dir)]
    (feed/put (feed/local-dir dir) feed/events-file "")
    (put-status! dir {:started (at 0) :publisher-time (at 0) :attached true :queued 0})
    (append! dir 1 2 2)
    (let [[st evs ns] (poll {} 1)]
      (testing "new events, a duplicate skipped, nothing to say"
        (is (= [1 2] (map :seq evs)))
        (is (= [] ns))
        (is (= (at 0) (:epoch st))))
      (testing "a heartbeat that has not changed is stale, said once"
        (let [[st _ ns] (poll st 20)]
          (is (= ["publisher heartbeat has not changed for 19 s"] ns))
          (is (= [] (nth (poll st 25) 2)))))
      (testing "publisher is N events behind, once queued stays up"
        (put-status! dir {:started (at 0) :publisher-time (at 3) :attached true :queued 4})
        (let [[st _ ns] (poll st 4)]
          (is (= [] ns))
          (put-status! dir {:started (at 0) :publisher-time (at 6) :attached true :queued 5})
          (let [[st _ ns] (poll st 7)]
            (is (= ["publisher is 5 events behind"] ns))
            (put-status! dir {:started (at 0) :publisher-time (at 9) :attached true :queued 6})
            (is (= [] (nth (poll st 10) 2))))))
      (testing "a restart: what is left of the old feed, said, then the new from seq 1"
        (append! dir 3)
        (feed/rotate (feed/local-dir dir) (at 0) [])
        (put-status! dir {:started (at 30) :publisher-time (at 30) :attached true :queued 0})
        (append! dir 1)
        (let [[st items] (cli/feed-poll dir st (ns-at 31))]
          (is (= [(str "the rest of feed " (at 0) ", which has ended: seq 3-3; ack these with --epoch " (at 0))
                  3
                  (str "feed restarted at " (at 30) ": seq starts again at 1")
                  1]
                 (map #(if (map? %) (:seq %) %) items)))
          (is (= [(at 30) 1] ((juxt :epoch :seq) st)))
          (testing "detached"
            (put-status! dir {:started (at 30) :publisher-time (at 32) :attached false :queued 0})
            (let [[st _ ns] (poll st 40)]
              (is (= 1 (count ns)))
              (is (str/starts-with? (first ns) "feed detached"))
              (is (= [] (nth (poll st 41) 2))))))))
    (testing "on the first poll too, from a cursor in an earlier feed"
      (let [[_ evs ns] (poll {:epoch (at 0) :seq 5 :offset 0} 31)]
        (is (= [1] (map :seq evs)))
        (is (= [(str "feed restarted at " (at 30) ": seq starts again at 1")]
               (remove #(str/starts-with? % "feed detached") ns)))))
    (testing "a feed that is not kept: said"
      (let [[_ evs ns] (poll {:epoch (at -60) :seq 5 :offset 0} 31)]
        (is (= [1] (map :seq evs)))
        (is (= [(str "feed " (at -60) " is gone: any events of it after seq 5 are lost")
                (str "feed restarted at " (at 30) ": seq starts again at 1")]
               (remove #(str/starts-with? % "feed detached") ns)))))
    (testing "a restart seen on an event before status.json"
      (let [dir  (str (fs/create-temp-dir))
            poll (partial poll-in dir)]
        (put-status! dir {:started (at 0) :publisher-time (at 0) :attached true :queued 0})
        (append-ep! dir (at 0) 1 2 3)
        (let [[st] (poll {} 1)]
          (feed/rotate (feed/local-dir dir) (at 0) [])
          (append-ep! dir (at 2) 1)
          (let [[st evs ns] (poll st 2)]
            (is (= [1] (map :seq evs)))
            (is (= 1 (count ns)))
            (put-status! dir {:started (at 2) :publisher-time (at 2) :attached true :queued 0})
            (append-ep! dir (at 2) 2)
            (testing "an earlier feed's late event is skipped"
              (append-ep! dir (at 0) 4))
            (let [[_ evs ns] (poll st 3)]
              (is (= [2] (map :seq evs)))
              (is (= [] ns)))))))))

(deftest stale-on-one-clock-test
  (testing "the box clock 20 s ahead or 60 s behind the host's: no false or hidden stale notice"
    (doseq [skew [-20 60]]
      (let [dir   (str (fs/create-temp-dir))
            ;; The host's time, as the publisher writes it, SKEW s off
            ;; this machine's; feed-poll never reads this machine's.
            t0    (OffsetDateTime/now)
            host  (fn [s] (str (.plusSeconds t0 (+ skew s))))
            beat! (fn [s] (put-status! dir {:started (host 0) :publisher-time (host s)
                                            :attached true :queued 0}))
            poll  (fn [st secs] (let [[st items] (cli/feed-poll dir st (* secs 1000000000))]
                                  [st (filterv string? items)]))]
        (testing (str "skew " skew " s")
          (beat! 0)
          (let [[st ns] (poll {} 0)]
            (is (= [] ns) "a fresh heartbeat is fresh")
            (let [st (reduce (fn [st t] (beat! t) (let [[st ns] (poll st t)] (is (= [] ns)) st))
                             st (range 3 31 3))]
              (testing "a heartbeat that stops is stale 15 s on"
                (is (= [] (second (poll st 44))))
                (is (= ["publisher heartbeat has not changed for 16 s"] (second (poll st 46))))))))))))

;;; --wake

(defn- wake-run
  "TIMELINE, [ms item] pairs, through wake-step with OPTS, polled every
  300 ms as follow does, to 20 s, on a fake clock: [ms lines] for each
  poll that printed."
  [opts timeline]
  (loop [t 0 ws {} tl timeline out []]
    (if (> t 20000)
      out
      (let [[seen later] (split-with #(<= (first %) t) tl)
            [ws items] (cli/wake-step ws (map second seen) (* t 1000000) opts)
            show #(if (string? %)
                    (str "! " %)
                    (str/join " " (remove nil? [(:seq %) (name (:event %)) (:status %) (:origin %)])))]
        (recur (+ t 300) ws later (cond-> out (seq items) (conj [t (mapv show items)])))))))

(def timeline
  (let [ev (fn [ms n event & {:as m}] [ms (merge {:seq n :event event :file "/work/wb.org"} m)])]
    [;; An async run: four events within 4 ms.
     (ev 0 1 :save :origin "auto")
     (ev 1 2 :execute :status "running" :origin "operator" :id id)
     (ev 2 3 :save :origin "auto")
     (ev 4 4 :result :status "done" :origin "operator" :id id)
     ;; A sync run.
     (ev 2000 5 :save :origin "auto")
     (ev 2001 6 :execute :status "done" :origin "operator" :id ft/id2)
     ;; The operator edits and saves.
     (ev 4000 7 :modify :origin "operator")
     (ev 4500 8 :save :origin "operator")
     ;; Emacs reloads the agent's write.
     (ev 6000 9 :revert :origin "agent")
     ;; The host dropped three; the next is a modify.
     [8000 "3 events dropped (seq 10-12)"]
     (ev 8001 13 :modify :origin "operator")
     ;; A long burst, an event every 600 ms.
     (ev 10000 14 :execute :status "running" :origin "operator")
     (ev 10600 15 :save :origin "auto")
     (ev 11200 16 :result :status "done" :origin "operator")
     (ev 11800 17 :result :status "error" :origin "operator")
     (ev 12400 18 :save :origin "auto")
     (ev 13000 19 :save :origin "auto")
     (ev 13600 20 :result :status "done" :origin "operator")]))

(deftest wake-step-test
  (testing "750 ms after the last event seen, at most 3 s after the first"
    (is (= [[1200 ["4 result done operator"]]
            [3000 ["6 execute done operator"]]
            ;; The modify and the save: one line.
            [5400 ["8 save operator"]]
            ;; The agent's revert alone printed nothing; ack 13 passes it.
            [9000 ["! 3 events dropped (seq 10-12)" "! ack 13"]]
            ;; Seen first at 10200: the cap, 3 s on, ends the batch.
            [13200 ["16 result done operator" "17 result error operator" "! ack 19"]]
            [14700 ["20 result done operator"]]]
           (wake-run (#'cli/wake-opts {:wake true}) timeline))))
  (testing "--wake 0: each poll's at once, still only what to act on"
    (is (= [[300 ["4 result done operator"]]
            [2100 ["6 execute done operator"]]
            [4500 ["8 save operator"]]
            [8100 ["! 3 events dropped (seq 10-12)" "! ack 13"]]
            [11400 ["16 result done operator"]]
            [12000 ["17 result error operator"]]
            [13800 ["20 result done operator"]]]
           (wake-run (#'cli/wake-opts {:wake 0}) timeline))))
  (testing "--wake 2000: 2 s after the last, at most 3 s"
    (is (= [[3000 ["4 result done operator" "6 execute done operator"]]
            [7200 ["8 save operator" "! ack 9"]]
            [11100 ["! 3 events dropped (seq 10-12)" "! ack 15"]]
            [14400 ["16 result done operator" "17 result error operator" "20 result done operator"]]]
           (wake-run (#'cli/wake-opts {:wake 2000}) timeline))))
  (testing "a restart drops the carried ack: it would be the old feed's seq"
    (let [opts (#'cli/wake-opts {:wake 0})
          [ws] (cli/wake-step {} [{:seq 9 :event :revert :origin "agent"}] 0 opts)]
      (is (= {:seq 9 :event :revert :origin "agent"} (:carry ws)))
      (is (= ["feed restarted at T: seq starts again at 1"]
             (second (cli/wake-step ws ["feed restarted at T: seq starts again at 1"] 1 opts))))
      (is (= ["feed detached" "ack 9"] (second (cli/wake-step ws ["feed detached"] 1 opts)))))))

;;; The cursor

(deftest cursor-test
  (let [fake (ft/fake-emacsclient!)
        dir  (publish-to! (str (fs/path (str (fs/create-temp-dir)) "feed"))
                          {:event "status" :file "/h/w/a.org" :devops-mode true}
                          {:event "save" :file "/h/w/a.org"}
                          {:event "save" :file "/h/w/a.org"}
                          {:event "save" :file "/h/w/a.org"}
                          {:event "save" :file "/h/w/a.org"}
                          {:event "save" :file "/h/w/a.org"})
        a    (:started (feed/status dir))
        run  (fn [& args] (let [r (apply bb fake dir args)]
                            [(:exit r) (vec (str/split-lines (str/trim (:out r)))) (str/trim (:err r))]))
        cursor-file (str (fs/path fake "cursor" "wb.json"))
        events (fn [lines] (remove #(str/starts-with? % "! ") lines))
        detached? #(str/starts-with? % "! feed detached")]
    (testing "no cursor: follow gives the whole feed, and does not move the cursor"
      (let [[exit lines] (run "follow" "--once")]
        (is (= 0 exit))
        (is (= ["1 save" "2 save" "3 save" "4 save" "5 save"]
               (map #(subs % 0 6) (events lines))))
        (is (not (fs/exists? cursor-file)))))
    (testing "ack moves the cursor, and follow resumes from it"
      (is (= [0 [(json/generate-string {:epoch a :seq 2})] ""] (run "ack" "2")))
      (is (= {:feed "wb" :epoch a :seq 2} (json/parse-string (slurp cursor-file) true)))
      (is (= ["3 save" "4 save" "5 save"] (map #(subs % 0 6) (events (second (run "follow" "--once")))))))
    (testing "ack never moves the cursor back"
      (is (= [0 [(json/generate-string {:epoch a :seq 2})] ""] (run "ack" "1"))))
    (testing "ack past this feed's last seq wants --epoch"
      (let [[exit _ err] (run "ack" "9")]
        (is (= 1 exit))
        (is (str/includes? err "pass --epoch"))))
    (testing "a re-attach: the old feed's unacked rest, then the new feed"
      (publish-to! dir {:event "modify" :file "/h/w/a.org"} {:event "save" :file "/h/w/a.org"})
      (let [b (:started (feed/status dir))
            [exit lines] (run "follow" "--once")]
        (is (= 0 exit))
        (is (= [(str "! the rest of feed " a ", which has ended: seq 3-5; ack these with --epoch " a)
                "3 save" "4 save" "5 save"
                (str "! feed restarted at " b ": seq starts again at 1")
                "1 modify" "2 save"]
               (->> lines (remove detached?) (map #(if (str/starts-with? % "!") % (str/join " " (take 2 (str/split % #" "))))))))
        (testing "ack --epoch: in the old feed"
          (is (= 0 (first (run "ack" "4" "--epoch" a))))
          (is (= ["5 save" "1 modify" "2 save"]
                 (->> (second (run "follow" "--once")) events (map #(str/join " " (take 2 (str/split % #" "))))))))
        (testing "ack in the new feed passes the old"
          (is (= 0 (first (run "ack" "1"))))
          (is (= {:feed "wb" :epoch b :seq 1} (json/parse-string (slurp cursor-file) true)))
          (is (= ["2 save"] (map #(subs % 0 6) (events (second (run "follow" "--once")))))))))
    (testing "--after N moves the cursor"
      (run "follow" "--after" "0" "--count" "1")
      (is (= 0 (:seq (json/parse-string (slurp cursor-file) true)))))))

(deftest after-restart-test
  (testing "the repro: feed A, seqs 1-5, then B with 3 events; follow --after 5 gives B"
    (let [fake (ft/fake-emacsclient!)
          dir  (apply publish-to! (str (fs/path (str (fs/create-temp-dir)) "feed"))
                      (repeat 5 {:event "modify" :file "/h/w/a.org"}))
          _    (apply publish-to! dir (repeat 3 {:event "modify" :file "/h/w/a.org"}))
          r    (bb fake dir "follow" "--after" "5" "--once")
          lines (str/split-lines (:out r))]
      (is (= 0 (:exit r)))
      (is (= (str "! feed restarted at " (:started (feed/status dir)) ": seq starts again at 1")
             (first lines)))
      (is (= ["1 modify" "2 modify" "3 modify"]
             (map #(subs % 0 8) (remove #(str/starts-with? % "! ") lines)))))))

(deftest wrote-test
  (let [fake (ft/fake-emacsclient!)
        work (str (fs/create-temp-dir))
        wb   (str (fs/path work "wb.org"))
        hash (fn [text] (spit wb text) (str/trim (:out (p/shell {:out :string} "shasum" "-a" "256" wb))))
        h1   (subs (hash "* one\n") 0 64)
        h2   (subs (hash "* two\n") 0 64)
        dir  (publish-to! (str (fs/path (str (fs/create-temp-dir)) "feed"))
                          {:event "status" :file "/h/w/wb.org" :devops-mode true}
                          {:event "modify" :file "/h/w/wb.org" :origin "operator"}
                          {:event "save" :file "/h/w/wb.org" :origin "operator" :sha256 "0ff"}
                          {:event "revert" :file "/h/w/wb.org" :origin "operator" :sha256 h1}
                          {:event "revert" :file "/h/w/wb.org" :origin "operator" :sha256 h2}
                          {:event "save" :file "/h/w/wb.org" :origin "auto" :sha256 h1})
        run  (fn [& args] (let [r (apply bb fake dir args)]
                            [(:exit r) (str/trim (:out r)) (str/trim (:err r))]))
        events (fn [o] (remove #(str/starts-with? % "! ") (str/split-lines o)))
        wrote-file (str (fs/path fake "cursor" "wb.wrote"))]
    (testing "wrote FILE: its sha256, beside the cursor"
      (spit wb "* one\n")
      (let [[exit o err] (run "wrote" wb)]
        (is (= [0 ""] [exit err]))
        (is (= {:file wb :sha256 h1} (json/parse-string o true)))
        (is (= [{:file wb :sha256 h1}]
               (map #(json/parse-string % true) (str/split-lines (slurp wrote-file)))))))
    (testing "follow marks a revert to what the agent wrote, and only that, origin agent"
      (is (= ["1 modify operator /work/wb.org:all"
              "2 save operator /work/wb.org:all"
              "3 revert agent /work/wb.org:all"
              "4 revert operator /work/wb.org:all"
              "5 save auto /work/wb.org:all"]
             (events (second (run "follow" "--once")))))
      (is (= ["agent" "operator"]
             (->> (events (second (run "follow" "--once" "--json")))
                  (map #(json/parse-string % true))
                  (filter #(= "revert" (:event %)))
                  (map :origin)))))
    (testing "the last writes are kept, at most wrote-kept"
      (dotimes [i (inc cli/wrote-kept)]
        (spit wb (str "* " i "\n"))
        (run "wrote" wb))
      (let [kept (map #(json/parse-string % true) (str/split-lines (slurp wrote-file)))]
        (is (= cli/wrote-kept (count kept)))
        (is (not-any? #(= h1 (:sha256 %)) kept))
        (is (= ["3 revert operator"]
               (->> (events (second (run "follow" "--once")))
                    (filter #(str/includes? % " revert "))
                    (take 1)
                    (map #(str/join " " (take 3 (str/split % #" ")))))))))
    (testing "wrote needs a feed, and a file"
      (let [r (p/shell {:in "" :out :string :err :string :continue true :env (ft/bb-env fake {})}
                       "bb" "--config" ft/bb-edn "wrote" wb)]
        (is (= 1 (:exit r)))
        (is (str/includes? (:err r) "needs a feed")))
      (is (= 1 (first (run "wrote" (str wb ".missing"))))))))
