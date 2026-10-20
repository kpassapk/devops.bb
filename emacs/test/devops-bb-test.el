;;; devops-bb-test.el --- Tests for devops.bb's Emacs side  -*- lexical-binding: t; -*-

;; Run with `bb test-emacs'.

(require 'ert)
(require 'cl-lib)
(require 'org)
(require 'ob-shell)
(require 'tramp)
(require 'devops-bb)
(require 'devops-drift)

(defconst devops-bb-test--posframe-stub
  '(unless (require 'posframe nil t)
     (defun posframe-workable-p () nil)
     (defun posframe-show (&rest _) nil)
     (defun posframe-hide (&rest _) nil)
     (defun posframe-poshandler-frame-center (&rest _) '(0 . 0))
     (provide 'posframe))
  "Stand in for posframe, which ob-confirm-posframe requires, when it is
not on the load path.  A batch Emacs shows no posframe anyway: the
prompt goes to a side window.")

(eval devops-bb-test--posframe-stub t)
(require 'ob-confirm-posframe)

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

(ert-deftest devops-bb-scripting--run-test ()
  "One run by its ID: its own input and output, none of the other run's."
  (devops-bb-test--with-transcript "devops:/srv/app/" (devops-bb-test--transcript t)
    (let ((out (devops-bb-scripting--run "devops:/srv/app/" devops-bb-test--run-b)))
      (should (eq (alist-get :status out) :done))
      (should (equal (alist-get :id out) devops-bb-test--run-b))
      (should (equal (alist-get :input out) "echo two"))
      (should (equal (alist-get :output out) "two")))
    (should (equal (alist-get :output (devops-bb-scripting--run
                                       (get-buffer "devops:/srv/app/")
                                       devops-bb-test--run-a))
                   "one\nls: /nope: No such file"))
    (let ((out (devops-bb-scripting--run "devops:/srv/app/"
                                         "33333333-2222-3333-4444-555555555555")))
      (should (eq (alist-get :status out) :not-found))
      (should-not (alist-get :input out))
      (should-not (alist-get :output out))))
  (devops-bb-test--with-transcript "devops:/srv/app/" (devops-bb-test--transcript)
    (let ((out (devops-bb-scripting--run "devops:/srv/app/" devops-bb-test--run-b)))
      (should (eq (alist-get :status out) :running))
      (should (equal (alist-get :output out) "two")))))

(ert-deftest devops-bb-scripting--head-tail-test ()
  "A long output keeps its first and last N/2 bytes; a short one is whole."
  (let* ((text (mapconcat (lambda (i) (format "line %04d" i))
                          (number-sequence 1 2000) "\n"))
         (cut (devops-bb-scripting--head-tail text 1000)))
    (should (> (string-bytes text) 1000))
    (should (= (string-bytes cut) 1000))
    (should (string-prefix-p (substring text 0 500) cut))
    (should (string-suffix-p (substring text -500) cut))
    (should (string-prefix-p "line 0001\n" cut))
    (should (string-suffix-p "line 2000" cut))
    (should (equal (devops-bb-scripting--head-tail text (string-bytes text)) text)))
  ;; A cut never splits a character: three bytes each, so less than N.
  (let ((cut (devops-bb-scripting--head-tail (make-string 2000 ?€) 1000)))
    (should (<= (string-bytes cut) 1000))
    (should (equal cut (make-string (* 2 (/ 500 3)) ?€)))))

(defun devops-bb-test-bench (&optional runs lines)
  "Time one run by ID against the full scan, in a synthetic session.
RUNS runs (default 500) of LINES output lines each (default 20); the
ID asked for is the last run's, as the subscriber asks as a run ends.
Run in batch:

  emacs --batch -L ... --eval \"(require \\='devops-bb-test)\" \\
    -f devops-bb-test-bench"
  (let* ((runs (or runs 500))
         (lines (or lines 20))
         (ids (mapcar (lambda (i) (format "%08x-2222-3333-4444-555555555555" i))
                      (number-sequence 1 runs)))
         (output (mapconcat (lambda (i) (format "%sout %d\n" devops-bb-test--prompt i))
                            (number-sequence 1 lines) "")))
    (devops-bb-test--with-transcript "devops-bench-session"
        (mapconcat (lambda (id) (devops-bb-test--run-text id "echo hi" output t))
                   ids "")
      (let* ((id (car (last ids)))
             (old (lambda ()
                    (seq-find (lambda (run) (equal id (alist-get :id run)))
                              (devops-bb-scripting--runs
                               (get-buffer "devops-bench-session")))))
             (new (lambda () (devops-bb-scripting--run "devops-bench-session" id)))
             (n-old 3)
             (n-new 100)
             (ms (lambda (fn n) (/ (* 1000.0 (car (benchmark-call fn n))) n))))
        (cl-assert (equal (alist-get :output (funcall old))
                          (alist-get :output (funcall new))))
        (message "bench: %d runs, %d chars; full scan %.1f ms, by ID %.2f ms"
                 runs (buffer-size (get-buffer "devops-bench-session"))
                 (funcall ms old n-old) (funcall ms new n-new))))))

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

;;; Subscribers

(defmacro devops-bb-test--with-subscriber (entries-var buffer &rest body)
  "Run BODY with a subscriber for BUFFER collecting entries in ENTRIES-VAR.
BUFFER nil subscribes it for every buffer.  Oldest first."
  (declare (indent 2))
  `(let* ((,entries-var nil)
          (b ,buffer)
          (f (lambda (entry)
               (setq ,entries-var (append ,entries-var (list entry))))))
     (devops-bb-log-subscribe f b)
     (unwind-protect
         (progn ,@body)
       (devops-bb-log-unsubscribe f b))))

(defun devops-bb-test--run-block ()
  "Run the first src block in the current buffer, without asking."
  (goto-char (point-min))
  (re-search-forward "begin_src")
  (let ((org-confirm-babel-evaluate nil))
    (org-babel-execute-src-block)))

(ert-deftest devops-bb-log-subscriber-indirect-test ()
  "A run from an indirect buffer reaches its base buffer's subscribers.
Another buffer's do not get it."
  (let ((devops-bb-execution-log nil))
    (devops-bb-test--with-org "* Run\n\n#+begin_src emacs-lisp\n(+ 1 2)\n#+end_src\n"
      (let ((indirect (make-indirect-buffer (current-buffer)
                                            "devops-bb-indirect" t)))
        (unwind-protect
            (with-temp-buffer
              (devops-bb-test--with-subscriber other (current-buffer)
                (devops-bb-test--with-subscriber entries indirect
                  (with-current-buffer indirect
                    (should (equal (devops-bb-test--run-block) 3)))
                  (should (= 1 (length entries)))
                  (should (equal (gethash :event (car entries)) "execute"))
                  (should (equal (gethash :buffer (car entries))
                                 "devops-bb-indirect"))
                  (should (equal (gethash :line (car entries)) 3))
                  (should-not other))))
          (kill-buffer indirect))))))

