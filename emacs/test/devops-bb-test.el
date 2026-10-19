;;; devops-bb-test.el --- Tests for devops.bb's Emacs side  -*- lexical-binding: t; -*-

;; Run with `bb test-emacs'.

(require 'ert)
(require 'cl-lib)
(require 'org)
(require 'ob-shell)
(require 'tramp)
(require 'devops-bb)

(devops-mode 1)
(devops-bb-log-mode 1)

;;; Helpers

(defmacro devops-bb-test--with-org (text &rest body)
  "Run BODY in a temp `org-mode' buffer containing TEXT, point at end."
  (declare (indent 1))
  `(with-temp-buffer
     (org-mode)
     (insert ,text)
     (goto-char (point-max))
     ,@body))

(defmacro devops-bb-test--with-local-target (dir-var &rest body)
  "Create a fresh local target directory bound to DIR-VAR for BODY.
DIR-VAR is bound to an absolute path ending in \"/\".  Used as a #+TARGET
value so blocks run and tangle on the local filesystem, not a remote.
The directory is removed afterwards."
  (declare (indent 1))
  `(let ((,dir-var (file-name-as-directory
                    (make-temp-file "devops-target-" t))))
     (unwind-protect
         (progn ,@body)
       (delete-directory ,dir-var t))))

(defmacro devops-bb-test--with-session-org (header &rest body)
  "Run BODY on a sh block carrying HEADER, under a heading tagged `:local:'."
  (declare (indent 1))
  `(devops-bb-test--with-org
       (concat "#+TARGET: /srv/app/ (local)\n\n"
               "* Run\t\t:local:\n\n"
               "#+begin_src sh " ,header "\npwd\n#+end_src\n")
     (goto-char (point-min))
     (re-search-forward "begin_src")
     ,@body))

(defun devops-bb-test--executor-params (lang)
  "Execute the src block at point with `org-babel-execute:LANG' stubbed out.
Return the header arguments the executor was handed, without starting
a shell."
  (let ((fn (intern (concat "org-babel-execute:" lang)))
        (seen nil))
    (cl-letf (((symbol-function fn)
               (lambda (_body params) (setq seen params) "")))
      (let ((org-confirm-babel-evaluate nil))
        (org-babel-execute-src-block)))
    seen))

(defconst devops-bb-test--run-id "11111111-2222-3333-4444-555555555555"
  "An async run ID for a fake session.")

(defun devops-bb-test--sent (id)
  "Return what ob-shell sends a session for the run ID of `echo hi'."
  (format (concat "echo 'ob_comint_async_shell_start_%s'\n"
                  "echo hi\n"
                  "echo 'ob_comint_async_shell_end_%s'\n")
          id id))

(defmacro devops-bb-test--with-sessions (sessions &rest body)
  "Run BODY with fake session buffers, removed afterwards.
SESSIONS is a list of (NAME TEXT): a buffer NAME holding TEXT, with a
live process the way a session has one.  TEXT nil makes the buffer
without a process."
  (declare (indent 1))
  `(let ((bufs nil))
     (unwind-protect
         (progn
           (pcase-dolist (`(,name ,text) ,sessions)
             (let ((buf (get-buffer-create name)))
               (push buf bufs)
               (when text
                 (with-current-buffer buf
                   (insert text)
                   (set-process-query-on-exit-flag
                    (start-process "devops-bb-test" buf "cat") nil)))))
           ,@body)
       (let ((kill-buffer-query-functions nil))
         (dolist (buf bufs)
           (when (buffer-live-p buf) (kill-buffer buf)))))))

;;; Scripting

(defconst devops-bb-test--prompt "\U000121b8\ufeff "
  "The prompt ob-shell sets in its sessions, `org-babel-sh-prompt'.")

(defun devops-bb-test--run-text (id input output done)
  "A session transcript of one async run of ID, as ob-shell leaves it."
  (let ((p devops-bb-test--prompt))
    (concat p "echo 'ob_comint_async_shell_start_" id "'\n"
            input "\n"
            "echo 'ob_comint_async_shell_end_" id "'\n"
            "ob_comint_async_shell_start_" id "\n"
            output
            (when done (concat p "ob_comint_async_shell_end_" id "\n" p)))))

