(ns devops.bb.repo
  "A repo in the sandbox, and back: git bundles between the operator's
  checkout and Claude's own clone, one workbook heading per branch.

  A heading names its task with a :PROPERTIES: drawer:

    :BRANCH:  api         the branch the work is committed on
    :BASE:    core        the branch it is cut from: main, or another heading's
    :REPO:    ~/src/...   the operator's checkout (or --repo); its name
                          names the bundle files

  The workbook need not be in that checkout, and nothing here looks for
  one there: the repo is the heading's :REPO:, never the workbook's
  location.  The workbook's own dir (`work', beside the .org) is the only thing
  the two sides share, three files of it, NAME being the repo's:

    NAME.bundle       seed: the operator's branches
    NAME.base.bundle  the same, again, when BASE has moved
    NAME.out.bundle   what Claude hands back: every branch with new commits

  In the sandbox: `clone!', `handoff!', `rebase!'.  On the host: `seed!',
  `land!', `status'.  None needs a worktree; `land!' respects one that has
  the branch checked out.  The host never runs git in Claude's clone,
  and fetches what Claude hands back only into refs/sandbox/*."
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [clojure.string :as str]
   [devops.bb.feed :as feed]
   [devops.bb.org :as org]))

(def work-path
  "Commits to this path are the operator's workbook, never Claude's."
  "work")

;;; git

(defn- fail! [msg] (throw (ex-info msg {})))

(defn- git*
  "Run git in DIR; {:exit :out :err}, out and err trimmed."
  [dir & args]
  (let [r (apply p/sh {:dir (str dir)
                       :extra-env {"GIT_PAGER" "cat" "GIT_TERMINAL_PROMPT" "0" "GIT_EDITOR" "true"}}
                 "git" "--no-pager" (remove nil? args))]
    {:exit (:exit r) :out (str/trim (:out r)) :err (str/trim (:err r))}))

(defn- git
  "Run git in DIR; its output, or throw with its error."
  [dir & args]
  (let [{:keys [exit out err]} (apply git* dir args)]
    (when-not (zero? exit)
      (fail! (str "git " (str/join " " (remove nil? args)) ": " (if (str/blank? err) out err))))
    out))

(defn- ok? [dir & args] (zero? (:exit (apply git* dir args))))

(defn- rev [dir ref]
  (let [{:keys [exit out]} (git* dir "rev-parse" "--verify" "-q" (str ref "^{commit}"))]
    (when (zero? exit) out)))

(defn- lines [s] (remove str/blank? (str/split-lines s)))

(defn- touching-work
  "The files under work/ that the commits in RANGE (rev-list args) touch."
  [dir & range-args]
  (->> (lines (apply git dir "log" "--name-only" "--pretty=format:" range-args))
       (filter #(str/starts-with? % (str work-path "/")))
       distinct))

;;; The task a heading names

(defn- read-workbook [opts]
  (let [wb (or (some-> (:workbook opts) fs/expand-home str)
               (some-> (feed/configured opts) feed/status :workbook)
               (fail! "no workbook: pass --workbook FILE"))]
    (when-not (fs/exists? wb) (fail! (str "no such workbook: " wb)))
    {:path (str (fs/absolutize wb)) :text (slurp wb)}))

(defn- seed-name
  "The name of the one seed bundle in DIR, if there is one."
  [dir]
  (let [names (->> (when (fs/directory? dir) (fs/glob dir "*.bundle"))
                   (map (comp str fs/file-name))
                   (remove #(re-find #"\.(base|out)\.bundle$" %))
                   (map #(subs % 0 (- (count %) (count ".bundle")))))]
    (when (= 1 (count names)) (first names))))

(defn task
  "The task OPTS name: {:branch :base :repo :name :work :workbook}.
  --heading (with --workbook, or the feed's) gives the BRANCH, BASE and
  REPO of a heading; --branch, --base, --repo and --name override it.
  :work is the workbook's dir, else --work, else /work.  :name is, failing
  those, the one seed bundle's.  :branch and :repo may be nil."
  [{:keys [heading branch base repo name work] :as opts}]
  (let [wb     (when (or heading (:workbook opts) (feed/configured opts)) (read-workbook opts))
        props  (when (and wb heading) (:props (org/find-heading (:text wb) heading)))
        branch (or branch (get props "BRANCH"))
        base   (or base (get props "BASE") "main")
        repo   (some-> (or repo (get props "REPO")) fs/expand-home str)
        work-dir (str (or (some-> wb :path fs/parent) (some-> work fs/expand-home) "/work"))]
    (when (= branch base) (fail! (str "branch and base are both " branch)))
    {:branch branch :base base :repo repo
     :name (or name (some-> repo fs/file-name str) (seed-name work-dir))
     :work work-dir
     :workbook (:path wb)}))

(defn- task! [opts]
  (let [t (task opts)]
    (when-not (:branch t) (fail! "no branch: pass --heading with a :BRANCH:, or --branch"))
    t))

(defn- need-name [{:keys [name]}]
  (or name (fail! "no repo name: pass --name, or give the heading a :REPO:")))

(defn- clone-dir
  "--dir, else the :REPO: when it is under HOME, so that a repo finding
  another by a relative path (bb.edn's ../orgstar) finds the copy beside
  it, else HOME/NAME."
  [{:keys [dir]} {:keys [repo]} name home]
  (let [home (fs/absolutize home)
        repo (some-> repo fs/absolutize)]
    (str (cond dir (fs/absolutize (fs/expand-home dir))
               (and repo (fs/starts-with? repo home) (not= repo home)) repo
               :else (fs/path home name)))))

;;; In the sandbox

(defn- base-key [branch] (str "refs/devops/base/" branch))

(defn- record-base! [dir branch base base-sha]
  (git dir "update-ref" (base-key branch) base-sha)
  (git dir "config" (str "branch." branch ".devopsbase") base))

(defn clone!
  "Clone NAME.bundle from the workbook's dir to the :REPO:, when it is
  under ~, else to ~/NAME (or --dir), on a new BRANCH cut from
  origin/BASE, and remember the base it was cut from for `rebase!'.
  {:dir :branch :base :base-sha}"
  [opts]
  (let [{:keys [branch base work] :as t} (task! opts)
        name   (need-name t)
        bundle (str (fs/path work (str name ".bundle")))
        dir    (clone-dir opts t name (fs/home))]
    (when-not (fs/exists? bundle) (fail! (str "no seed bundle: " bundle)))
    (when (fs/exists? dir) (fail! (str dir " exists")))
    (git "." "clone" "-q" bundle dir)
    (let [base-sha (or (rev dir (str "origin/" base))
                       (fail! (str "the bundle has no branch " base)))
          start    (if (rev dir (str "origin/" branch)) (str "origin/" branch) (str "origin/" base))]
      (git dir "switch" "-q" "-c" branch start)
      (record-base! dir branch base base-sha)
      ;; Commits need an author here, and nothing here signs.
      (when-not (ok? dir "config" "user.name")
        (git dir "config" "user.name" "Claude (sandbox)")
        (git dir "config" "user.email" "claude-sandbox@localhost"))
      (git dir "config" "commit.gpgsign" "false")
      {:dir dir :branch branch :base base :base-sha base-sha})))

