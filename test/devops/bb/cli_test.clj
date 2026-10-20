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
         (cli/summary-line {:event :tangle :all false :file "/w/a.org" :line 9 :targets []}))))

;;; Feed mode

(def id ft/id1)

(defn- publish!
  "A feed in a new dir, published from ENTRIES; the dir."
  [& entries]
  (let [dir (str (fs/path (str (fs/create-temp-dir)) "feed"))]
    @(:done (publish/start! {:to dir :map ["/h/w=/work"] :name "wb" :heartbeat-ms 60000}
                            (java.io.StringReader.
                             (str/join (map #(str (json/generate-string %) "\n") entries)))))
    dir))

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
               (str/split-lines (slurp log))))
        (testing "and says the feed is detached"
          (is (str/includes? (slurp errs) "notice: feed detached")))))
    (testing "no emacsclient ran"
      (is (not (fs/exists? (fs/path fake "called")))))))

(defn- put-status! [dir m] (feed/put (feed/local-dir dir) feed/status-file (json/generate-string m)))
(defn- append! [dir & seqs]
  (doseq [n seqs] (feed/append (feed/local-dir dir) (json/generate-string {:event "save" :seq n}))))

(deftest feed-poll-test
  (let [dir  (str (fs/create-temp-dir))
        now  (OffsetDateTime/parse "2026-10-19T10:00:00-06:00")
        at   (fn [s] (str (.plusSeconds now s)))
        poll-in (fn [dir st secs] (cli/feed-poll dir st (.plusSeconds now secs)))
        poll (partial poll-in dir)]
    (feed/put (feed/local-dir dir) feed/events-file "")
    (put-status! dir {:started (at 0) :publisher-time (at 0) :attached true :queued 0})
    (append! dir 1 2 2)
    (let [[st evs ns] (poll {} 1)]
      (testing "new events, a duplicate skipped, nothing to say"
        (is (= [1 2] (map :seq evs)))
        (is (= [] ns)))
      (testing "a stale heartbeat is said once"
        (let [[st _ ns] (poll st 20)]
          (is (= ["publisher heartbeat is 20 s old"] ns))
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
      (testing "a restart: said, and read again from seq 1"
        (feed/put (feed/local-dir dir) feed/events-file "")
        (put-status! dir {:started (at 30) :publisher-time (at 30) :attached true :queued 0})
        (append! dir 1)
        (let [[st evs ns] (poll st 31)]
          (is (= [1] (map :seq evs)))
          (is (= [(str "feed restarted at " (at 30) ": seq starts again at 1")] ns))
          (testing "detached"
            (put-status! dir {:started (at 30) :publisher-time (at 32) :attached false :queued 0})
            (let [[st _ ns] (poll st 40)]
              (is (= 1 (count ns)))
              (is (str/starts-with? (first ns) "feed detached"))
              (is (= [] (nth (poll st 41) 2))))))))
    (testing "a restart seen in events.jsonl before status.json"
      (let [dir  (str (fs/create-temp-dir))
            poll (partial poll-in dir)]
        (put-status! dir {:started (at 0) :publisher-time (at 0) :attached true :queued 0})
        (append! dir 1 2 3)
        (let [[st] (poll {} 1)]
          (feed/put (feed/local-dir dir) feed/events-file "")
          (append! dir 1)
          (let [[st evs] (poll st 2)]
            (is (= [1] (map :seq evs)))
            (put-status! dir {:started (at 2) :publisher-time (at 2) :attached true :queued 0})
            (append! dir 2)
            (let [[_ evs ns] (poll st 3)]
              (is (= [2] (map :seq evs)))
              (is (= 1 (count ns))))))))))