(defconst devops-bb-test--run-a "089d9332-bcff-4ae3-9916-d891ec394804")
(defconst devops-bb-test--run-b "11111111-2222-3333-4444-555555555555")

(defmacro devops-bb-test--with-transcript (name text &rest body)
  "Run BODY with a buffer NAME holding the session transcript TEXT.
The buffer looks like an ob-shell async session -- its prompt regexp and
the buffer-local marker regexp -- without a shell behind it."
  (declare (indent 2))
  `(let ((buf (get-buffer-create ,name)))
     (unwind-protect
         (progn
           (with-current-buffer buf
             (insert ,text)
             (setq-local comint-prompt-regexp
                         (concat "^" (regexp-quote devops-bb-test--prompt) " *"))
             (setq-local org-babel-comint-async-indicator
                         "ob_comint_async_shell_\\(start\\|end\\)_\\(.+\\)"))
           ,@body)
       (let ((kill-buffer-query-functions nil))
         (kill-buffer buf)))))

(defun devops-bb-test--transcript (&optional b-done)
  "Two runs: A, done, printing to stdout and stderr; B, done when B-DONE."
  (let ((p devops-bb-test--prompt))
    (concat "sh-3.2$ PROMPT_COMMAND=;PS1=\"x\";PS2=\n"
            (devops-bb-test--run-text devops-bb-test--run-a "echo one\nls /nope"
                                   (concat p "one\n" p "ls: /nope: No such file\n")
                                   t)
            (devops-bb-test--run-text devops-bb-test--run-b "echo two"
                                   (concat p "two\n") b-done))))

(ert-deftest devops-bb-scripting--runs-test ()
  "Each run's input and output are cut at its markers, prompts removed."
  (devops-bb-test--with-transcript "devops-test-session" (devops-bb-test--transcript)
    (let ((runs (devops-bb-scripting--runs (get-buffer "devops-test-session"))))
      (should (equal (mapcar (lambda (r) (alist-get :id r)) runs)
                     (list devops-bb-test--run-a devops-bb-test--run-b)))
      (should (equal (alist-get :input (car runs)) "echo one\nls /nope\n"))
      (should (equal (alist-get :output (car runs))
                     "one\nls: /nope: No such file"))
      (should (alist-get :done (car runs)))
      (should (equal (alist-get :output (cadr runs)) "two"))
      (should-not (alist-get :done (cadr runs))))))

(ert-deftest devops-bb-scripting--block-run-test ()
  "A run is found by the ID in #+RESULTS, else by the body it sent."
  (devops-bb-test--with-transcript "devops-test-session" (devops-bb-test--transcript)
    (let ((runs (devops-bb-scripting--runs (get-buffer "devops-test-session")))
          (id (lambda (run) (alist-get :id run))))
      (should (equal (funcall id (devops-bb-scripting--block-run
                                  runs devops-bb-test--run-a "anything"))
                     devops-bb-test--run-a))
      (should (equal (funcall id (devops-bb-scripting--block-run
                                  runs "one" "echo one\nls /nope"))
                     devops-bb-test--run-a))
      (should (equal (funcall id (devops-bb-scripting--block-run
                                  runs nil "<<creds()>>\necho two"))
                     devops-bb-test--run-b))
      (should-not (devops-bb-scripting--block-run runs nil "echo three")))))

(ert-deftest devops-bb-scripting--block-run-output-uuid-test ()
  "A UUID inside a finished block's output is output, not a run's ID."
  (devops-bb-test--with-transcript "devops-test-session" (devops-bb-test--transcript)
    (let ((runs (devops-bb-scripting--runs (get-buffer "devops-test-session")))
          (printed (format "ran %s earlier" devops-bb-test--run-a)))
      (should (equal (alist-get :id (devops-bb-scripting--block-run
                                     runs printed "echo two"))
                     devops-bb-test--run-b))
      (should (equal (alist-get :id (devops-bb-scripting--block-run
                                     runs (concat devops-bb-test--run-a "\n")
                                     "echo two"))
                     devops-bb-test--run-a)))))

(ert-deftest devops-bb-scripting-block-output-test ()
  "The output of the block at a line, from the heading's session."
  (devops-bb-test--with-transcript "devops:/srv/app/" (devops-bb-test--transcript)
    (devops-bb-test--with-org
        (concat "#+TARGET: /srv/app/ (local)\n\n"
                "* Run\t\t:local:\n\n"
                "#+begin_src sh\necho one\nls /nope\n#+end_src\n\n"
                "#+RESULTS:\n: one\n\n"
                "#+begin_src sh\necho two\n#+end_src\n\n"
                "#+begin_src sh\necho three\n#+end_src\n")
      (let ((out (devops-bb-scripting-block-output (current-buffer) 6)))
        (should (equal (alist-get :session out) "devops:/srv/app/"))
        (should (eq (alist-get :status out) :done))
        (should (equal (alist-get :id out) devops-bb-test--run-a))
        (should (eq (alist-get :source out) :session))
        (should (equal (alist-get :output out) "one\nls: /nope: No such file"))
        (should (equal (alist-get :result out) "one")))
      (should (eq (alist-get :status (devops-bb-scripting-block-output (current-buffer) 13))
                  :running))
      (should (eq (alist-get :status (devops-bb-scripting-block-output (current-buffer) 17))
                  :not-found))
      (should-error (devops-bb-scripting-block-output (current-buffer) 3) :type 'user-error))))

(ert-deftest devops-bb-scripting-block-output-no-session-test ()
  "Without a session buffer or a result there is no output."
  (devops-bb-test--with-session-org ""
    (let ((out (devops-bb-scripting-block-output (current-buffer) 6)))
      (should (eq (alist-get :status out) :no-session))
      (should-not (alist-get :source out))
      (should-not (alist-get :output out)))))

(defmacro devops-bb-test--with-result-org (result &rest body)
  "Run BODY on a `:local:' sh block whose #+RESULTS is RESULT.
The block's body is on line 6."
  (declare (indent 1))
  `(devops-bb-test--with-org
       (concat "#+TARGET: /srv/app/ (local)\n\n"
               "* Run\t\t:local:\n\n"
               "#+begin_src sh\necho hi\n#+end_src\n\n"
               "#+RESULTS:\n" ,result)
     ,@body))

(ert-deftest devops-bb-scripting-block-output-results-fallback-test ()
  "With no session, #+RESULTS is the output, without its colons."
  (devops-bb-test--with-result-org ": hi\n: there\n"
    (let ((out (devops-bb-scripting-block-output (current-buffer) 6)))
      (should (eq (alist-get :status out) :no-session))
      (should (eq (alist-get :source out) :results))
      (should (equal (alist-get :output out) "hi\nthere")))))

(ert-deftest devops-bb-scripting-block-output-results-not-found-test ()
  "A session that never ran the block falls back to #+RESULTS too."
  (devops-bb-test--with-transcript "devops:/srv/app/" (devops-bb-test--transcript)
    (devops-bb-test--with-result-org ": hi\n"
      (let ((out (devops-bb-scripting-block-output (current-buffer) 6)))
        (should (eq (alist-get :status out) :not-found))
        (should (eq (alist-get :source out) :results))
        (should (equal (alist-get :output out) "hi"))))))

(ert-deftest devops-bb-scripting-block-output-results-table-test ()
  "A table result reads as its rows, not as a list."
  (devops-bb-test--with-result-org "| a | 1 |\n| b | 2 |\n"
    (should (equal (alist-get :output (devops-bb-scripting-block-output (current-buffer) 6))
                   "| a | 1 |\n| b | 2 |"))))

(ert-deftest devops-bb-scripting-block-output-results-drawer-test ()
  "A :results drawer reads as its contents."
  (devops-bb-test--with-result-org ":RESULTS:\nhi\n:END:\n"
    (should (equal (alist-get :output (devops-bb-scripting-block-output (current-buffer) 6))
                   "hi"))))

(ert-deftest devops-bb-scripting-block-output-placeholder-test ()
  "A placeholder UUID left in #+RESULTS is not output."
  (devops-bb-test--with-result-org (concat ": " devops-bb-test--run-a "\n")
    (let ((out (devops-bb-scripting-block-output (current-buffer) 6)))
      (should-not (alist-get :source out))
      (should-not (alist-get :output out))
      (should (equal (alist-get :result out) devops-bb-test--run-a)))))

(ert-deftest devops-bb-scripting-block-output-pool-test ()
  "A block's run is found in whichever session of the pool ran it.
The session holding the ID in #+RESULTS wins over an earlier run of the
same block in another session."
  (devops-bb-test--with-transcript "devops:/srv/app/"
      (devops-bb-test--run-text devops-bb-test--run-a "echo two"
                             (concat devops-bb-test--prompt "two\n") t)
    (devops-bb-test--with-transcript "devops:/srv/app/<2>"
        (concat (devops-bb-test--run-text devops-bb-test--run-b "echo two" "" nil)
                (devops-bb-test--run-text "22222222-2222-3333-4444-555555555555"
                                       "echo three"
                                       (concat devops-bb-test--prompt "three\n") t))
      (devops-bb-test--with-org
          (concat "#+TARGET: /srv/app/ (local)\n\n"
                  "* Run\t\t:local:\n\n"
                  "#+begin_src sh\necho two\n#+end_src\n\n"
                  "#+RESULTS:\n: " devops-bb-test--run-b "\n\n"
                  "#+begin_src sh\necho three\n#+end_src\n")
        (let ((out (devops-bb-scripting-block-output (current-buffer) 6)))
          (should (equal (alist-get :session out) "devops:/srv/app/<2>"))
          (should (eq (alist-get :status out) :running))
          (should (equal (alist-get :id out) devops-bb-test--run-b)))
        (let ((out (devops-bb-scripting-block-output (current-buffer) 12)))
          (should (equal (alist-get :session out) "devops:/srv/app/<2>"))
          (should (eq (alist-get :status out) :done))
          (should (equal (alist-get :output out) "three")))))))

(ert-deftest devops-bb-scripting-block-output-two-targets-test ()
  "A heading with two targets is an error naming them, not a prompt."
  (devops-bb-test--with-org
      (concat "#+TARGET: /srv/a/ (a)\n#+TARGET: /srv/b/ (b)\n\n"
              "* Run\t\t:a:b:\n\n"
              "#+begin_src sh\npwd\n#+end_src\n")
    (cl-letf (((symbol-function 'completing-read)
               (lambda (&rest _) (error "Prompted"))))
      (should-error (devops-bb-scripting-block-output (current-buffer) 7) :type 'user-error)
      (should (equal (alist-get :session (devops-bb-scripting-block-output (current-buffer) 7 "b"))
                     "devops:/srv/b/")))))

(ert-deftest devops-bb-scripting-run-output-test ()
  "A run's output by its ID, whatever has become of its block."
  (devops-bb-test--with-transcript "devops:/srv/app/" (devops-bb-test--transcript)
    (let ((out (devops-bb-scripting-run-output "devops:/srv/app/"
                                            devops-bb-test--run-a)))
      (should (equal (alist-get :session out) "devops:/srv/app/"))
      (should (eq (alist-get :status out) :done))
      (should (equal (alist-get :id out) devops-bb-test--run-a))
      (should (string-match-p "echo one\nls /nope\\'" (alist-get :input out)))
      (should (equal (alist-get :output out) "one\nls: /nope: No such file")))
    (should (eq (alist-get :status (devops-bb-scripting-run-output
                                    "devops:/srv/app/" devops-bb-test--run-b))
                :running))
    (let ((out (devops-bb-scripting-run-output "devops:/srv/app/" "nope")))
      (should (eq (alist-get :status out) :not-found))
      (should-not (alist-get :output out))))
  (should (eq (alist-get :status (devops-bb-scripting-run-output
                                  "devops:gone" devops-bb-test--run-a))
              :no-session)))

(ert-deftest devops-bb-scripting--pending-prompt-test ()
  "A session ending partway through a line, not on its prompt, is waiting."
  (devops-bb-test--with-transcript "devops-test-session"
      (concat (devops-bb-test--transcript) "[sudo] password for app: ")
    (should (equal (devops-bb-scripting--pending-prompt
                    (get-buffer "devops-test-session"))
                   "[sudo] password for app:")))
  (devops-bb-test--with-transcript "devops-test-session" (devops-bb-test--transcript t)
    (should-not (devops-bb-scripting--pending-prompt
                 (get-buffer "devops-test-session")))))

(ert-deftest devops-bb-scripting-sessions-executes-test ()
  "A real async session is listed, idle once its block has finished."
  (let ((devops-enable-session-async t))
    (devops-bb-test--with-local-target target
      (let ((session (car (devops-target-sessions target))))
        (unwind-protect
            (devops-bb-test--with-org
                (format (concat "#+TARGET: %s (local)\n\n"
                                "* Run\t\t:local:\n\n"
                                "#+begin_src sh\necho hi\nls /nonexistent-devops\n#+end_src\n")
                        target)
              (goto-char (point-min))
              (re-search-forward "begin_src")
              (let* ((org-confirm-babel-evaluate nil)
                     (uuid (org-babel-execute-src-block))
                     (deadline (+ (float-time) 30)))
                (while (and (< (float-time) deadline)
                            (save-excursion
                              (goto-char (point-min))
                              (search-forward uuid nil t)))
                  (accept-process-output nil 0.2))
                (let ((out (devops-bb-scripting-block-output (current-buffer) 6)))
                  (should (eq (alist-get :status out) :done))
                  (should (equal (alist-get :id out) uuid))
                  (should (eq (alist-get :source out) :session))
                  (should (string-match-p "\\`hi\n.*nonexistent-devops"
                                          (alist-get :output out))))
                (let ((s (seq-find (lambda (s) (equal (alist-get :name s) session))
                                   (devops-bb-scripting-sessions))))
                  (should s)
                  (should (eq (alist-get :state s) :idle))
                  (should (equal (alist-get :id s) uuid)))))
          (when-let* ((buf (get-buffer session)))
            (let ((kill-buffer-query-functions nil))
              (kill-buffer buf))))))))

