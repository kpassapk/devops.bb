(ns devops.bb.log-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is testing]]
   [devops.bb.log :as log]))

(def fixture "test/fixtures/executions.jsonl")

(deftest read-from-test
  (let [{:keys [events offset]} (log/read-from fixture 0)]
    (testing "skips lines that aren't JSON"
      (is (= [:execute :result :tangle :drift :tangle :execute] (map :event events))))
    (testing "parses devops-log.el's time"
      (is (= 17 (.getMinute (:time (first events))))))
    (testing "nothing new past the end"
      (is (= [] (:events (log/read-from fixture offset)))))))

(deftest partial-and-truncated-test
  (let [f (str (fs/create-temp-file {:suffix ".jsonl"}))]
    (spit f "{\"event\":\"execute\"}\n{\"event\":\"res")
    (let [{:keys [events offset]} (log/read-from f 0)]
      (testing "a partial last line waits for the next read"
        (is (= [:execute] (map :event events)))
        (spit f "ult\"}\n" :append true)
        (is (= [:result] (map :event (:events (log/read-from f offset))))))
      (testing "a file shorter than the offset is read again from the start"
        (spit f "{\"event\":\"drift\"}\n")
        (let [r (log/read-from f 1000)]
          (is (:reset r))
          (is (= [:drift] (map :event (:events r)))))))
    (fs/delete f)))
