(ns devops.bb.cli
  "devops-bb: devops.el from the command line, for agents and scripts.

  Each command asks the operator's Emacs one question through
  devops.bb.emacs and prints the answer as one line of JSON.  An error
  goes to stderr as `error: ...', with exit status 1.  `follow' is the
  exception: it reads the log, not Emacs, and prints a line per event."
  (:require
   [babashka.fs :as fs]
   [cheshire.core :as json]
   [clojure.string :as str]
   [devops.bb.emacs :as emacs]
   [devops.bb.log :as log]))

(defn- elisp-str [s] (pr-str (str s)))

(defn- elisp-opt [s] (if s (elisp-str s) "nil"))

(defn- abs [file] (str (fs/absolutize (fs/expand-home file))))

(defn- fail [msg]
  (binding [*out* *err*] (println (str "error: " msg)))
  (System/exit 1))

(defn- answer
  "Print what FORM, elisp, evaluates to in Emacs, as JSON."
  [form]
  (try
    (println (json/generate-string (emacs/eval-json form)))
    (catch Exception e (fail (ex-message e)))))

;;; Commands

(defn status
  "Whether Emacs is reachable, devops-mode is on, and where it logs"
  [_]
  (answer "(progn (require 'devops)
             (list (cons :devops-mode (if (bound-and-true-p devops-mode) t :json-false))
                   (cons :execution-log (and devops-execution-log
                                             (expand-file-name devops-execution-log)))))"))

(defn output
  "What the src block at LINE of FILE printed: session, else #+RESULTS"
  {:org.babashka/cli {:args->opts [:file :line]
                      :coerce {:line :long}
                      :require [:file :line]}}
  [{:keys [file line tag]}]
  (answer (format "(progn (require 'devops-scripting)
                     (devops-scripting-block-output %s %d %s))"
                  (elisp-str (abs file)) line (elisp-opt tag))))

(defn run-output
  "What the run ID printed, from its session alone"
  {:org.babashka/cli {:args->opts [:id] :require [:id]}}
  [{:keys [id session]}]
  ;; Without SESSION, every live session is asked: an ID is unique.
  (answer (format "(progn (require 'devops-scripting)
                     (let ((id %s) (session %s))
                       (if session
                           (devops-scripting-run-output session id)
                         (or (seq-some
                              (lambda (s)
                                (let ((r (devops-scripting-run-output (alist-get :name s) id)))
                                  (and (not (eq (alist-get :status r) :not-found)) r)))
                              (devops-scripting-sessions))
                             (list (cons :session nil) (cons :status :not-found)
                                   (cons :id id) (cons :input nil) (cons :output nil))))))"
                  (elisp-str id) (elisp-opt session))))

(defn sessions
  "Every live async session: name, directory, state, prompt, id"
  [_]
  (answer "(progn (require 'devops-scripting) (vconcat (devops-scripting-sessions)))"))

(defn modified
  "Whether Emacs holds unsaved edits to FILE: true, or false"
  {:org.babashka/cli {:args->opts [:file] :require [:file]}}
  [{:keys [file]}]
  (answer (format "(let ((b (find-buffer-visiting %s)))
                     (if (and b (buffer-modified-p b)) t :json-false))"
                  (elisp-str (abs file)))))

(defn revert
  "Reload FILE's buffer from disk, if Emacs visits it and it has no unsaved edits"
  {:org.babashka/cli {:args->opts [:file] :require [:file]}}
  [{:keys [file]}]
  (answer (format "(let ((b (find-buffer-visiting %s)))
                     (cond ((not b) :not-visited)
                           ((buffer-modified-p b) (error \"%%s has unsaved edits\" (buffer-name b)))
                           (t (with-current-buffer b (revert-buffer t t t)) :reverted)))"
                  (elisp-str (abs file)))))

(defn tools
  "The tools.org blocks loaded for #+call:, matching REGEXP if given"
  {:org.babashka/cli {:args->opts [:regexp]}}
  [{:keys [regexp]}]
  (answer (format "(progn (require 'devops)
                     (vconcat
                      (mapcar (lambda (e)
                                (list (cons :name (format \"%%s\" (nth 0 e)))
                                      (cons :language (nth 1 e))
                                      (cons :vars (vconcat (mapcar #'cdr (nth 2 e))))))
                              (devops-org-tool-blocks %s))))"
                  (elisp-opt regexp))))

(defn drift
  "Compare what FILE tangles with each target: all headings, or one"
  {:org.babashka/cli {:args->opts [:file] :require [:file]}}
  [{:keys [file heading custom-id]}]
  (answer (format "(progn (require 'devops-drift)
                     (vconcat %s))"
                  (cond heading   (format "(devops-drift-headline %s %s)"
                                          (elisp-str (abs file)) (elisp-str heading))
                        custom-id (format "(devops-drift-custom-id %s %s)"
                                          (elisp-str (abs file)) (elisp-str custom-id))
                        :else     (format "(devops-drift-all %s)" (elisp-str (abs file)))))))

;;; Following the log

(defn summary-line
  "One short line for event E: what, how it went, where, and the run ID."
  [e]
  (str/join " " (remove str/blank?
                        [(name (:event e))
                         (cond (:error e)               "error"
                               (= :drift (:event e))    (if (every? #(= "same" (:status %)) (:files e))
                                                          "same" "drift")
                               (= :tangle (:event e))   "done"
                               :else                    (str (:status e)))
                         (str (:file e) ":" (if (true? (:all e)) "all" (or (:line e) "all")))
                         (:id e)])))

(defn follow
  "Print a line per new event in the execution log, for files under DIR"
  [{:keys [dir log json]}]
  (let [path (or (some-> log fs/expand-home str) (emacs/log-path)
                 (fail "no log: devops-execution-log is nil or Emacs is out of reach; pass --log"))
        dir  (str (fs/normalize (fs/absolutize (or dir "."))) "/")]
    (loop [offset (:offset (log/read-from path 0))]
      (let [{:keys [events offset]} (log/read-from path offset)]
        (doseq [e events
                :when (str/starts-with? (str (:file e)) dir)]
          (println (if json
                     (json/generate-string (-> e (dissoc :time) (assoc :time (:time-str e)) (dissoc :time-str)))
                     (summary-line e)))
          (flush))
        (Thread/sleep 300)
        (recur offset)))))
