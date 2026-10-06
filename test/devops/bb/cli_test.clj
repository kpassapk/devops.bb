(ns devops.bb.cli-test
  (:require
   [clojure.test :refer [deftest is]]
   [devops.bb.cli :as cli]))

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
