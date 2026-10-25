(ns devops.bb.repo-test
  "The core/API loop, with real git in a temp dir: two headings, each in
  a workbook of its own outside the checkout, two operator worktrees, an agent's
  clone that fixes core from the API's side, a base that moves, and a
  rebase."
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [devops.bb.repo :as repo]))

(defn- git [dir & args]
  (let [r (apply p/sh {:dir (str dir)
                       :extra-env {"GIT_AUTHOR_NAME" "t" "GIT_AUTHOR_EMAIL" "t@t"
                                   "GIT_COMMITTER_NAME" "t" "GIT_COMMITTER_EMAIL" "t@t"}}
                 "git" "--no-pager" args)]
    (when-not (zero? (:exit r)) (throw (ex-info (str args (:err r)) r)))
    (str/trim (:out r))))

(defn- commit! [dir file text msg]
  (fs/create-dirs (fs/parent (fs/path dir file)))
  (spit (str (fs/path dir file)) text)
  (git dir "add" file)
  (git dir "commit" "-q" "-m" msg)
  (git dir "rev-parse" "HEAD"))

(defn- heading [title branch base top]
  (str "* TODO " title "\n:PROPERTIES:\n:BRANCH: " branch "\n:BASE: " base
       "\n:REPO: " top "\n:END:\n"))

(defn- workbook!
  "A task's workbook, in a dir of its own under ROOT/active, as aly-root
  lays them out: one heading, its :REPO: the checkout TOP."
  [root dir title branch base top]
  (let [f (fs/path root "active" dir "work.org")]
    (fs/create-dirs (fs/parent f))
    (spit (str f) (str "#+TITLE: " dir "\n" (heading title branch base top)))
    (str f)))

(defn- worktree!
  "The operator's own worktree for BRANCH, cut from BASE, beside TOP as
  NAME-BRANCH.  Its path."
  [top branch base]
  (let [path (str (fs/path (fs/parent top) (str (fs/file-name top) "-" branch)))]
    (git top "worktree" "add" "-q" "-b" branch path base)
    path))

(defn- fixture
  "A checkout `atomstream', with its own workbooks committed under work/,
  in a temp dir."
  []
  (let [root (str (fs/canonicalize (fs/create-temp-dir)))
        top  (str (fs/path root "atomstream"))]
    (fs/create-dirs top)
    (git top "init" "-q" "-b" "main")
    (commit! top "src/model.clj" "v1\n" "model v1")
    (commit! top "work/todos.org" "* TODO the repo's own\n" "workbook")
    {:root root :top top}))