(ert-deftest devops-bb-log-subscriber-error-test ()
  "A subscriber that signals is reported, and the others, the log and the
run go on."
  (devops-bb-test--with-execution-log log
    (devops-bb-test--with-org "#+begin_src emacs-lisp\n(+ 1 2)\n#+end_src\n"
      (let ((boom (lambda (_) (error "Boom"))))
        (devops-bb-log-subscribe boom)
        (unwind-protect
            (devops-bb-test--with-subscriber entries (current-buffer)
              (let ((inhibit-message t))
                (should (equal (devops-bb-test--run-block) 3)))
              (should (= 1 (length entries)))
              (should (= 1 (length (devops-bb-test--log-entries log))))
              (with-current-buffer "*Messages*"
                (goto-char (point-max))
                (should (search-backward "failed on execute: Boom" nil t))))
          (devops-bb-log-unsubscribe boom))))))

(ert-deftest devops-bb-log-subscriber-log-off-test ()
  "With `devops-bb-execution-log' nil, subscribers still get entries.
They still get none from a run that is not the user's."
  (let ((devops-bb-execution-log nil))
    (cl-letf (((symbol-function 'devops-bb-log-append-entry)
               (lambda (&rest _) (error "Logged with the log off"))))
      (devops-bb-test--with-org "#+begin_src emacs-lisp\n(+ 1 2)\n#+end_src\n"
        (devops-bb-test--with-subscriber entries nil
          (devops-bb-test--run-block)
          (devops-with-sync (devops-bb-test--run-block))
          (should (= 1 (length entries)))
          (should (equal (gethash :event (car entries)) "execute")))))))

(ert-deftest devops-bb-log-subscribe-test ()
  "Subscribing twice adds once; unsubscribing a buffer's last drops it."
  (with-temp-buffer
    (let ((f #'ignore)
          (b (current-buffer)))
      (devops-bb-log-subscribe f b)
      (devops-bb-log-subscribe f b)
      (should (equal (gethash b devops-bb-log--buffer-subscribers) (list f)))
      (devops-bb-log-unsubscribe f b)
      (should (eq (gethash b devops-bb-log--buffer-subscribers :none) :none)))))

;;; Sandbox

(defvar ob-confirm-posframe-mode)

(defconst devops-bb-test--repo
  (file-name-directory
   (directory-file-name
    (file-name-directory
     (directory-file-name
      (file-name-directory (or load-file-name buffer-file-name))))))
  "This checkout, holding bb.edn and bin/devops-bb.")

(defun devops-bb-test--fake-publish (&optional after)
  "A publisher that records under its --to DIR: its flags in DIR/args,
its stdin in DIR/stdin.  AFTER, sh, runs once stdin closes."
  (list "sh" "-c"
        (concat "mkdir -p \"$2\" && printf '%s\\n' \"$@\" > \"$2/args\""
                " && cat >> \"$2/stdin\"" (when after (concat "; " after)))
        "fake-publish"))

(defvar devops-bb-test--prompts nil
  "The prompts shown in a test, newest first: (NAME PREVIEW NOTICES PARAMS).
PARAMS is the header-argument line, in full: the preview cuts it.")

(defvar devops-bb-test--answers nil
  "The keys to answer the next prompts with, in order; then y.")

(defun devops-bb-test--answer (info &optional notices)
  "Answer the prompt for INFO, for `ob-confirm-posframe--read-answer'.
Record it, with the preview it shows and its NOTICES."
  (push (list (nth 4 info)
              (with-current-buffer ob-confirm-posframe--buffer
                (buffer-substring-no-properties (point-min) (point-max)))
              (mapcar #'substring-no-properties notices)
              (substring-no-properties (ob-confirm-posframe--param-line info)))
        devops-bb-test--prompts)
  (or (pop devops-bb-test--answers) ?y))

(defmacro devops-bb-test--with-sandbox (root-var &rest body)
  "Run BODY with ROOT-VAR a temp dir for workbooks and their feeds.
The fake publisher, no sinks yet, `ob-confirm-posframe-mode' on, its
prompts answered by `devops-bb-test--answer', and no execution log.
Afterwards every buffer is detached and every buffer visiting a file
under ROOT-VAR killed."
  (declare (indent 1))
  `(let* ((,root-var (file-name-as-directory
                      (file-truename (make-temp-file "devops-sandbox-" t))))
          (devops-bb-sandbox-sinks nil)
          (devops-bb-sandbox-publish-command (devops-bb-test--fake-publish))
          (devops-bb-execution-log nil)
          (org-confirm-babel-evaluate nil)
          (devops-bb-test--prompts nil)
          (devops-bb-test--answers nil)
          (posframe-was ob-confirm-posframe-mode))
     (ob-confirm-posframe-mode 1)
     (unwind-protect
         (cl-letf (((symbol-function 'ob-confirm-posframe--read-answer)
                    #'devops-bb-test--answer))
           ,@body)
       (unless posframe-was (ob-confirm-posframe-mode -1))
       (devops-bb-sandbox--on-kill-emacs)
       (let ((kill-buffer-query-functions nil))
         (dolist (buf (buffer-list))
           (when (and (buffer-live-p buf)
                      (buffer-file-name buf)
                      (string-prefix-p ,root-var (buffer-file-name buf)))
             (with-current-buffer buf (set-buffer-modified-p nil))
             (kill-buffer buf))))
       (delete-directory ,root-var t))))

(defun devops-bb-test--sink (root name &rest flags)
  "Add the sink NAME, its feed ROOT/feeds/NAME, with FLAGS; return NAME."
  (push (cons name (append (list :to (expand-file-name (concat "feeds/" name) root))
                           flags))
        devops-bb-sandbox-sinks)
  name)

(defun devops-bb-test--visit (root name text)
  "Visit ROOT/NAME/NAME.org, written with TEXT first."
  (let ((file (expand-file-name (format "%s/%s.org" name name) root)))
    (make-directory (file-name-directory file) t)
    (write-region text nil file nil 'silent)
    (find-file-noselect file)))

(defun devops-bb-test--wait (pred &optional secs)
  "Wait up to SECS seconds, default 20, for PRED to return non-nil."
  (let ((deadline (+ (float-time) (or secs 20))))
    (while (and (not (funcall pred)) (< (float-time) deadline))
      (accept-process-output nil 0.05))
    (funcall pred)))

(defun devops-bb-test--detach (buffer)
  "Detach BUFFER and wait for its publisher to exit."
  (let ((proc (gethash :process (devops-bb-sandbox-attachment buffer))))
    (with-current-buffer buffer (devops-bb-sandbox-detach))
    (should (devops-bb-test--wait (lambda () (not (process-live-p proc)))))))

(defun devops-bb-test--json-lines (file)
  "FILE's JSON lines, as alists; nil when it does not exist."
  (when (file-exists-p file)
    (with-temp-buffer
      (insert-file-contents file)
      (mapcar (lambda (line)
                (json-parse-string line :object-type 'alist
                                   :null-object nil :false-object :false))
              (split-string (buffer-string) "\n" t)))))

(defun devops-bb-test--stdin (root sink)
  "What the fake publisher of SINK got on stdin, as alists."
  (devops-bb-test--json-lines (expand-file-name (format "feeds/%s/stdin" sink) root)))

(defun devops-bb-test--events (lines &optional event)
  "The LINES whose event is EVENT, or all but the status lines."
  (seq-filter (lambda (l) (if event
                              (equal (alist-get 'event l) event)
                            (not (equal (alist-get 'event l) "status"))))
              lines))

(defconst devops-bb-test--elisp-block
  "* Run\n\n#+begin_src emacs-lisp\n(+ 1 2)\n#+end_src\n"
  "A workbook with one sync block, on line 3.")

(ert-deftest devops-bb-sandbox-attach-test ()
  "Attach starts the publisher with the sink's flags and sends a status
line, then the buffer's entries; a sync run carries its #+RESULTS.
Detach closes stdin and undoes the attach."
  (devops-bb-test--with-sandbox root
    (devops-bb-test--sink root "a" :map '(("/h/w/" . "/work")) :max-lines 10)
    (let ((buf (devops-bb-test--visit root "wb" devops-bb-test--elisp-block)))
      (with-current-buffer buf
        (devops-bb-sandbox-attach "a" "my-feed")
        (should (devops-bb-sandbox-attachment))
        (should backup-by-copying)
        (should (local-variable-p 'create-lockfiles))
        (should (string-match-p "sandbox: my-feed" (devops-bb-sandbox--mode-line)))
        (should (member devops-bb-sandbox--mode-line-entry mode-line-misc-info))
        (should (equal (devops-bb-test--run-block) 3))
        (revert-buffer t t)
        (should backup-by-copying)
        (devops-bb-test--detach buf)
        (should-not (devops-bb-sandbox-attachment))
        (should-not (local-variable-p 'backup-by-copying))
        (should-not (local-variable-p 'create-lockfiles))
        (should-not (devops-bb-sandbox--mode-line))
        (should-not (memq 'devops-bb-sandbox--on-kill-buffer kill-buffer-hook)))
      (should (equal (split-string (with-temp-buffer
                                     (insert-file-contents
                                      (expand-file-name "feeds/a/args" root))
                                     (buffer-string))
                                   "\n" t)
                     (list "--to" (expand-file-name "feeds/a" root)
                           "--map" "/h/w=/work" "--max-lines" "10"
                           "--name" "my-feed")))
      (let* ((lines (devops-bb-test--stdin root "a"))
             (status (car lines))
             (run (car (devops-bb-test--events lines "execute")))
             (out (alist-get 'output run)))
        (should (equal (alist-get 'event status) "status"))
        (should (equal (alist-get 'file status) (buffer-file-name buf)))
        (should (eq (alist-get 'devops-mode status) t))
        (should (eq (alist-get 'sync run) t))
        (should (equal (alist-get 'status run) "done"))
        (should (equal (alist-get 'line run) 3))
        (should (string-match-p (concat "\\`" devops-bb-scripting--uuid-regexp "\\'")
                                (alist-get 'id run)))
        (should (equal out `((session) (status . "done") (id . ,(alist-get 'id run))
                             (input . "(+ 1 2)") (output . "3"))))
        (should (equal (mapcar (lambda (l) (alist-get 'event l)) (cdr lines))
                       '("modify" "execute" "revert")))))))

(ert-deftest devops-bb-sandbox-attach-refuses-test ()
  "Attach refuses without ob-confirm-posframe-mode, a remote file, a sink
in use or not supported; asks before replacing; warns of other .org files."
  (devops-bb-test--with-sandbox root
    (devops-bb-test--sink root "a")
    (devops-bb-test--sink root "b")
    (devops-bb-test--sink root "c" :via "docker exec -i box")
    (let ((one (devops-bb-test--visit root "one" devops-bb-test--elisp-block))
          (two (devops-bb-test--visit root "two" devops-bb-test--elisp-block))
          (warnings nil))
      (with-current-buffer one
        (let ((ob-confirm-posframe-mode nil))
          (should-error (devops-bb-sandbox-attach "a" "one") :type 'user-error))
        (should-error (devops-bb-sandbox-attach "c" "one") :type 'user-error)
        (should-error (devops-bb-sandbox-attach "nope" "one") :type 'user-error)
        (should-not (devops-bb-sandbox-attachment))
        (devops-bb-sandbox-attach "a" "one"))
      (with-current-buffer two
        (should-error (devops-bb-sandbox-attach "a" "two") :type 'user-error)
        (write-region "" nil (expand-file-name "two/other.org" root) nil 'silent)
        (cl-letf (((symbol-function 'display-warning)
                   (lambda (_type msg &rest _) (push msg warnings))))
          (devops-bb-sandbox-attach "b" "two"))
        (should (string-match-p "other\\.org" (car warnings))))
      (with-current-buffer one
        (let ((proc (gethash :process (devops-bb-sandbox-attachment))))
          (cl-letf (((symbol-function 'y-or-n-p) (lambda (_) nil)))
            (should-error (devops-bb-sandbox-attach "a" "again") :type 'user-error))
          (should (eq proc (gethash :process (devops-bb-sandbox-attachment))))
          (cl-letf (((symbol-function 'y-or-n-p) (lambda (_) t)))
            (devops-bb-sandbox-attach "a" "again"))
          (should (devops-bb-test--wait (lambda () (not (process-live-p proc)))))
          (should (equal (gethash :feed (devops-bb-sandbox-attachment)) "again")))))
    (with-temp-buffer
      (org-mode)
      (setq buffer-file-name "/ssh:nohost:/tmp/wb.org")
      (should-error (devops-bb-sandbox-attach "a" "remote") :type 'user-error)
      (setq buffer-file-name nil))))

(ert-deftest devops-bb-sandbox-two-buffers-test ()
  "Two attached buffers give two feeds, and neither gets the other's."
  (devops-bb-test--with-sandbox root
    (devops-bb-test--sink root "a")
    (devops-bb-test--sink root "b")
    (let ((one (devops-bb-test--visit root "one" devops-bb-test--elisp-block))
          (two (devops-bb-test--visit root "two" devops-bb-test--elisp-block)))
      (with-current-buffer one (devops-bb-sandbox-attach "a" "one"))
      (with-current-buffer two (devops-bb-sandbox-attach "b" "two"))
      (dotimes (_ 2)
        (with-current-buffer one (devops-bb-test--run-block))
        (with-current-buffer two (devops-bb-test--run-block)))
      (devops-bb-test--detach one)
      (devops-bb-test--detach two)
      (pcase-dolist (`(,sink ,buf) `(("a" ,one) ("b" ,two)))
        (let ((lines (devops-bb-test--stdin root sink)))
          (should (= 2 (length (devops-bb-test--events lines "execute"))))
          (dolist (l lines)
            (should (equal (alist-get 'file l) (buffer-file-name buf)))))))))

(ert-deftest devops-bb-sandbox-kill-test ()
  "Killing the buffer sends its kill, then detaches: stdin closes."
  (devops-bb-test--with-sandbox root
    (devops-bb-test--sink root "a")
    (let ((buf (devops-bb-test--visit root "wb" devops-bb-test--elisp-block)))
      (with-current-buffer buf (devops-bb-sandbox-attach "a" "wb"))
      (let ((proc (gethash :process (devops-bb-sandbox-attachment buf))))
        (with-current-buffer buf
          (goto-char (point-max))
          (insert "edit\n")
          (let ((kill-buffer-query-functions nil))
            (kill-buffer)))
        (should (devops-bb-test--wait (lambda () (not (process-live-p proc)))))
        (should (zerop (hash-table-count devops-bb-sandbox--registry)))
        (should (equal (mapcar (lambda (l) (alist-get 'event l))
                               (devops-bb-test--stdin root "a"))
                       '("status" "modify" "kill")))))))

(ert-deftest devops-bb-sandbox-rename-test ()
  "A rename keeps the feed: a status line names the new file, and the
next event carries it."
  (devops-bb-test--with-sandbox root
    (devops-bb-test--sink root "a")
    (let* ((buf (devops-bb-test--visit root "wb" devops-bb-test--elisp-block))
           (old (buffer-file-name buf))
           (new (expand-file-name "wb/20261019T000000--renamed.org" root)))
      (with-current-buffer buf
        (devops-bb-sandbox-attach "a" "wb")
        (set-visited-file-name new)
        (should (equal (gethash :feed (devops-bb-sandbox-attachment)) "wb"))
        (should backup-by-copying)
        (devops-bb-test--run-block))
      (devops-bb-test--detach buf)
      (let ((lines (devops-bb-test--stdin root "a")))
        (should (equal (mapcar (lambda (l) (alist-get 'file l))
                               (devops-bb-test--events lines "status"))
                       (list old new)))
        (should (equal (alist-get 'file (car (devops-bb-test--events lines "execute")))
                       new))))))

(ert-deftest devops-bb-sandbox-indirect-test ()
  "A run from an indirect buffer reaches the base buffer's feed, with the
line in the file.  Killing the indirect buffer sends nothing, and the
base stays attached."
  (devops-bb-test--with-sandbox root
    (devops-bb-test--sink root "a")
    (let ((buf (devops-bb-test--visit
                root "wb" (concat "* Notes\n\ntext\n\n" devops-bb-test--elisp-block))))
      (with-current-buffer buf (devops-bb-sandbox-attach "a" "wb"))
      (let ((indirect (make-indirect-buffer buf "devops-bb-sandbox-indirect" t)))
        (with-current-buffer indirect
          (goto-char (point-min))
          (re-search-forward "^\\* Run")
          (org-narrow-to-subtree)
          (should (string-match-p "sandbox: wb" (devops-bb-sandbox--mode-line)))
          (should (equal (devops-bb-test--run-block) 3)))
        (should (with-current-buffer buf (buffer-modified-p)))
        (let ((kill-buffer-query-functions nil))
          (kill-buffer indirect)))
      (should (devops-bb-sandbox-attachment buf))
      (devops-bb-test--detach buf)
      (let* ((lines (devops-bb-test--stdin root "a"))
             (run (car (devops-bb-test--events lines "execute"))))
        (should (equal (alist-get 'buffer run) "devops-bb-sandbox-indirect"))
        (should (equal (alist-get 'file run) (buffer-file-name buf)))
        (should (equal (alist-get 'line run) 7))
        (should (equal (alist-get 'output (alist-get 'output run)) "3"))
        (should-not (devops-bb-test--events lines "kill"))))))

(ert-deftest devops-bb-sandbox-entries-test ()
  "An async run's result carries its output, its execute none; an entry
with an error carries none."
  (let ((devops-enable-session-async t))
    (devops-bb-test--with-local-target target
      (let ((session (car (devops-target-sessions target))))
        (unwind-protect
            (devops-bb-test--with-sandbox root
              (devops-bb-test--sink root "a")
              (let ((buf (devops-bb-test--visit
                          root "wb"
                          (format (concat "#+TARGET: %s (local)\n\n"
                                          "* Run\t\t:local:\n\n"
                                          "#+begin_src sh\necho hi\n#+end_src\n\n"
                                          "* Deploy\n")
                                  target))))
                (with-current-buffer buf
                  (devops-bb-sandbox-attach "a" "wb")
                  (let ((uuid (devops-bb-test--run-block)))
                    (should (devops-bb-test--wait
                             (lambda ()
                               (devops-bb-test--events
                                (devops-bb-test--stdin root "a") "result"))))
                    (goto-char (point-max))
                    (should-error (devops-tangle) :type 'user-error)
                    (devops-bb-test--detach buf)
                    (let* ((lines (devops-bb-test--stdin root "a"))
                           (execute (car (devops-bb-test--events lines "execute")))
                           (result (car (devops-bb-test--events lines "result")))
                           (tangle (car (devops-bb-test--events lines "tangle"))))
                      (should (equal (alist-get 'id execute) uuid))
                      (should (equal (alist-get 'status execute) "running"))
                      (should-not (assq 'sync execute))
                      (should-not (assq 'output execute))
                      (should (equal (alist-get 'id result) uuid))
                      (should (equal (alist-get 'output result)
                                     `((session . ,session) (status . "done")
                                       (id . ,uuid) (input . "echo hi")
                                       (output . "hi"))))
                      (should (alist-get 'error tangle))
                      (should-not (assq 'output tangle)))))))
          (when-let* ((b (get-buffer session)))
            (let ((kill-buffer-query-functions nil))
              (kill-buffer b))))))))

(ert-deftest devops-bb-sandbox-rough-cap-test ()
  "An output over the rough cap keeps its head and tail, and says how
big it was."
  (let* ((text (mapconcat (lambda (i) (format "line %04d" i))
                          (number-sequence 1 2000) "\n"))
         (cut (devops-bb-sandbox--rough-cap
               (cljbang-hash-map :id "x" :output text) 1000)))
    (should (= (string-bytes (gethash :output cut)) 1000))
    (should (eq (gethash :truncated cut) t))
    (should (= (gethash :lines cut) 2000))
    (should (= (gethash :bytes cut) (string-bytes text)))
    (should (equal (gethash :output (devops-bb-sandbox--rough-cap
                                     (cljbang-hash-map :output "short") 1000))
                   "short"))))

(ert-deftest devops-bb-sandbox-dead-test ()
  "A publisher that dies shows DEAD; attach offers to restart it."
  (devops-bb-test--with-sandbox root
    (devops-bb-test--sink root "a")
    (let ((buf (devops-bb-test--visit root "wb" devops-bb-test--elisp-block))
          (warnings nil))
      (with-current-buffer buf
        (cl-letf (((symbol-function 'display-warning)
                   (lambda (_type msg &rest _) (push msg warnings))))
          (let ((devops-bb-sandbox-publish-command '("sh" "-c" "exit 3")))
            (devops-bb-sandbox-attach "a" "wb"))
          (should (devops-bb-test--wait
                   (lambda () (gethash :dead (devops-bb-sandbox-attachment))))))
        (should (string-match-p "sandbox: DEAD" (devops-bb-sandbox--mode-line)))
        (should (string-match-p "feed wb died" (car warnings)))
        (devops-bb-test--run-block)
        (let ((args (cl-letf (((symbol-function 'y-or-n-p) (lambda (_) t)))
                      (devops-bb-sandbox--attach-args))))
          (should (equal args '("a" "wb")))
          (cl-letf (((symbol-function 'y-or-n-p)
                     (lambda (_) (error "Asked to replace a dead publisher"))))
            (apply #'devops-bb-sandbox-attach args)))
        (should-not (gethash :dead (devops-bb-sandbox-attachment)))
        (should (string-match-p "sandbox: wb" (devops-bb-sandbox--mode-line)))
        (devops-bb-test--run-block)
        (devops-bb-test--detach buf)
        (should (= 1 (length (devops-bb-test--events
                              (devops-bb-test--stdin root "a") "execute"))))))))

(ert-deftest devops-bb-sandbox-quit-test ()
  "Quitting Emacs closes each publisher's stdin and waits for it a while."
  (devops-bb-test--with-sandbox root
    (devops-bb-test--sink root "a")
    (let ((devops-bb-sandbox-publish-command
           (devops-bb-test--fake-publish "sleep 0.5; touch \"$2/flushed\""))
          (buf (devops-bb-test--visit root "wb" devops-bb-test--elisp-block)))
      (with-current-buffer buf (devops-bb-sandbox-attach "a" "wb"))
      (should (memq 'devops-bb-sandbox--on-kill-emacs kill-emacs-hook))
      (let ((start (float-time)))
        (devops-bb-sandbox--on-kill-emacs)
        (should (< (- (float-time) start) devops-bb-sandbox-quit-timeout)))
      (should (file-exists-p (expand-file-name "feeds/a/flushed" root)))
      (should (zerop (hash-table-count devops-bb-sandbox--registry))))))

(ert-deftest devops-bb-sandbox-publish-test ()
  "End to end, the real publisher: a child Emacs attaches, runs a block
and exits at once.  The publisher, under SIGHUP, outlives it and pushes
the run and `attached' false."
  (devops-bb-test--with-sandbox root
    (let* ((feed (expand-file-name "feed" root))
           (file (expand-file-name "wb/wb.org" root))
           (form
            `(progn
               (setq load-path ',load-path
                     native-comp-enable-subr-trampolines nil)
               ,devops-bb-test--posframe-stub
               (require 'devops-bb)
               (require 'ob-confirm-posframe)
               (ob-confirm-posframe-mode 1)
               (fset 'ob-confirm-posframe--read-answer (lambda (&rest _) ?y))
               (devops-mode 1)
               (devops-bb-log-mode 1)
               (setq devops-bb-sandbox-sinks '(("local" :to ,feed))
                     devops-bb-sandbox-publish-command
                     '(,(expand-file-name "bin/devops-bb" devops-bb-test--repo) "publish")
                     org-confirm-babel-evaluate nil)
               (with-current-buffer (find-file-noselect ,file)
                 (devops-bb-sandbox-attach "local" "wb")
                 (goto-char (point-min))
                 (re-search-forward "begin_src")
                 (org-babel-execute-src-block))
               ;; No waiting: only SIGHUP being ignored lets it flush.
               (remove-hook 'kill-emacs-hook 'devops-bb-sandbox--on-kill-emacs)
               (kill-emacs 0))))
      (make-directory (file-name-directory file) t)
      (write-region devops-bb-test--elisp-block nil file nil 'silent)
      (should (zerop (call-process (expand-file-name invocation-name invocation-directory)
                                   nil nil nil
                                   "--batch" "--eval" (prin1-to-string form))))
      (let ((status-file (expand-file-name "status.json" feed)))
        (should (devops-bb-test--wait
                 (lambda ()
                   (and (file-exists-p status-file)
                        (eq :false (alist-get 'attached
                                              (car (devops-bb-test--json-lines
                                                    status-file))))))
                 30))
        (let* ((status (car (devops-bb-test--json-lines status-file)))
               (events (devops-bb-test--json-lines (expand-file-name "events.jsonl" feed)))
               (run (car (devops-bb-test--events events "execute"))))
          (should (equal (alist-get 'feed status) "wb"))
          (should (equal (alist-get 'workbook status) file))
          (should (equal (mapcar (lambda (e) (alist-get 'event e)) events)
                         '("modify" "execute")))
          (should (equal (mapcar (lambda (e) (alist-get 'seq e)) events) '(1 2)))
          (should (equal (alist-get 'output
                                    (car (devops-bb-test--json-lines
                                          (expand-file-name
                                           (format "outputs/%s.json" (alist-get 'id run))
                                           feed))))
                         "3")))))))

;;; Review

(defvar devops-bb-test--pwned nil
  "Set by the elisp a test plants, which must never run.")

(defvar devops-bb-test-local nil
  "A local variable a test plants.")
(put 'devops-bb-test-local 'safe-local-variable #'integerp)

(defun devops-bb-test--claude-writes (file text)
  "Write TEXT to FILE behind Emacs's back, as Claude does in the sandbox.
Its time is a few seconds on, so auto-revert sees the change."
  (make-directory (file-name-directory file) t)
  (write-region text nil file nil 'silent)
  (set-file-times file (time-add (current-time) 5)))

(defun devops-bb-test--execute-at (regexp)
  "Run what is at the first match of REGEXP, as C-c C-c does.
Return a src block's value."
  (goto-char (point-min))
  (re-search-forward regexp)
  (goto-char (match-beginning 0))
  (if (org-element-type-p (org-element-context) 'src-block)
      (org-babel-execute-src-block)
    (org-babel-execute-maybe)))

(defun devops-bb-test--prompt-names ()
  "The names of the blocks prompted for, oldest first."
  (mapcar #'car (reverse devops-bb-test--prompts)))

(defun devops-bb-test--notices ()
  "The notices of the last prompt."
  (nth 2 (car devops-bb-test--prompts)))

(defmacro devops-bb-test--with-workbook (spec &rest body)
  "Run BODY in an attached workbook, in a sandbox: SPEC is (ROOT BUF TEXT).
ROOT is the sandbox's temp dir, BUF the buffer of ROOT/wb/wb.org, which
starts as TEXT and is attached to the sink \"a\"."
  (declare (indent 1))
  (let ((root (nth 0 spec)) (buf (nth 1 spec)) (text (nth 2 spec)))
    `(devops-bb-test--with-sandbox ,root
       (devops-bb-test--sink ,root "a")
       (let ((,buf (devops-bb-test--visit ,root "wb" ,text)))
         (ignore ,buf)
         (with-current-buffer ,buf
           (devops-bb-sandbox-attach "a" "wb")
           ,@body)))))

(defconst devops-bb-test--named-block
  "* Run\n\n#+name: sum\n#+begin_src emacs-lisp\n(+ 1 2)\n#+end_src\n"
  "A workbook with one named sync block.")

(ert-deftest devops-bb-review-confirm-test ()
  "An unchanged block the operator confirmed and ran runs without a
prompt.  A block Claude changed, or a file Claude wrote beside it,
prompts once, with the diff; `d' shows all of it."
  (devops-bb-test--with-workbook (root buf devops-bb-test--named-block)
    (let ((file (buffer-file-name)) seen)
      (should (equal (devops-bb-test--execute-at "begin_src") 3))
      (should (equal (devops-bb-test--prompt-names) '("sum")))
      (should (member "not run since attach" (devops-bb-test--notices)))
      (devops-bb-test--execute-at "begin_src")
      (should (= 1 (length devops-bb-test--prompts)))
      ;; Claude edits the block.
      (devops-bb-test--claude-writes
       file (replace-regexp-in-string "(\\+ 1 2)" "(+ 1 3)" devops-bb-test--named-block))
      (revert-buffer t t)
      (setq devops-bb-test--answers '(?d ?y))
      (cl-letf (((symbol-function 'recursive-edit)
                 (lambda () (setq seen (buffer-string)))))
        (should (equal (devops-bb-test--execute-at "begin_src") 4)))
      (should (= 3 (length devops-bb-test--prompts)))
      (should (member "changed since you ran it: +1/-1" (devops-bb-test--notices)))
      (should (string-match-p "^-(\\+ 1 2)$" seen))
      (should (string-match-p "^\\+(\\+ 1 3)$" seen))
      (devops-bb-test--execute-at "begin_src")
      (should (= 3 (length devops-bb-test--prompts)))
      ;; Claude writes a script beside it, and another in a subdir.
      (devops-bb-test--claude-writes (expand-file-name "analyze.py" default-directory)
                                     "print('one')\nprint('two')\n")
      (devops-bb-test--claude-writes (expand-file-name "lib/util.sh" default-directory)
                                     "echo hi\n")
      (setq devops-bb-test--answers '(?d ?y))
      (cl-letf (((symbol-function 'recursive-edit)
                 (lambda () (setq seen (buffer-string)))))
        (devops-bb-test--execute-at "begin_src"))
      (should (= 5 (length devops-bb-test--prompts)))
      (should (member (concat "files changed since you last ran a block: "
                              "analyze.py +2/-0 (new), lib/util.sh +1/-0 (new)")
                      (devops-bb-test--notices)))
      (should-not (seq-find (lambda (n) (string-prefix-p "changed since" n))
                            (devops-bb-test--notices)))
      (should (string-match-p "^\\+\\+\\+ b/analyze\\.py$" seen))
      (should (string-match-p "^\\+print('two')$" seen))
      (should (string-match-p "^\\+\\+\\+ b/lib/util\\.sh$" seen))
      (devops-bb-test--execute-at "begin_src")
      (should (= 5 (length devops-bb-test--prompts)))
      (devops-bb-test--claude-writes (expand-file-name "analyze.py" default-directory)
                                     "print('one')\nprint('three')\n")
      (devops-bb-test--execute-at "begin_src")
      (should (member "files changed since you last ran a block: analyze.py +1/-1"
                      (devops-bb-test--notices)))
      ;; A cancelled prompt is not a yes: it asks again.
      (devops-bb-test--claude-writes (expand-file-name "analyze.py" default-directory) "x\n")
      (setq devops-bb-test--answers '(?n))
      (devops-bb-test--execute-at "begin_src")
      (should (= 7 (length devops-bb-test--prompts)))
      (devops-bb-test--execute-at "begin_src")
      (should (= 8 (length devops-bb-test--prompts))))))

(ert-deftest devops-bb-review-failed-run-test ()
  "A run is recorded once it ran: one that failed after the yes asks again."
  (devops-bb-test--with-workbook
      (root buf "* Run\n\n#+name: boom\n#+begin_src emacs-lisp\n(error \"Boom\")\n#+end_src\n")
    (should-error (devops-bb-test--execute-at "begin_src"))
    (should-error (devops-bb-test--execute-at "begin_src"))
    (should (equal (devops-bb-test--prompt-names) '("boom" "boom")))))

(ert-deftest devops-bb-review-unattached-test ()
  "A buffer that is not attached keeps the user's
`org-confirm-babel-evaluate'; an attached one never calls it."
  (devops-bb-test--with-workbook (root buf devops-bb-test--named-block)
    (let* ((calls nil)
           (org-confirm-babel-evaluate (lambda (lang _body) (push lang calls) nil)))
      (devops-bb-test--execute-at "begin_src")
      (should-not calls)
      (should (= 1 (length devops-bb-test--prompts)))
      (devops-bb-test--with-org "#+begin_src emacs-lisp\n(+ 1 2)\n#+end_src\n"
        (should (equal (devops-bb-test--execute-at "begin_src") 3))
        (should calls)
        (should (equal (delete-dups calls) '("emacs-lisp")))
        (should (= 1 (length devops-bb-test--prompts)))))))

(defconst devops-bb-test--plants
  '(("own :var" "* Run\n\n#+begin_src emacs-lisp :var x=(setq devops-bb-test--pwned 1)\nx\n#+end_src\n"
     "begin_src" ":var x")
    ("#+PROPERTY" "#+PROPERTY: header-args :var x=(setq devops-bb-test--pwned 2)\n\n* Run\n\n#+begin_src emacs-lisp\nx\n#+end_src\n"
     "begin_src" ":var x")
    ("property drawer" "* Run\n:PROPERTIES:\n:header-args:emacs-lisp: :dir (setq devops-bb-test--pwned 3)\n:END:\n\n#+begin_src emacs-lisp\n1\n#+end_src\n"
     "begin_src" ":dir")
    ("#+HEADER" "* Run\n\n#+HEADER: :results (setq devops-bb-test--pwned 4)\n#+begin_src emacs-lisp\n1\n#+end_src\n"
     "begin_src" ":results")
    ("#+call: argument" "* Run\n\n#+name: blk\n#+begin_src emacs-lisp :var n=1\nn\n#+end_src\n\n#+call: blk(n=(setq devops-bb-test--pwned 5))\n"
     "^#\\+call" ":var n")
    ("#+call: header" "* Run\n\n#+name: blk\n#+begin_src emacs-lisp\n1\n#+end_src\n\n#+call: blk() :dir (setq devops-bb-test--pwned 6)\n"
     "^#\\+call" ":dir")
    ("inline src block" "* Run\n\nsrc_emacs-lisp[:var x=(setq devops-bb-test--pwned 7)]{x}\n"
     "src_" ":var x")
    ("inline call" "* Run\n\n#+name: blk\n#+begin_src emacs-lisp :var n=1\nn\n#+end_src\n\ncall_blk(n=(setq devops-bb-test--pwned 8))\n"
     "call_" ":var n")
    ("reference argument" "* Run\n\n#+name: blk\n#+begin_src emacs-lisp :var n=1\nn\n#+end_src\n\n#+begin_src emacs-lisp :var y=blk(n=(setq devops-bb-test--pwned 9))\ny\n#+end_src\n"
     "begin_src emacs-lisp :var y" ":var y")
    ("reference header" "* Run\n\n#+name: blk\n#+begin_src emacs-lisp\n1\n#+end_src\n\n#+begin_src emacs-lisp :var y=blk[:dir (setq devops-bb-test--pwned 10)]()\ny\n#+end_src\n"
     "begin_src emacs-lisp :var y" ":var y")
    ("named call" "* Run\n\n#+name: blk\n#+begin_src emacs-lisp :var n=1\nn\n#+end_src\n\n#+name: c\n#+call: blk(n=(setq devops-bb-test--pwned 11))\n\n#+begin_src emacs-lisp :var y=c\ny\n#+end_src\n"
     "begin_src emacs-lisp :var y" ":var n")
    ("noweb call" "* Run\n\n#+name: blk\n#+begin_src emacs-lisp :var n=1\nn\n#+end_src\n\n#+begin_src emacs-lisp :noweb yes\n<<blk(n=(setq devops-bb-test--pwned 12))>>\n#+end_src\n"
     "begin_src emacs-lisp :noweb" "reference"))
  "Elisp Claude can plant in a header: (WHAT TEXT AT REFUSAL).
Running what is at AT in a workbook of TEXT must be refused, naming
REFUSAL, and must never set `devops-bb-test--pwned'.")

(defun devops-bb-test--refuses-plants (&optional any-refusal)
  "Run each of `devops-bb-test--plants' attached; check it is refused.
With ANY-REFUSAL, the refusal need not name the header, and may come
after the block's own prompt, as for a noweb call, which runs once the
block is confirmed."
  (pcase-dolist (`(,what ,text ,at ,refusal) devops-bb-test--plants)
    (ert-info (what :prefix "plant: ")
      (devops-bb-test--with-workbook (root buf text)
        (setq devops-bb-test--pwned nil)
        (let ((err (should-error (devops-bb-test--execute-at at) :type 'user-error)))
          (should (string-match-p (concat "\\`elisp in header "
                                          (if any-refusal "" (regexp-quote refusal)))
                                  (cadr err)))
          (should-not devops-bb-test--pwned)
          (unless any-refusal
            (should-not devops-bb-test--prompts)))))))

(ert-deftest devops-bb-review-elisp-refused-test ()
  "Elisp in a header value, own or inherited, refuses the run before org
evaluates it, and before any prompt: the planted elisp never runs."
  (devops-bb-test--refuses-plants)
  ;; Unattached, org runs it: the plants are real.
  (pcase-dolist (`(,what ,text ,at ,_) devops-bb-test--plants)
    (ert-info (what :prefix "plant, unattached: ")
      (setq devops-bb-test--pwned nil)
      (let ((org-confirm-babel-evaluate nil))
        (devops-bb-test--with-org text
          (org-mode)                    ; For #+PROPERTY.
          (ignore-errors (devops-bb-test--execute-at at))
          (should devops-bb-test--pwned))))))

(ert-deftest devops-bb-review-read-guard-test ()
  "With the raw-header checks off, the guard on `org-babel-read' alone
still refuses every planted elisp."
  (cl-letf (((symbol-function 'devops-bb-review--check-params) #'ignore)
            ((symbol-function 'devops-bb-review--check-ref) #'ignore))
    (devops-bb-test--refuses-plants t)))

(ert-deftest devops-bb-review-post-this-test ()
  "*this* only names a variable: `:post' passes the result on."
  (devops-bb-test--with-workbook
      (root buf (concat "* Run\n\n#+name: twice\n#+begin_src emacs-lisp :var x=0\n(* 2 x)\n#+end_src\n\n"
                        "#+begin_src emacs-lisp :post twice(x=*this*)\n21\n#+end_src\n"))
    (should (equal (devops-bb-test--execute-at "begin_src emacs-lisp :post") 42))))

(ert-deftest devops-bb-review-dir-check-test ()
  "A dotfile, dot-dir or symlink at any depth suspends the buffer: runs
are refused with the reason, and the mode line says so.  Removing it
resumes the buffer.  Attach refuses such a dir."
  (devops-bb-test--with-workbook (root buf devops-bb-test--named-block)
    (let ((dir default-directory))
      ;; Edits make no lock file, which would be a dotfile symlink.
      (goto-char (point-max))
      (insert "\n")
      (should-not (directory-files dir nil "\\`\\.#"))
      (save-buffer)
      (pcase-dolist (`(,plant ,reason)
                     `((,(lambda () (make-directory (expand-file-name ".git" dir)))
                        "sandbox dir has .git/: remove it to continue")
                       (,(lambda () (devops-bb-test--claude-writes
                                     (expand-file-name "sub/deep/.envrc" dir) "x"))
                        "sandbox dir has sub/deep/.envrc: remove it to continue")
                       (,(lambda ()
                           (make-directory (expand-file-name "sub" dir) t)
                           (make-symbolic-link "/etc" (expand-file-name "sub/etc" dir)))
                        "sandbox dir has symlink sub/etc: remove it to continue")
                       (,(lambda () (make-symbolic-link "nowhere" (expand-file-name "dangling" dir)))
                        "sandbox dir has symlink dangling: remove it to continue")))
        (funcall plant)
        (revert-buffer t t)
        (should (equal (devops-bb-review-suspended) reason))
        (should (string-match-p "sandbox: SUSPENDED" (devops-bb-sandbox--mode-line)))
        (let ((err (should-error (devops-bb-test--execute-at "begin_src") :type 'user-error)))
          (should (equal (cadr err) reason)))
        (dolist (f (list ".git" "sub/deep/.envrc" "sub/etc" "dangling"))
          (let ((f (expand-file-name f dir)))
            (cond ((file-symlink-p f) (delete-file f))
                  ((file-directory-p f) (delete-directory f t))
                  ((file-exists-p f) (delete-file f)))))
        (should (equal (devops-bb-test--execute-at "begin_src") 3))
        (should-not (devops-bb-review-suspended))
        (should (string-match-p "sandbox: wb" (devops-bb-sandbox--mode-line))))
      (devops-bb-test--detach buf)
      (devops-bb-test--claude-writes (expand-file-name ".dir-locals.el" dir) "()")
      (let ((err (should-error (devops-bb-sandbox-attach "a" "wb") :type 'user-error)))
        (should (equal (cadr err) "sandbox dir has .dir-locals.el: remove it to continue"))))))

(ert-deftest devops-bb-review-file-test ()
  "`:file' and `:output-dir' must stay inside the workbook's dir."
  (let ((outside (make-temp-file "devops-outside-" t)))
    (unwind-protect
        (devops-bb-test--with-workbook
            (root buf (format (concat "* Run\n\n"
                                      "#+begin_src emacs-lisp :results file :file ../x.txt\n1\n#+end_src\n\n"
                                      "#+begin_src emacs-lisp :results file :file %s/x.txt\n2\n#+end_src\n\n"
                                      "#+begin_src emacs-lisp :results file :output-dir %s/made :file x.txt\n3\n#+end_src\n\n"
                                      "#+begin_src emacs-lisp :results file :output-dir out :file x.txt\n4\n#+end_src\n")
                              outside outside))
          (let ((err (should-error (devops-bb-test--execute-at "begin_src.*:file \\.\\./x")
                                   :type 'user-error)))
            (should (string-match-p "\\`:file \\.\\./x\\.txt is outside the sandbox dir" (cadr err))))
          (should-error (devops-bb-test--execute-at "begin_src.*:file /") :type 'user-error)
          (should-error (devops-bb-test--execute-at "begin_src.*:output-dir /") :type 'user-error)
          (should-not (file-exists-p (expand-file-name "made" outside)))
          (should-not (file-exists-p (expand-file-name "x.txt" outside)))
          (should-not (file-exists-p (expand-file-name "../x.txt" default-directory)))
          (should-not devops-bb-test--prompts)
          (devops-bb-test--execute-at "begin_src.*:output-dir out")
          (should (file-exists-p (expand-file-name "out/x.txt" default-directory))))
      (delete-directory outside t))))

(defconst devops-bb-test--local-variables
  (concat devops-bb-test--named-block
          "\n# Local Variables:\n# devops-bb-test-local: 1\n"
          "# eval: (setq devops-bb-test--pwned t)\n# End:\n")
  "A workbook Claude planted local variables in.")

(ert-deftest devops-bb-review-local-variables-test ()
  "Planted local variables, eval: among them, do nothing on M-x
revert-buffer or on auto-revert; a .dir-locals.el above is ignored."
  (let ((enable-local-variables :all)
        (enable-local-eval t))
    (setq devops-bb-test--pwned nil)
    (devops-bb-test--with-workbook (root buf devops-bb-test--named-block)
      (devops-bb-test--claude-writes (expand-file-name ".dir-locals.el" root)
                                     "((nil . ((devops-bb-test-local . 2))))")
      (devops-bb-test--claude-writes (buffer-file-name) devops-bb-test--local-variables)
      (revert-buffer t t)
      (should (string-match-p "eval: " (buffer-string)))
      (should-not devops-bb-test--pwned)
      (should-not (local-variable-p 'devops-bb-test-local))
      (devops-bb-test--claude-writes (buffer-file-name)
                                     (concat "#+TITLE: x\n" devops-bb-test--local-variables))
      (auto-revert-mode 1)
      (auto-revert-handler)
      (should (string-prefix-p "#+TITLE: x" (buffer-string)))
      (should-not devops-bb-test--pwned)
      (should-not (local-variable-p 'devops-bb-test-local))
      ;; Unattached, the same revert runs them: the plants are real.
      (devops-bb-test--detach buf)
      (revert-buffer t t)
      (should devops-bb-test--pwned)
      (should (equal devops-bb-test-local 1)))))

(ert-deftest devops-bb-review-attach-drops-local-variables-test ()
  "Local variables a buffer read before it was attached are dropped."
  (let ((enable-local-variables :all))
    (devops-bb-test--with-workbook (root buf devops-bb-test--local-variables)
      (should-not (local-variable-p 'devops-bb-test-local))
      (should backup-by-copying)
      (should (devops-bb-sandbox-attachment)))))

(ert-deftest devops-bb-review-vc-test ()
  "vc never runs git in an attached buffer's dir."
  (let* ((calls 0)
         (count (lambda (&rest _) (cl-incf calls))))
    (advice-add 'vc-git-registered :before count)
    (unwind-protect
        (devops-bb-test--with-sandbox root
          (devops-bb-test--sink root "a")
          (should (zerop (call-process "git" nil nil nil "init" "-q" root)))
          (let ((buf (devops-bb-test--visit root "wb" devops-bb-test--named-block)))
            (should (> calls 0))
            (with-current-buffer buf
              (devops-bb-sandbox-attach "a" "wb")
              (setq calls 0)
              (revert-buffer t t)
              (goto-char (point-max))
              (insert "\n")
              (save-buffer)
              (vc-refresh-state)
              (should (zerop calls))
              (should-not vc-mode))))
      (advice-remove 'vc-git-registered count))))

(ert-deftest devops-bb-review-keywords-revert-test ()
  "A revert that changes keyword lines restarts org, so #+PROPERTY holds;
one that does not, does not."
  (devops-bb-test--with-workbook
      (root buf (concat "#+PROPERTY: header-args :results value\n\n" devops-bb-test--named-block))
    (let ((restarts 0))
      (cl-letf* ((orig (symbol-function 'org-mode-restart))
                 ((symbol-function 'org-mode-restart)
                  (lambda () (cl-incf restarts) (funcall orig))))
        (devops-bb-test--claude-writes
         (buffer-file-name)
         (concat "#+PROPERTY: header-args :results value\n\n" devops-bb-test--named-block "\ntext\n"))
        (revert-buffer t t t)
        (should (= restarts 0))
        (devops-bb-test--claude-writes
         (buffer-file-name)
         (concat "#+PROPERTY: header-args :results output\n\n" devops-bb-test--named-block))
        (revert-buffer t t t)
        (should (= restarts 1))
        (should (equal (org-entry-get (point-max) "header-args" t) ":results output"))
        (should (devops-bb-sandbox-attachment))))))

(ert-deftest devops-bb-review-posframe-off-test ()
  "With ob-confirm-posframe-mode off after attach, runs are refused."
  (devops-bb-test--with-workbook (root buf devops-bb-test--named-block)
    (ob-confirm-posframe-mode -1)
    (unwind-protect
        (should-error (devops-bb-test--execute-at "begin_src") :type 'user-error)
      (ob-confirm-posframe-mode 1))))

(ert-deftest devops-bb-review-scheme-test ()
  "Attach refuses a scheme without :allow; a run on a scheme whose :allow
went after attach is refused; the prompt shows the scheme's environment."
  (let ((devops-target-schemes '(("aws" :env (("AWS_PROFILE" . "%s"))))))
    (devops-bb-test--with-sandbox root
      (devops-bb-test--sink root "a")
      (let ((buf (devops-bb-test--visit
                  root "wb"
                  (concat "#+TARGET: cloud /aws:dev:\n#+TARGET: other /aws:prod:\n\n"
                          "* Run\t\t:cloud:\n\n#+name: profile\n#+begin_src emacs-lisp\n(getenv \"AWS_PROFILE\")\n#+end_src\n\n"
                          "* Other\t\t:other:\n\n#+name: other\n#+begin_src emacs-lisp\n1\n#+end_src\n"))))
        (with-current-buffer buf
          (let ((err (should-error (devops-bb-sandbox-attach "a" "wb") :type 'user-error)))
            (should (equal (cadr err) "scheme aws has no :allow; a sandboxed workbook may only name allowed credentials")))
          (setq devops-target-schemes '(("aws" :env (("AWS_PROFILE" . "%s")) :allow ("dev"))))
          (devops-bb-sandbox-attach "a" "wb")
          (should (equal (devops-bb-test--execute-at "#\\+name: profile") "dev"))
          (should (member "credential: target /aws:dev: · AWS_PROFILE=dev" (devops-bb-test--notices)))
          (should (string-match-p "  target cloud /aws:dev: · AWS_PROFILE=dev\\'"
                                  (nth 3 (car devops-bb-test--prompts))))
          (let ((err (should-error (devops-bb-test--execute-at "#\\+name: other") :type 'user-error)))
            (should (string-match-p "aws:prod is not in :allow" (cadr err))))
          (setq devops-target-schemes '(("aws" :env (("AWS_PROFILE" . "%s")))))
          (let ((err (should-error (devops-bb-test--execute-at "#\\+name: profile") :type 'user-error)))
            (should (equal (cadr err) "scheme aws has no :allow; a sandboxed workbook may only name allowed credentials"))))))))

(defvar devops-bb-test--instance nil
  "Where a test's dynamic target says its instance is.")

(ert-deftest devops-bb-review-dynamic-target-test ()
  "A dynamic target's block prompts as block › target-block, telling
what it resolves.  A new instance makes the block under it prompt once,
with the target's change."
  (let ((a (file-name-as-directory (make-temp-file "devops-instance-" t)))
        (b (file-name-as-directory (make-temp-file "devops-instance-" t)))
        (devops-enable-session-async nil))
    (unwind-protect
        (devops-bb-test--with-workbook
            (root buf (concat "#+TARGET: local <<where()>>\n\n"
                              "* Locate\n\n#+name: where\n#+begin_src emacs-lisp\ndevops-bb-test--instance\n#+end_src\n\n"
                              "* Check\t\t:local:\n\n#+name: check-disk\n#+begin_src emacs-lisp\n(+ 1 1)\n#+end_src\n"))
          (setq devops-bb-test--instance a)
          (devops-bb-test--execute-at "#\\+name: check-disk")
          (should (equal (devops-bb-test--prompt-names) '("where" "check-disk")))
          (let ((where (car (last devops-bb-test--prompts))))
            (should (string-match-p "check-disk › where" (nth 1 where)))
            (should (member "resolves target local for check-disk" (nth 2 where))))
          (devops-bb-test--execute-at "#\\+name: check-disk")
          (should (= 2 (length devops-bb-test--prompts)))
          (setq devops-bb-test--instance b)
          (devops-bb-test--execute-at "#\\+name: check-disk")
          (should (equal (devops-bb-test--prompt-names) '("where" "check-disk" "check-disk")))
          (should (member (format "target changed: local %s → local %s" a b)
                          (devops-bb-test--notices)))
          (devops-bb-test--execute-at "#\\+name: check-disk")
          (should (= 3 (length devops-bb-test--prompts))))
      (delete-directory a t)
      (delete-directory b t))))

(provide 'devops-bb-test)

;;; devops-bb-test.el ends here