;;; Execution log

(defmacro devops-bb-test--with-execution-log (log-var &rest body)
  "Run BODY with `devops-bb-execution-log' set to LOG-VAR.
LOG-VAR is bound to a temp file name, under a directory that does not
exist yet.  The directory is removed after."
  (declare (indent 1))
  `(let* ((dir (make-temp-file "devops-log-" t))
          (,log-var (expand-file-name "sub/executions.jsonl" dir))
          (devops-bb-execution-log ,log-var))
     (unwind-protect
         (progn ,@body)
       (delete-directory dir t))))

(defun devops-bb-test--log-entries (file)
  "The entries of the execution log FILE, as alists, oldest first."
  (with-temp-buffer
    (insert-file-contents file)
    (mapcar (lambda (line) (json-parse-string line :object-type 'alist))
            (split-string (buffer-string) "\n" t))))

(ert-deftest devops-bb-log-sync-test ()
  "A synchronous run is logged once, with an error and no result.
The block's heading has no target, so `devops-bb-scripting-block-output'
fails, and the entry holds why."
  (devops-bb-test--with-execution-log log
    (devops-bb-test--with-org "* Run\n\n#+begin_src emacs-lisp\n(+ 1 2)\n#+end_src\n"
      (goto-char (point-min))
      (re-search-forward "begin_src")
      (let ((org-confirm-babel-evaluate nil))
        (org-babel-execute-src-block))
      (let ((entries (devops-bb-test--log-entries log)))
        (should (= 1 (length entries)))
        (should (equal (alist-get 'event (car entries)) "execute"))
        (should (equal (alist-get 'line (car entries)) 3))
        (should (string-match-p
                 "\\`[0-9-]+T[0-9:]+\\.[0-9]\\{3\\}[-+][0-9]\\{4\\}\\'"
                 (alist-get 'time (car entries))))
        (should-not (assq 'result (car entries)))
        (should (alist-get 'error (car entries)))))))

(defvar devops-bb-test--target-runs 0
  "How many times the dynamic target's block in a test has run.")

(ert-deftest devops-bb-log-dynamic-target-test ()
  "Logging a run under a dynamic target does not run its block again.
The entry names the session from the target the run resolved."
  (devops-bb-test--with-local-target target
    (devops-bb-test--with-execution-log log
      (devops-bb-test--with-org
          (format (concat "#+TARGET: <<where()>> (local)\n\n"
                          "* Locate\n\n"
                          "#+name: where\n#+begin_src emacs-lisp\n"
                          "(setq devops-bb-test--target-runs"
                          " (1+ devops-bb-test--target-runs))\n"
                          "%S\n#+end_src\n\n"
                          "* Run\t\t:local:\n\n"
                          "#+begin_src sh\npwd\n#+end_src\n")
                  target)
        (goto-char (point-max))
        (re-search-backward "begin_src")
        (let ((org-confirm-babel-evaluate nil)
              (devops-enable-session-async nil)
              (devops-bb-test--target-runs 0))
          (org-babel-execute-src-block)
          (should (= devops-bb-test--target-runs 1))
          (let ((entries (devops-bb-test--log-entries log)))
            (should (= 1 (length entries)))
            (should (equal (alist-get 'session (car entries))
                           (car (devops-target-sessions target))))))))))

(ert-deftest devops-bb-log-pool-session-test ()
  "A run is logged with the session of the pool it was sent to."
  (let ((devops-enable-session-async t))
    (devops-bb-test--with-execution-log log
      (devops-bb-test--with-sessions
          `(("devops:/srv/app/" ,(devops-bb-test--sent devops-bb-test--run-id)))
        (devops-bb-test--with-session-org ""
          (devops-bb-test--executor-params "sh")
          (should (equal (alist-get 'session
                                    (car (devops-bb-test--log-entries log)))
                         "devops:/srv/app/<2>")))))))

(ert-deftest devops-bb-log-off-test ()
  "With `devops-bb-execution-log' nil, a run writes no log."
  (let ((devops-bb-execution-log nil))
    (cl-letf (((symbol-function 'devops-bb-log-append-entry)
               (lambda (&rest _) (error "Logged with the log off"))))
      (devops-bb-test--with-org "#+begin_src emacs-lisp\n(+ 1 2)\n#+end_src\n"
        (goto-char (point-min))
        (let ((org-confirm-babel-evaluate nil))
          (should (equal (org-babel-execute-src-block) 3)))))))

(ert-deftest devops-bb-log-scripted-test ()
  "A run under `devops-with-sync' is not the user's, and is not logged."
  (devops-bb-test--with-execution-log log
    (devops-bb-test--with-org "#+begin_src emacs-lisp\n(+ 1 2)\n#+end_src\n"
      (goto-char (point-min))
      (let ((org-confirm-babel-evaluate nil))
        (devops-with-sync (org-babel-execute-src-block)))
      (should-not (file-exists-p log)))))

(ert-deftest devops-bb-log-async-test ()
  "An async run is logged when it starts and again when its result lands."
  (let ((devops-enable-session-async t))
    (devops-bb-test--with-local-target target
      (let ((session (car (devops-target-sessions target))))
        (unwind-protect
            (devops-bb-test--with-execution-log log
              (devops-bb-test--with-org
                  (format (concat "#+TARGET: %s (local)\n\n"
                                  "* Run\t\t:local:\n\n"
                                  "#+begin_src sh\necho hi\n#+end_src\n")
                          target)
                (goto-char (point-min))
                (re-search-forward "begin_src")
                (let* ((org-confirm-babel-evaluate nil)
                       (uuid (org-babel-execute-src-block))
                       (deadline (+ (float-time) 30)))
                  (while (and (< (float-time) deadline)
                              (< (length (devops-bb-test--log-entries log)) 2))
                    (accept-process-output nil 0.2))
                  (let ((entries (devops-bb-test--log-entries log)))
                    (should (equal (mapcar (lambda (e) (alist-get 'event e))
                                           entries)
                                   '("execute" "result")))
                    (should (equal (alist-get 'status (car entries)) "running"))
                    (should (equal (alist-get 'id (car entries)) uuid))
                    (should (equal (alist-get 'line (cadr entries)) 5))
                    (should (equal (alist-get 'status (cadr entries)) "done"))
                    (should-not (assq 'output (cadr entries)))))))
          (when-let* ((buf (get-buffer session)))
            (let ((kill-buffer-query-functions nil))
              (kill-buffer buf))))))))

(ert-deftest devops-bb-log-async-output-uuid-test ()
  "A result is logged with its own run's ID, whatever its output prints.
The second block prints the first run's ID, which is in the session."
  (let ((devops-enable-session-async t))
    (devops-bb-test--with-local-target target
      (let ((session (car (devops-target-sessions target))))
        (unwind-protect
            (devops-bb-test--with-execution-log log
              (devops-bb-test--with-org
                  (format (concat "#+TARGET: %s (local)\n\n"
                                  "* Run\t\t:local:\n\n"
                                  "#+begin_src sh\necho hi\n#+end_src\n")
                          target)
                (let ((org-confirm-babel-evaluate nil)
                      (wait (lambda (n)
                              (let ((deadline (+ (float-time) 30)))
                                (while (and (< (float-time) deadline)
                                            (< (length (devops-bb-test--log-entries log))
                                               n))
                                  (accept-process-output nil 0.2))))))
                  (goto-char (point-min))
                  (re-search-forward "begin_src")
                  (let ((first (org-babel-execute-src-block)))
                    (funcall wait 2)
                    (goto-char (point-max))
                    (insert (format "\n#+begin_src sh\necho %s\n#+end_src\n" first))
                    (re-search-backward "begin_src")
                    (let ((second (org-babel-execute-src-block)))
                      (funcall wait 4)
                      (let ((entries (devops-bb-test--log-entries log)))
                        (should (equal (mapcar (lambda (e) (alist-get 'id e))
                                               entries)
                                       (list first first second second)))))))))
          (when-let* ((buf (get-buffer session)))
            (let ((kill-buffer-query-functions nil))
              (kill-buffer buf))))))))

(ert-deftest devops-bb-log-tangle-test ()
  "Interactive `devops-tangle' logs the heading and what it tangled.
The blocks it runs for noweb are not logged."
  (devops-bb-test--with-local-target target
    (devops-bb-test--with-execution-log log
      (devops-bb-test--with-org
          (format (concat "#+TARGET: %s (local)\n\n"
                          "* Deploy\t\t:local:\n\n"
                          "#+name: greeting\n"
                          "#+begin_src emacs-lisp\n\"hi\"\n#+end_src\n\n"
                          "#+begin_src txt :tangle out.txt :noweb yes\n"
                          "<<greeting()>>\n#+end_src\n")
                  target)
        (org-back-to-heading)
        (let ((org-confirm-babel-evaluate nil))
          (devops-tangle))
        (let ((entries (devops-bb-test--log-entries log)))
          (should (= 1 (length entries)))
          (let ((entry (car entries)))
            (should (equal (alist-get 'event entry) "tangle"))
            (should (eq (alist-get 'all entry) :false))
            (should (equal (alist-get 'heading entry) "Deploy"))
            (should (equal (alist-get 'line entry) 3))
            (should (equal (alist-get 'targets entry)
                           `[((tag . "local") (target . ,target)
                              (files . 1))]))))))))

(ert-deftest devops-bb-log-tangle-error-test ()
  "A tangle that fails is logged with why, and still signals."
  (devops-bb-test--with-execution-log log
    (devops-bb-test--with-org "* Deploy\n"
      (org-back-to-heading)
      (should-error (devops-tangle) :type 'user-error)
      (let ((entry (car (devops-bb-test--log-entries log))))
        (should (equal (alist-get 'event entry) "tangle"))
        (should (string-match-p "No #\\+TARGET" (alist-get 'error entry)))))))

(ert-deftest devops-bb-log-tangle-scripted-test ()
  "A tangle a script asks for, by headline, is not logged."
  (devops-bb-test--with-local-target target
    (devops-bb-test--with-execution-log log
      (devops-bb-test--with-org
          (format (concat "#+TARGET: %s (local)\n\n"
                          "* Deploy\t\t:local:\n\n"
                          "#+begin_src txt :tangle out.txt\nhi\n#+end_src\n")
                  target)
        (devops-tangle-headline (current-buffer) "Deploy")
        (should-not (file-exists-p log))))))

(ert-deftest devops-bb-log-drift-test ()
  "Interactive `devops-drift' logs each file's status, without a diff."
  (devops-bb-test--with-local-target target
    (devops-bb-test--with-execution-log log
      (devops-bb-test--with-org
          (format (concat "#+TARGET: %s (local)\n\n"
                          "* Deploy\t\t:local:\n\n"
                          "#+begin_src txt :tangle foo.txt\nhello\n#+end_src\n")
                  target)
        (org-back-to-heading)
        (unwind-protect
            (devops-drift t)
          (when-let* ((report (get-buffer "*Drift Report*")))
            (kill-buffer report)))
        (let ((entry (car (devops-bb-test--log-entries log))))
          (should (equal (alist-get 'event entry) "drift"))
          (should (eq (alist-get 'all entry) t))
          (should-not (assq 'heading entry))
          (should (equal (alist-get 'files entry)
                         `[((status . "missing") (tag . "local")
                            (path . "foo.txt")
                            (remote . ,(concat target "foo.txt"))
                            (detail . :null))])))))))

(ert-deftest devops-bb-log-mode-test ()
  "The mode adds its hooks and advice on, and removes them off."
  (unwind-protect
      (progn
        (devops-bb-log-mode -1)
        (should-not (memq 'devops-bb-log--on-execute org-babel-after-execute-hook))
        (should-not (memq 'devops-bb-log--on-command devops-command-functions))
        (should-not (advice-member-p 'devops-bb-log--log-async-result
                                     'org-babel-insert-result))
        (devops-bb-log-mode 1)
        (should (memq 'devops-bb-log--on-execute org-babel-after-execute-hook))
        (should (memq 'devops-bb-log--on-command devops-command-functions))
        (should (advice-member-p 'devops-bb-log--log-async-result
                                 'org-babel-insert-result))
        (should (advice-member-p 'devops-bb-log--during-async-filter
                                 'org-babel-comint-async-filter)))
    (devops-bb-log-mode 1)))

(ert-deftest devops-bb-log-edits-test ()
  "An org file's first change, save, revert and kill with edits are logged."
  (devops-bb-test--with-execution-log log
    (let ((file (make-temp-file "devops-bb-edits-" nil ".org" "* A\n")))
      (unwind-protect
          (with-current-buffer (find-file-noselect file)
            (goto-char (point-max))
            (insert "b\n")
            (insert "c\n")
            (save-buffer)
            (revert-buffer t t)
            (insert "d\n")
            (let ((kill-buffer-query-functions nil))
              (kill-buffer))
            (let ((entries (devops-bb-test--log-entries log)))
              (should (equal (mapcar (lambda (e) (alist-get 'event e)) entries)
                             '("modify" "save" "revert" "modify" "kill")))
              (should (equal (alist-get 'file (car entries)) file))))
        (delete-file file)))))

(provide 'devops-bb-test)

;;; devops-bb-test.el ends here
