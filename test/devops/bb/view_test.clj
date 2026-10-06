(ns devops.bb.view-test
  "Screens as text: what the dashboard draws, with the color taken out."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]
   [devops.bb.log :as log]
   [devops.bb.model :as model]
   [devops.bb.view :as view]))

(def state
  {:log-path "~/.cache/devops/executions.jsonl"
   :emacs?   true
   :model    (model/add-events model/empty-model
                               (:events (log/read-from "test/fixtures/executions.jsonl" 0)))
   :now      (log/parse-time "2026-10-06T11:23:05-0600")
   :width 80 :height 12 :cursor 1 :top 0 :page :feed})

(defn- screen [s] (mapv str/trimr (str/split-lines (view/strip-ansi s))))

(deftest feed-test
  (is (= [" devops.bb · ~/.cache/devops/executions.jsonl · emacs ●                11:23:05"
          "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
          " 11:23:00  ▶ execute  db.org:77            db-1 /ssh:db-1:              running…"
          " 11:22:10  ✗ tangle   db.org:12            No target on this h…            error"
          " 11:21:47  ≠ drift    infra.org            all headings          1 drift, 1 same"
          " 11:20:02  ⇣ tangle   infra.org:100        nginx                         2 files"
          " 11:17:50  ✓ result   infra.org:118        web-1 /ssh:web-1:                3.4s"
          " 11:17:47  ▶ execute  infra.org:118        web-1 /ssh:web-1:"
          ""
          ""
          "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
          " ▶1 running ✓1 done ⇣2 tangled =1 drift ✗2 problems     ⏎ open  e emacs  q quit"]
         (screen (view/feed state)))))

(deftest every-line-fits-test
  (doseq [w [40 80 120]]
    (is (every? #(<= (count %) w) (screen (view/feed (assoc state :width w)))))))

(deftest drift-detail-test
  (let [e (nth (vec (model/newest-first (:model state))) 2)]
    (is (= ["━━ files ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
            "drift    web        /ssh:web-1:/etc/nginx/nginx.conf"
            "same     web        /ssh:web-1:/etc/hosts"]
           (->> (view/detail (assoc state :page :detail :width 60
                                    :detail {:event e :status :ok :scroll 0}))
                screen
                (drop 2)
                (take 3)
                (map str/triml))))))

(deftest block-detail-test
  (let [e (first (:events (:model state)))
        s (screen (view/detail (assoc state :page :detail
                                      :height 24
                                      :detail {:event e :status :ok :scroll 0
                                               :data {:run {:session "devops:web-1 /ssh:web-1:" :status "done" :id "a1" :input "df -h /"
                                                            :output "\u001b[32mok\u001b[0m\r\nfilesystem 42%"}
                                                      :output {:session "devops:web-1 /ssh:web-1:"
                                                               :status "done" :id "a9" :source "session"
                                                               :output "a later run"}
                                                      :block {:language "sh" :body "df -h /" :line-start 117
                                                              :line-end 119 :heading "Check disk"}}})))]
    (is (= " infra.org:118 · Check disk · execute · 11:17:47" (first s)))
    (is (some #{" df -h /"} s))
    (is (some #{" ok"} s))
    (is (some #{" filesystem 42%"} s))))

(deftest block-detail-fallback-test
  (let [e (first (:events (:model state)))
        s (screen (view/detail (assoc state :page :detail :height 24
                                      :detail {:event e :status :ok :scroll 0
                                               :data {:run {:status "not-found" :id "a1"}
                                                      :output {:session "devops:web-1 /ssh:web-1:"
                                                               :status "not-found" :source "results"
                                                               :output "from results"}}})))]
    (is (some #{" this run is gone from the session; showing #+RESULTS"} s))
    (is (some #{" from results"} s))))

(deftest changed-block-test
  (let [e (first (:events (:model state)))
        s (screen (view/detail (assoc state :page :detail :height 24
                                      :detail {:event e :status :ok :scroll 0
                                               :data {:run {:status "done" :id "a1" :input "ls" :output "x"}
                                                      :block {:language "sh" :body "sleep 10" :line-start 117
                                                              :line-end 119 :heading "Other"}}})))]
    (is (= " infra.org:118 · execute · 11:17:47" (first s)))
    (is (some #{" ls"} s))
    (is (not-any? #{" sleep 10"} s))))
