(ns devops.bb.model-test
  (:require
   [clojure.test :refer [deftest is]]
   [devops.bb.log :as log]
   [devops.bb.model :as model]))

(def m (model/add-events model/empty-model
                         (:events (log/read-from "test/fixtures/executions.jsonl" 0))))

(deftest pairing-test
  (let [[exec result] (:events m)]
    (is (:ended exec))
    (is (not (model/running? exec)))
    (is (= 3 (.getSeconds (:duration result))))))

(deftest summary-test
  (is (= {:running 1 :results 1 :tangles 2 :drifts 1 :problems 2}
         (model/summary m))))