(defn handoff!
  "Bundle every branch with commits origin does not have into
  NAME.out.bundle, by name.  Refuses uncommitted changes, nothing new,
  and commits that touch work/.  {:bundle :heads}"
  [opts]
  (let [{:keys [work] :as t} (task opts)
        name (need-name t)
        dir  (clone-dir opts t name (fs/home))
        out  (str (fs/path work (str name ".out.bundle")))
        new  ["--branches" "--not" "--remotes=origin"]]
    (when-not (fs/exists? dir) (fail! (str "no clone at " dir)))
    (when-not (str/blank? (git dir "status" "--porcelain"))
      (fail! "uncommitted changes in the clone: commit or stash them first"))
    (when (str/blank? (apply git dir "rev-list" new))
      (fail! "nothing to hand off: no branch has commits origin lacks"))
    (when-let [files (seq (apply touching-work dir new))]
      (fail! (str "commits touch " work-path "/, the operator's workbook: "
                  (str/join ", " files))))
    (apply git dir "bundle" "create" "-q" out new)
    {:bundle out :heads (lines (git dir "bundle" "list-heads" out))}))

(defn rebase!
  "Move BRANCH onto its base's new tip, from NAME.base.bundle: its own
  commits are replayed on it.  On a
  conflict, {:conflict [files]}, and --continue finishes once resolved.
  {:rebased :from :onto}, or {:unchanged true}."
  [opts]
  (let [{:keys [work branch] :as t} (task! opts)
        name    (need-name t)
        dir     (clone-dir opts t name (fs/home))
        pending (str "refs/devops/rebasing/" branch)
        base    (or (not-empty (:out (git* dir "config" (str "branch." branch ".devopsbase"))))
                    (:base t))
        finish! (fn [onto]
                  (record-base! dir branch base onto)
                  (git dir "update-ref" "-d" pending))
        conflict #(hash-map :conflict (lines (git dir "diff" "--name-only" "--diff-filter=U")))]
    (if (:continue opts)
      (let [onto (or (rev dir pending) (fail! (str "no rebase in progress for " branch)))]
        (if (ok? dir "rebase" "--continue")
          (do (finish! onto) {:rebased branch :onto onto})
          (conflict)))
      (let [bundle (str (fs/path work (str name ".base.bundle")))
            _      (when-not (fs/exists? bundle) (fail! (str "no base bundle: " bundle)))
            _      (git dir "fetch" "-q" bundle "+refs/heads/*:refs/remotes/origin/*")
            old    (or (rev dir (base-key branch)) (fail! (str "no recorded base for " branch)))
            onto   (or (rev dir (str "origin/" base)) (fail! (str "the bundle has no branch " base)))]
        (if (= old onto)
          {:unchanged true}
          (do (git dir "update-ref" pending onto)
              (git dir "switch" "-q" branch)
              ;; A base that only moved forward may already hold some of
              ;; the branch's commits (the agent's own fix to it, landed),
              ;; which only `rebase ONTO' knows to skip.  A rewritten base
              ;; (squashed, rebased) holds none of them as they were.
              (if (if (ok? dir "merge-base" "--is-ancestor" old onto)
                    (ok? dir "rebase" onto branch)
                    (ok? dir "rebase" "--onto" onto old branch))
                (do (finish! onto) {:rebased branch :from old :onto onto})
                (conflict))))))))

;;; On the host

(defn- toplevel
  "The checkout at REPO: any dir in it, a linked worktree's too."
  [repo]
  (when-not repo (fail! "no repo: pass --repo, or give the heading a :REPO:"))
  (when-not (fs/directory? repo) (fail! (str "no such repo: " repo)))
  (git repo "rev-parse" "--show-toplevel"))

(defn- repo-name
  "The checkout's name: its main worktree's dir, not a linked one's."
  [top]
  (let [common (fs/canonicalize (fs/path top (git top "rev-parse" "--git-common-dir")))]
    (str (fs/file-name (if (= ".git" (str (fs/file-name common))) (fs/parent common) top)))))

(defn- host-task
  "The task OPTS name, with its checkout."
  [opts]
  (let [wb  (read-workbook opts)
        t   (task! (assoc opts :workbook (:path wb)))
        top (toplevel (:repo t))]
    (assoc t :top top :name (or (:name t) (repo-name top)))))

(defn- seeded-key [branch] (str "refs/devops/seeded/" branch))

(defn seed!
  "Bundle the checkout's branches into the workbook's dir for the agent:
  NAME.bundle, or with --rebase NAME.base.bundle, for a branch whose base
  has moved.  {:bundle :base-sha}"
  [opts]
  (let [{:keys [top work name base branch]} (host-task opts)
        out (str (fs/path work (str name (if (:rebase opts) ".base" "") ".bundle")))
        sha (or (rev top (str "refs/heads/" base))
                (fail! (str "base " base " is not a local branch of " top)))]
    (git top "bundle" "create" "-q" out "--branches")
    (git top "update-ref" (seeded-key branch) sha)
    {:bundle out :base-sha sha}))

(defn- worktrees
  "{branch path} of the checkout's worktrees."
  [top]
  (->> (str/split (git top "worktree" "list" "--porcelain") #"\n\n")
       (keep (fn [blk]
               (let [ls (str/split-lines blk)
                     path (some #(second (re-matches #"worktree (.*)" %)) ls)
                     br   (some #(second (re-matches #"branch refs/heads/(.*)" %)) ls)]
                 (when br [br path]))))
       (into {})))

(defn- ahead [top from to] (parse-long (git top "rev-list" "--count" (str from ".." to))))

(defn land!
  "Fetch NAME.out.bundle into refs/sandbox/*, with fsck, and say what each
  branch it carries is next to the local one: :new, :same, :ff,
  :rebased (the same commits, rewritten) or :diverged, with its log.  Refuses commits that touch work/.  With
  --apply, moves the :ff and :rebased ones, in the worktree that has them
  checked out, and makes the :new.  [{:branch :state :log}]"
  [opts]
  (let [{:keys [top work name base]} (host-task opts)
        bundle (str (fs/path work (str name ".out.bundle")))
        _      (when-not (fs/exists? bundle) (fail! (str "no bundle from the agent: " bundle)))
        heads  (->> (lines (git top "bundle" "list-heads" bundle))
                    (keep #(second (re-matches #"\S+ refs/heads/(.*)" %))))
        _      (git top "-c" "transfer.fsckObjects=true" "fetch" "-q" bundle
                    "+refs/heads/*:refs/sandbox/*")
        wts    (worktrees top)]
    (vec
     (for [b heads
           :let [ref   (str "refs/sandbox/" b)
                 local (rev top (str "refs/heads/" b))
                 tip   (rev top ref)
                 from  (or local (rev top (str "refs/heads/" base)))
                 state (cond (nil? local) :new
                             (= local tip) :same
                             (ok? top "merge-base" "--is-ancestor" local tip) :ff
                             ;; Rebased: every local commit is in the tip, by patch.
                             (not-any? #(str/starts-with? % "+")
                                       (lines (git top "cherry" tip local))) :rebased
                             :else :diverged)
                 files (touching-work top (str from ".." ref))]]
       (do
         (when (seq files)
           (fail! (str b " touches " work-path "/, the operator's workbook: " (str/join ", " files))))
         (when (and (:apply opts) (#{:ff :new :rebased} state))
           (cond (= state :new) (git top "branch" b ref)
                 ;; --keep: the worktree's own work/ edits stay.
                 (wts b)        (if (= state :ff)
                                  (git (wts b) "merge" "--ff-only" "-q" ref)
                                  (git (wts b) "reset" "--keep" "-q" ref))
                 :else          (git top "update-ref" (str "refs/heads/" b) tip local)))
         {:branch b :state state
          :log (git top "log" "--stat" "--format=%h %s" (str from ".." ref))})))))

(defn status
  "One map per heading with a :BRANCH:, in order, over every workbook
  --workbook names (once or more): the worktree that has it checked out,
  if any, its commits over BASE, whether it was seeded from the base's
  current tip (:seed is :none, :current or :stale), and how many commits
  the agent handed back that are not landed.  Each heading's repo is its own :REPO:, or --repo."
  [opts]
  (let [wbs (let [w (:workbook opts)] (if (sequential? w) w [w]))]
    (vec
     (for [wb wbs
           {:keys [title props]} (org/headings (:text (read-workbook (assoc opts :workbook wb))))
           :let [branch (get props "BRANCH") base (get props "BASE" "main")]
           :when branch]
       (let [top    (toplevel (some-> (or (:repo opts) (get props "REPO")) fs/expand-home str))
             wts    (worktrees top)
             bsha   (rev top (str "refs/heads/" base))
             seeded (rev top (seeded-key branch))
             local  (rev top (str "refs/heads/" branch))
             sand   (rev top (str "refs/sandbox/" branch))]
         {:title title :branch branch :base base
          :worktree (wts branch)
          :commits (when (and local bsha) (ahead top bsha local))
          :seed (cond (nil? seeded) :none (= seeded bsha) :current :else :stale)
          :unlanded (when sand (ahead top (or local bsha sand) sand))})))))

;;; Tasks

(defn- run [f opts print-fn]
  (try
    (print-fn (f opts))
    (catch clojure.lang.ExceptionInfo e
      (binding [*out* *err*] (println (str "error: " (ex-message e))))
      (System/exit 1))))

(def ^:private heading-spec
  {:heading  {:desc "A heading's :ID: or part of its title, for BRANCH, BASE, REPO"}
   :workbook {:desc "The workbook; default: the feed's"}
   :branch   {:desc "Overrides the heading's :BRANCH:"}
   :base     {:desc "Overrides the heading's :BASE: (default main)"}
   :repo     {:desc "The operator's checkout; default: the heading's :REPO:"}
   :name     {:desc "The repo's name for the bundle files; default: the heading's :REPO:"}
   :dir      {:desc "Claude's clone; default the :REPO:, when under ~, else ~/NAME"}})

(defn- short [sha] (subs sha 0 7))

(defn repo-clone
  "Clone the seed bundle in the workbook's dir, on the heading's branch"
  {:org.babashka/cli {:spec heading-spec}}
  [opts]
  (run clone! opts (fn [{:keys [dir branch base base-sha]}]
                     (println (str dir ": " branch " from " base " at " (short base-sha))))))

(defn repo-handoff
  "Bundle every branch with new commits into the workbook's dir"
  {:org.babashka/cli {:spec heading-spec}}
  [opts]
  (run handoff! opts (fn [{:keys [bundle heads]}] (println bundle) (run! println heads))))

(defn repo-rebase
  "Rebase the heading's branch onto its base's new tip, from NAME.base.bundle"
  {:org.babashka/cli {:spec (assoc heading-spec :continue {:desc "Finish after resolving conflicts"
                                                           :coerce :boolean})}}
  [opts]
  (run rebase! opts
       (fn [{:keys [conflict unchanged rebased onto]}]
         (cond conflict  (do (println "conflict in:" (str/join ", " conflict))
                             (println "resolve, `git add', then `devops-bb repo rebase --continue'")
                             (System/exit 2))
               unchanged (println "base unchanged")
               :else     (println (str rebased " rebased onto " (short onto)))))))

(defn bundle-seed
  "Bundle the branches for the agent; --rebase when the base has moved"
  {:org.babashka/cli {:spec (assoc heading-spec :rebase {:desc "Write NAME.base.bundle"
                                                         :coerce :boolean})
                      :require [:workbook :heading]}}
  [opts]
  (run seed! opts (fn [{:keys [bundle base-sha]}]
                    (println (str bundle " (base at " (short base-sha) ")")))))

(defn bundle-land
  "Fetch what the agent handed back into refs/sandbox/*; --apply fast-forwards"
  {:org.babashka/cli {:spec (assoc heading-spec :apply {:desc "Fast-forward branches, make new ones"
                                                        :coerce :boolean})
                      :require [:workbook :heading]}}
  [opts]
  (run land! opts
       (fn [rs]
         (doseq [{:keys [branch state log]} rs]
           (println (str "== " branch " (" (name state) ")"))
           (when-not (str/blank? log) (println log)))
         (when-not (:apply opts)
           (println "\nnothing moved; --apply to fast-forward"))
         (when (some #(= :diverged (:state %)) rs)
           (println "diverged: bundle seed --rebase, then the agent runs repo rebase")
           (System/exit 2)))))

(defn bundle-status
  "The workbooks' headings with a :BRANCH:, and where each stands"
  {:org.babashka/cli {:spec {:workbook {:desc "A workbook; once per workbook" :coerce []}
                             :repo     {:desc "Overrides every heading's :REPO:"}}
                      :require [:workbook]}}
  [opts]
  (run status opts
       (fn [rows]
         (doseq [{:keys [branch base commits seed unlanded worktree]} rows]
           (println (format "%-24s on %-16s %2s commits  seed %-7s %s%s"
                            branch base (or commits "-") (name seed)
                            (if (some-> unlanded pos?) (str unlanded " unlanded  ") "")
                            (or worktree "no worktree")))))))