(deftest core-api-loop-test
  (let [{:keys [root top]} (fixture)
        core-wb (workbook! root "core" "Core model" "core" "main" top)
        api-wb  (workbook! root "api" "API" "api" "core" top)
        core   (worktree! top "core" "main")
        api    (worktree! top "api" "core")
        todos  (str (fs/path api "work/todos.org"))
        agent  (str (fs/path root "agent-api"))]
    (is (= "atomstream-core" (str (fs/file-name core))))
    (is (not (fs/exists? (fs/path api "work" "work.org"))) "no workbook is copied in")
    (spit todos "edited, uncommitted\n")

    (testing "status reads every workbook, each heading against its :REPO:"
      (is (= [["core" "main" core] ["api" "core" api]]
             (map (juxt :branch :base :worktree) (repo/status {:workbook [core-wb api-wb]})))))

    (testing "seed, then the agent clones on its branch from the base"
      (let [{:keys [bundle]} (repo/seed! {:workbook api-wb :heading "API"})]
        (is (= "atomstream.bundle" (str (fs/file-name bundle))) "named for the :REPO:")
        (is (= (str (fs/parent api-wb)) (str (fs/parent bundle))) "beside the workbook"))
      (let [r (repo/clone! {:workbook api-wb :heading "API" :dir agent})]
        (is (= "api" (:branch r)))
        (is (= (git top "rev-parse" "core") (:base-sha r)))
        (is (= "api" (git agent "branch" "--show-current")))))

    (testing "the agent fixes core from the API's side, rebases api, hands back both"
      (git agent "switch" "-q" "-c" "core" "origin/core")
      (commit! agent "src/model.clj" "v2\n" "core: model v2")
      (git agent "switch" "-q" "api")
      (git agent "rebase" "-q" "core")
      (commit! agent "src/api.clj" "api\n" "api: handlers")
      (let [{:keys [heads]} (repo/handoff! {:workbook api-wb :heading "API" :dir agent})]
        (is (= #{"refs/heads/core" "refs/heads/api"}
               (set (map #(second (str/split % #" ")) heads))))))

    (testing "land: nothing moves without --apply; then both fast-forward in their worktrees"
      (let [before (git top "rev-parse" "core")
            rs     (repo/land! {:workbook api-wb :heading "API"})]
        (is (= {"core" :ff "api" :ff} (into {} (map (juxt :branch :state)) rs)))
        (is (= before (git top "rev-parse" "core"))))
      (repo/land! {:workbook api-wb :heading "API" :apply true})
      (is (= "v2" (str/trim (slurp (str (fs/path core "src/model.clj"))))))
      (is (= "api" (str/trim (slurp (str (fs/path api "src/api.clj"))))))
      (is (= "edited, uncommitted\n" (slurp todos)) "the repo's own work/ edit stays"))

    (testing "the base moves: seed --rebase, the agent rebases api onto it"
      (commit! core "src/model.clj" "v3\n" "core: model v3, by hand")
      (let [new-core (git top "rev-parse" "core")]
        (is (= [:stale] (->> (repo/status {:workbook api-wb}) (filter #(= "api" (:branch %))) (map :seed))))
        (repo/seed! {:workbook api-wb :heading "API" :rebase true})
        (is (= [:current] (->> (repo/status {:workbook api-wb}) (filter #(= "api" (:branch %))) (map :seed))))
        (let [r (repo/rebase! {:workbook api-wb :heading "API" :dir agent})]
          (is (= (:onto r) new-core))
          (is (nil? (:conflict r))))
        (is (= new-core (git agent "merge-base" "api" new-core)))
        (is (= {:unchanged true} (repo/rebase! {:workbook api-wb :heading "API" :dir agent})))))

    (testing "land sees the rewritten api as :rebased and moves it, keeping work/"
      (git agent "switch" "-q" "core")
      (git agent "merge" "-q" "--ff-only" "origin/core")
      (git agent "switch" "-q" "api")
      (repo/handoff! {:workbook api-wb :heading "API" :dir agent})
      (let [rs (repo/land! {:workbook api-wb :heading "API" :apply true})]
        (is (= :rebased (:state (first (filter #(= "api" (:branch %)) rs))))))
      (is (= (git agent "rev-parse" "api") (git top "rev-parse" "api")))
      (is (= "edited, uncommitted\n" (slurp todos))))))

(deftest no-worktree-test
  (let [{:keys [root top]} (fixture)
        api-wb (workbook! root "api" "API" "api" "main" top)
        agent (str (fs/path root "agent"))
        land  #(into {} (map (juxt :branch :state)) (repo/land! {:workbook api-wb :heading "API" :apply true}))]
    (repo/seed! {:workbook api-wb :heading "API"})
    (repo/clone! {:workbook api-wb :heading "API" :dir agent})
    (commit! agent "src/api.clj" "api\n" "api: handlers")
    (repo/handoff! {:workbook api-wb :heading "API" :dir agent})
    (testing "land makes the branch, which nothing has checked out"
      (is (= {"api" :new} (land)))
      (is (= (git agent "rev-parse" "api") (git top "rev-parse" "api"))))
    (testing "and moves it, with no worktree to move it in"
      (commit! agent "src/api.clj" "api v2\n" "api: v2")
      (repo/handoff! {:workbook api-wb :heading "API" :dir agent})
      (is (= {"api" :ff} (land)))
      (is (= (git agent "rev-parse" "api") (git top "rev-parse" "api")))
      (is (= "main" (git top "branch" "--show-current")))
      (is (nil? (:worktree (first (repo/status {:workbook api-wb}))))))))

(deftest rebase-conflict-test
  (let [{:keys [root top]} (fixture)
        api-wb (workbook! root "api" "API" "api" "main" top)
        agent (str (fs/path root "agent"))]
    (worktree! top "api" "main")
    (repo/seed! {:workbook api-wb :heading "API" :base "main"})
    (repo/clone! {:workbook api-wb :heading "API" :base "main" :dir agent})
    (commit! agent "src/model.clj" "agent\n" "agent edit")
    (commit! top "src/model.clj" "host\n" "host edit")
    (repo/seed! {:workbook api-wb :heading "API" :base "main" :rebase true})
    (let [r (repo/rebase! {:workbook api-wb :heading "API" :base "main" :dir agent})]
      (is (= ["src/model.clj"] (:conflict r)))
      (spit (str (fs/path agent "src/model.clj")) "both\n")
      (git agent "add" "src/model.clj")
      (let [r (repo/rebase! {:workbook api-wb :heading "API" :base "main" :dir agent :continue true})]
        (is (= (git top "rev-parse" "main") (:onto r))))
      (is (= {:unchanged true} (repo/rebase! {:workbook api-wb :heading "API" :base "main" :dir agent}))))))

(deftest guards-test
  (let [{:keys [root top]} (fixture)
        api-wb (workbook! root "api" "API" "api" "main" top)
        agent (str (fs/path root "agent"))]
    (worktree! top "api" "main")
    (repo/seed! {:workbook api-wb :heading "API" :base "main"})
    (repo/clone! {:workbook api-wb :heading "API" :base "main" :dir agent})
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"nothing to hand off"
                          (repo/handoff! {:workbook api-wb :heading "API" :dir agent})))
    (commit! agent "work/wb.org" "agent was here\n" "touches the workbook")
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"touch work/"
                          (repo/handoff! {:workbook api-wb :heading "API" :dir agent})))
    (testing "the host checks too, whatever the agent's bundle holds"
      (git agent "bundle" "create" "-q" (str (fs/path (fs/parent api-wb) "atomstream.out.bundle"))
           "--branches" "--not" "--remotes=origin")
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"touches work/"
                            (repo/land! {:workbook api-wb :heading "API"}))))
    (testing "a heading with no :REPO: needs --repo on the host"
      (let [wb (str (fs/path root "active" "bare.org"))]
        (spit wb "* TODO Bare\n:PROPERTIES:\n:BRANCH: bare\n:END:\n")
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no repo"
                              (repo/seed! {:workbook wb :heading "Bare"})))
        (is (:bundle (repo/seed! {:workbook wb :heading "Bare" :repo top})))))
    (testing "no clone twice, and the same branch as its base"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"exists"
                            (repo/clone! {:workbook api-wb :heading "API" :base "main" :dir agent})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"both"
                            (repo/clone! {:workbook api-wb :heading "API" :base "api" :dir agent}))))))
