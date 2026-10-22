(ns devops.bb.org-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [devops.bb.org :as org]))

(def text
  "#+TITLE: wb
* Intro
:PROPERTIES:
:BRANCH: not-a-task-but-fine
:END:
** TODO 1. Core model                                          :repo:core:
:PROPERTIES:
:BRANCH:   core
:BASE:     main
:ID:       core-task
:END:
Body with :BRANCH: nope
** DONE 2. API
SCHEDULED: <2026-10-21 Wed>
:PROPERTIES:
:BRANCH: api
:BASE:   core
:END:
** 3. No drawer here
text
:PROPERTIES:
:BRANCH: late
:END:
")

(deftest headings-test
  (let [[intro core api none] (org/headings text)]
    (is (= 4 (count (org/headings text))))
    (is (= {"BRANCH" "not-a-task-but-fine"} (:props intro)))
    (is (= {:level 2 :line 6 :todo "TODO" :title "1. Core model" :tags #{"repo" "core"}
            :props {"BRANCH" "core" "BASE" "main" "ID" "core-task"}}
           core))
    (is (= {"BRANCH" "api" "BASE" "core"} (:props api)))
    (is (= "DONE" (:todo api)))
    (is (= {} (:props none)) "a drawer after body text is not the heading's")))

(deftest find-heading-test
  (is (= "core" (get-in (org/find-heading text "core-task") [:props "BRANCH"])))
  (is (= "api" (get-in (org/find-heading text "2. api") [:props "BRANCH"])))
  (testing "no match, several matches"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no heading" (org/find-heading text "zzz")))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"several" (org/find-heading text "."))))) 
