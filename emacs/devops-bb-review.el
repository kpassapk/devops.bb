;;; devops-bb-review.el --- Review of each run in a sandboxed workbook -*- lexical-binding: t; -*-

;; Copyright (C) 2026 Kyle S Passarelli

;; Author: Kyle S Passarelli <kyle.passarelli@gmail.com>
;; Assisted-by: Claude:claude-opus-5-5
;; URL: https://github.com/kpassapk/devops.bb

;; This package is free software; you can redistribute it and/or modify
;; it under the terms of the GNU General Public License as published by
;; the Free Software Foundation; either version 3, or (at your option)
;; any later version.

;; This package is distributed in the hope that it will be useful,
;; but WITHOUT ANY WARRANTY; without even the implied warranty of
;; MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
;; GNU General Public License for more details.

;; You should have received a copy of the GNU General Public License
;; along with GNU Emacs.  If not, see <https://www.gnu.org/licenses/>.

;;; Commentary:
;;
;; A workbook attached to a sandbox (devops/bb/sandbox.clj) is written
;; by Claude and run by the operator.  This file makes the operator's
;; yes real: what runs, where, and what changed since the last yes is
;; shown, and nothing Claude wrote runs without it.
;;
;; - Every run is confirmed, unless unchanged.  A run's fingerprint is
;;   its language, body (noweb references expanded), processed header
;;   arguments, resolved target, session and scheme environment, and
;;   the path, size and sha256 of every file in the workbook's
;;   directory but the workbook.  One the operator confirmed and ran
;;   skips the prompt.
;; - Elisp in a header value is refused before org evaluates it: org
;;   evaluates header values while it gathers a block's arguments,
;;   before any prompt.
;; - The workbook's directory may hold no dotfile, dot-dir, symlink or
;;   special file, at any depth: one suspends the buffer.  `:file' and
;;   `:output-dir' must stay inside the directory.
;; - A scheme target must have an :allow.
;; - Local variables, dir-locals and vc are off in an attached buffer.
;; - A run saves the buffer as its result lands, unless the file changed
;;   on disk: Claude's write wins, and no save asks.
;;
;; The notices and the header line go to ob-confirm-posframe's prompt.
;; The state lives in the attachment, :review, which outlives a revert.
;;
;; Elisp rather than cljbang: it is advice, dynamic binding and regexps
;; over org's internals, which elisp says directly.

;;; Code:

(require 'cl-lib)
(require 'seq)
(require 'subr-x)
(require 'org)
(require 'org-element)
(require 'ob-core)
(require 'ob-ref)
(require 'ob-lob)

(declare-function devops-bb-sandbox-attachment "devops/bb/sandbox")
(declare-function ob-confirm-posframe-notice "ob-confirm-posframe")
(declare-function devops-target-scheme "devops")
(declare-function devops-target-env "devops")
(declare-function org-mode-restart "org")

(defvar devops--block-target)
(defvar devops-block-session)
(defvar devops-resolving-target)
(defvar devops-target-schemes)
(defvar ob-confirm-posframe-mode)
(defvar ob-confirm-posframe-notice-functions)
(defvar ob-confirm-posframe-header-functions)

(defvar devops-bb-review-diff-max-bytes (* 256 1024)
  "Files up to this size are kept whole, for the diffs a prompt shows.
A bigger file, or one with a NUL byte, is fingerprinted but not diffed.")

(cl-defstruct (devops-bb-review-state (:constructor devops-bb-review--make)
                                      (:copier nil))
  "The review state of one attachment, kept in its :review."
  (fingerprints (make-hash-table :test 'equal)
                :documentation "The fingerprints confirmed and run, a set.")
  (blocks (make-hash-table :test 'equal)
          :documentation "Block key to its last confirmed (:text :target :env).")
  (snapshot nil :documentation "The dir's files at the last confirmed run.")
  (suspended nil :documentation "Why the buffer is suspended, or nil.")
  (keywords nil :documentation "The keyword lines before a revert."))

;;; Runs

(defvar devops-bb-review--run nil
  "The attached base buffer a run in progress started from, or nil.
Bound around every block run, reference and argument evaluation that
starts in an attached buffer, so code it reaches in another buffer, a
file visited for a reference, is held to the same rules.")

(defvar devops-bb-review--confirmed nil
  "Non-nil once the innermost run in progress has been confirmed.
Bound to nil by each run and each reference: until the operator says
yes to a block, no elisp read from it is evaluated.")

(defvar devops-bb-review--pending nil
  "The review of the innermost run, a plist, once it is computed.
Recorded as confirmed by `org-babel-after-execute-hook'.")

(defvar devops-bb-review--names nil
  "The names of the blocks running, innermost first.")

(defun devops-bb-review--state (&optional buffer)
  "The review state of BUFFER's attachment, the current buffer's by default."
  (when (fboundp 'devops-bb-sandbox-attachment)
    (when-let* ((att (devops-bb-sandbox-attachment (or buffer (current-buffer)))))
      (gethash :review att))))

(defun devops-bb-review--run-buffer ()
  "The attached base buffer the code running now answers to, or nil."
  (or devops-bb-review--run
      (let ((base (or (buffer-base-buffer) (current-buffer))))
        (and (devops-bb-review--state base) base))))

(defmacro devops-bb-review--in-run (run &rest body)
  "Run BODY with `devops-bb-review--run' RUN, unconfirmed."
  (declare (indent 1))
  `(let ((devops-bb-review--run ,run)
         (devops-bb-review--confirmed nil))
     ,@body))

;;; Elisp in headers

(defun devops-bb-review--elisp-p (value)
  "Non-nil when VALUE, a header value, is one org evaluates as elisp.
As `org-babel-read' decides, leading blanks aside: it starts with
\(, \\=', \\=` or [.  \"*this*\" only names a variable, and passes."
  (and (stringp value)
       (let ((v (string-trim-left value)))
         (and (> (length v) 0)
              (memq (aref v 0) '(?\( ?' ?` ?\[))))))

(defun devops-bb-review--refuse-elisp (what)
  "Refuse the run: the header WHAT holds elisp."
  (user-error "elisp in header %s: a sandboxed workbook may not evaluate header values"
              what))

(defun devops-bb-review--check-ref (ref what)
  "Refuse when REF, the value of the header WHAT, holds elisp.
REF is a literal, elisp, or a reference that may pass header arguments
and arguments of its own, `name[:dir x](a=1)', which are checked too."
  (let ((ref (string-trim ref)))
    (cond
     ((devops-bb-review--elisp-p ref) (devops-bb-review--refuse-elisp what))
     ((string-prefix-p "\"" ref) nil)
     (t
      ;; As `org-babel-ref-resolve' takes it apart.
      (when (and (string-match "\\[\\([^[]*\\)\\]\\'" ref)
                 (let ((head (substring ref 0 (match-beginning 0))))
                   (= (cl-count ?\( head) (cl-count ?\) head))))
        (setq ref (substring ref 0 (match-beginning 0))))
      (when (string-match "\\`\\(.+?\\)\\(\\[\\(.*\\)\\]\\|\\(\\)\\)(\\(.*\\))\\'" ref)
        (let ((headers (match-string 3 ref))
              (args (match-string 5 ref)))
          (when (org-string-nw-p headers)
            (devops-bb-review--check-params
             (org-babel-parse-header-arguments headers t) what))
          (when (org-string-nw-p args)
            (dolist (arg (org-babel-ref-split-args args))
              (devops-bb-review--check-var arg what)))))))))

(defun devops-bb-review--check-var (value &optional what)
  "Refuse when VALUE, a `:var' assignment, holds elisp.
WHAT names the header in the refusal; by default the variable."
  (if (string-match "\\`\\(.+?\\)=" value)
      (devops-bb-review--check-ref
       (substring value (match-end 0))
       (or what (format ":var %s" (string-trim (match-string 1 value)))))
    (devops-bb-review--check-ref value (or what ":var"))))

(defun devops-bb-review--check-params (params &optional what)
  "Refuse when a value in PARAMS, raw header arguments, is elisp.
Values that are not strings come from the operator's own defaults, and
pass.  WHAT, when given, names the header in the refusal."
  (dolist (p params)
    (when (consp p)
      (let ((key (car p))
            (value (cdr p)))
        (cond
         ((not (stringp value)))
         ((eq key :var) (devops-bb-review--check-var value what))
         ((or (devops-bb-review--elisp-p value)
              ;; Merged, these are split into words and reordered.
              (and (memq key '(:results :exports))
                   (seq-some #'devops-bb-review--elisp-p (split-string value))))
          (devops-bb-review--refuse-elisp (or what (format "%s" key)))))))))

(defun devops-bb-review--check-sources (datum)
  "Refuse when a header argument org reads for DATUM holds elisp.
DATUM is a src block, inline or not, or a call, #+call: or call_.  Each
place org reads arguments from is checked on its own, as org parses
each before it merges them, and a value overridden later is evaluated
all the same: org's defaults, the header-args properties (#+PROPERTY,
drawers, inherited), the block's #+HEADER lines and its own line.  A
call's block is checked when org reads it."
  (let* ((type (org-element-type datum))
         (inline (memq type '(inline-src-block inline-babel-call))))
    (pcase type
      ((or 'src-block 'inline-src-block)
       (let* ((lang (org-element-property :language datum))
              (defaults (intern (concat "org-babel-default-header-args:" lang))))
         (devops-bb-review--check-params
          (if inline org-babel-default-inline-header-args org-babel-default-header-args))
         (when (boundp defaults)
           (devops-bb-review--check-params (symbol-value defaults)))
         (dolist (alist (org-with-point-at (org-element-begin datum)
                          (org-babel-params-from-properties lang t)))
           (devops-bb-review--check-params alist))
         (dolist (h (cons (org-element-property :parameters datum)
                          (org-element-property :header datum)))
           (devops-bb-review--check-params (org-babel-parse-header-arguments h t)))))
      ((or 'babel-call 'inline-babel-call)
       (let ((lang (car (org-babel-lob-get-info datum t)))
             (args (org-element-property :arguments datum)))
         (devops-bb-review--check-params org-babel-default-lob-header-args)
         (dolist (alist (org-with-point-at
                            (org-element-property (if inline :begin :post-affiliated) datum)
                          (org-babel-params-from-properties lang t)))
           (devops-bb-review--check-params alist))
         (devops-bb-review--check-params
          (org-babel-parse-header-arguments (org-element-property :inside-header datum) t))
         (when args
           (dolist (ref (org-babel-ref-split-args args))
             (devops-bb-review--check-var ref)))
         (devops-bb-review--check-params
          (org-babel-parse-header-arguments (org-element-property :end-header datum) t)))))))

(defun devops-bb-review--src-block-info (fn &optional no-eval datum)
  "Around `org-babel-get-src-block-info' (FN with NO-EVAL and DATUM).
Before org evaluates a block's arguments, own and inherited, in an
attached buffer, read them raw and refuse elisp."
  (if-let* (((not no-eval))
            (run (devops-bb-review--run-buffer)))
      (devops-bb-review--in-run run
        (devops-bb-review--check-sources (or datum (org-element-context)))
        (funcall fn no-eval datum))
    (funcall fn no-eval datum)))

(defun devops-bb-review--lob-info (fn &optional datum no-eval)
  "Around `org-babel-lob-get-info' (FN with DATUM and NO-EVAL).
A #+call: or call_ line, as `org-babel-get-src-block-info' is."
  (if-let* (((not no-eval))
            (run (devops-bb-review--run-buffer)))
      (devops-bb-review--in-run run
        (devops-bb-review--check-sources (or datum (org-element-context)))
        (funcall fn datum no-eval))
    (funcall fn datum no-eval)))

(defun devops-bb-review--ref-resolve (fn ref)
  "Around `org-babel-ref-resolve' (FN with REF).
A reference runs a block, or reads data, of its own: unconfirmed, and
refused when REF passes elisp."
  (if-let* ((run (devops-bb-review--run-buffer)))
      (devops-bb-review--in-run run
        (devops-bb-review--check-ref ref (format "reference %s" ref))
        (funcall fn ref))
    (funcall fn ref)))

(defun devops-bb-review--read (fn cell &optional inhibit-lisp-eval)
  "Around `org-babel-read' (FN with CELL and INHIBIT-LISP-EVAL).
The last line: in an attached buffer, before the operator confirmed
the block, elisp is never evaluated, however org got there."
  (if (and (not inhibit-lisp-eval)
           (not devops-bb-review--confirmed)
           (devops-bb-review--elisp-p cell)
           (devops-bb-review--run-buffer))
      (devops-bb-review--refuse-elisp
       (truncate-string-to-width (string-trim cell) 40 nil nil "…"))
    (funcall fn cell inhibit-lisp-eval)))

;;; The workbook's directory

(defun devops-bb-review--root (buffer)
  "BUFFER's workbook directory."
  (file-name-directory (buffer-file-name buffer)))

(defun devops-bb-review--file-entry (file rel attrs)
  "The entry of FILE, REL in the dir, with ATTRS: (REL SIZE SHA256 CONTENT).
CONTENT is the bytes, or nil when too big or binary."
  (with-temp-buffer
    (set-buffer-multibyte nil)
    (insert-file-contents-literally file)
    (let ((size (file-attribute-size attrs)))
      (list rel size
            (secure-hash 'sha256 (current-buffer))
            (and (<= size devops-bb-review-diff-max-bytes)
                 (not (progn (goto-char (point-min)) (search-forward "\0" nil t)))
                 (buffer-string))))))

(defun devops-bb-review--scan (root exclude)
  "Check and hash the directory ROOT, but its file EXCLUDE.
Return (VIOLATION . FILES): VIOLATION says what suspends the buffer, or
is nil; FILES are the entries of the regular files, by path.  Symlinks
are never followed: `file-attributes' describes the link."
  (let (files violation)
    (cl-labels
        ((walk (dir)
           (dolist (f (directory-files dir t directory-files-no-dot-files-regexp t))
             (unless violation
               (let* ((name (file-name-nondirectory f))
                      (rel (file-relative-name f root))
                      (attrs (file-attributes f))
                      (type (file-attribute-type attrs)))
                 (cond
                  ((null attrs))        ; Gone since the listing.
                  ((string-prefix-p "." name)
                   (setq violation
                         (format "sandbox dir has %s%s: remove it to continue"
                                 rel (if (eq type t) "/" ""))))
                  ((stringp type)
                   (setq violation
                         (format "sandbox dir has symlink %s: remove it to continue"
                                 rel)))
                  ((eq type t) (walk f))
                  ((not (eq ?- (aref (file-attribute-modes attrs) 0)))
                   (setq violation
                         (format "sandbox dir has special file %s: remove it to continue"
                                 rel)))
                  ((equal f exclude))
                  (t (push (devops-bb-review--file-entry f rel attrs) files))))))))
      (walk (directory-file-name root)))
    (cons violation (sort files (lambda (a b) (string< (car a) (car b)))))))

(defun devops-bb-review--check-dir (buffer)
  "Scan attached BUFFER's directory, mark it suspended or not, return the scan."
  (let* ((file (expand-file-name (buffer-file-name buffer)))
         (scan (devops-bb-review--scan (file-name-directory file) file))
         (state (devops-bb-review--state buffer)))
    (when (and state (not (equal (car scan) (devops-bb-review-state-suspended state))))
      (setf (devops-bb-review-state-suspended state) (car scan))
      (force-mode-line-update t))
    scan))

(defun devops-bb-review-suspended (&optional buffer)
  "Why attached BUFFER is suspended, as of its last check, or nil."
  (when-let* ((state (devops-bb-review--state buffer)))
    (devops-bb-review-state-suspended state)))

(defun devops-bb-review--inside-p (path dir root)
  "Non-nil when PATH, relative to DIR, names a file inside ROOT.
By name only: no absolute path, no .., no elisp."
  (and (stringp path)
       (not (devops-bb-review--elisp-p path))
       (not (file-name-absolute-p path))
       (not (member ".." (split-string path "/")))
       (let ((file-name-handler-alist nil))
         (string-prefix-p (file-name-as-directory (expand-file-name root))
                          (expand-file-name path (expand-file-name dir root))))))

(defun devops-bb-review--file-param (fn name params)
  "Around `org-babel-generate-file-param' (FN with NAME and PARAMS).
It makes the :output-dir, even for a block only looked at: in an
attached buffer, not one outside the workbook's directory.  The run is
refused at the confirm."
  (if-let* ((run (devops-bb-review--run-buffer))
            (out (assq :output-dir params))
            ((not (devops-bb-review--inside-p
                   (cdr out) default-directory (devops-bb-review--root run)))))
      params
    (funcall fn name params)))

;;; The fingerprint

(defun devops-bb-review--plain (x)
  "X with the text properties of its strings removed, deeply."
  (cond ((stringp x) (substring-no-properties x))
        ((consp x) (cons (devops-bb-review--plain (car x))
                         (devops-bb-review--plain (cdr x))))
        ((vectorp x) (vconcat (mapcar #'devops-bb-review--plain x)))
        (t x)))

(defun devops-bb-review--print (x)
  "X printed in full, for a hash."
  (let ((print-length nil) (print-level nil) (print-circle nil)
        (print-escape-newlines t))
    (prin1-to-string (devops-bb-review--plain x))))

(defun devops-bb-review--session (session)
  "SESSION, without the pool slot devops.el gives a busy target's run.
The slot is which shell was free, not where the block runs."
  (if (and (stringp session) (string-match "\\`\\(devops:.*\\)<[0-9]+>\\'" session))
      (match-string 1 session)
    session))

(defun devops-bb-review--dir (params)
  "The directory a block with PARAMS runs in, expanded."
  (let ((dir (cdr (assq :dir params)))
        (file-name-handler-alist nil))
    (if (stringp dir) (expand-file-name dir) default-directory)))

(defun devops-bb-review--params (params)
  "PARAMS, processed header arguments, as the fingerprint takes them:
:dir expanded, :session without its slot, sorted by key."
  (let ((params (mapcar (lambda (p)
                          (pcase (car-safe p)
                            (:dir (cons :dir (devops-bb-review--dir params)))
                            (:session (cons :session (devops-bb-review--session (cdr p))))
                            (_ p)))
                        params)))
    (sort params (lambda (a b) (string< (format "%s" (car-safe a))
                                        (format "%s" (car-safe b)))))))

(defun devops-bb-review--body (info)
  "INFO's body, the text that runs.
Noweb references are expanded, calls `<<name()>>' left as written.  A
call runs its block, which is reviewed on
its own, and must not run before the operator answers; one that passes
elisp is refused now, before the prompt."
  (if (not (org-babel-noweb-p (nth 2 info) :eval))
      (nth 1 info)
    (let (calls)
      (prog1
          (condition-case err
              (cl-letf (((symbol-function 'org-babel-ref-resolve)
                         (lambda (id) (push id calls) (org-babel-noweb-wrap id))))
                (org-babel-expand-noweb-references info))
            (error (format "%s\n[noweb not expanded: %s]"
                           (nth 1 info) (error-message-string err))))
        (dolist (id calls)
          (devops-bb-review--check-ref id (format "reference %s" id)))))))

(defun devops-bb-review--key (info)
  "A name for INFO's block that survives edits elsewhere in the file:
its #+name, or its heading's path and its place under the heading."
  (let ((file (buffer-file-name (or (buffer-base-buffer) (current-buffer)))))
    (concat
     (if (and devops-bb-review--run
              (not (equal file (buffer-file-name devops-bb-review--run))))
         (concat file "::")
       "")
     (or (nth 4 info)
         (if-let* ((pos (nth 5 info)))
             (org-with-wide-buffer
              (goto-char pos)
              (let* ((start (save-excursion
                              (if (org-before-first-heading-p)
                                  (point-min)
                                (org-back-to-heading t)
                                (point))))
                     (n (let ((case-fold-search t))
                          (how-many "^[ \t]*#\\+\\(begin_src\\|call:\\)\\|src_[^[:space:]{[]+[[{]"
                                    start (1+ pos)))))
                (format "%s #%d"
                        (string-join (ignore-errors (org-get-outline-path t)) "/")
                        n)))
           (format "%s block" (nth 0 info)))))))

(defun devops-bb-review--text (info body)
  "INFO's block as its diff shows it: its arguments, then BODY."
  (concat (format "#+begin_src %s\n" (nth 0 info))
          (mapconcat (lambda (p)
                       (format "%s %s" (car p)
                               (if (stringp (cdr p)) (cdr p) (prin1-to-string (cdr p)))))
                     (seq-remove (lambda (p)
                                   (memq (car-safe p)
                                         '(:result-params :result-type
                                           :colname-names :rowname-names)))
                                 (devops-bb-review--params (nth 2 info)))
                     "\n")
          "\n\n" body
          (if (string-suffix-p "\n" body) "" "\n")))

(defun devops-bb-review--scheme (target)
  "TARGET's (SCHEME ARG PLIST) when it is a scheme target, or nil."
  (and (stringp target) (fboundp 'devops-target-scheme)
       (devops-target-scheme target)))

(defun devops-bb-review--review (run info)
  "Review INFO's run, in attached buffer RUN.
Refuse it, or return the plist the prompt and the fingerprint take."
  (unless (bound-and-true-p ob-confirm-posframe-mode)
    (user-error "Turn on ob-confirm-posframe-mode: a sandboxed workbook's runs need its review"))
  (let* ((scan (devops-bb-review--check-dir run))
         (params (nth 2 info))
         (pair (bound-and-true-p devops--block-target))
         (target (cdr pair))
         (scheme (devops-bb-review--scheme target))
         (root (devops-bb-review--root run))
         (dir (devops-bb-review--dir params)))
    (when (car scan) (user-error "%s" (car scan)))
    (when (and scheme (not (plist-get (nth 2 scheme) :allow)))
      (user-error "scheme %s has no :allow; a sandboxed workbook may only name allowed credentials"
                  (car scheme)))
    (dolist (key '(:file :output-dir))
      (when-let* ((cell (assq key params))
                  ((cdr cell))
                  ((not (devops-bb-review--inside-p (cdr cell) dir root))))
        (user-error "%s %s is outside the sandbox dir: a sandboxed workbook writes only there"
                    key (cdr cell))))
    (let* ((env (and scheme (devops-target-env target)))
           (session (devops-bb-review--session
                     (or (bound-and-true-p devops-block-session)
                         (cdr (assq :session params)))))
           (body (devops-bb-review--body info))
           (files (cdr scan)))
      (list :info info
            :fingerprint (secure-hash
                          'sha256
                          (devops-bb-review--print
                           (list (nth 0 info) body
                                 (devops-bb-review--params params)
                                 pair session env
                                 (mapcar (lambda (e) (seq-take e 3)) files))))
            :key (devops-bb-review--key info)
            :text (devops-bb-review--text info body)
            :target pair
            :session session
            :env env
            :files files))))

;;; Confirming

(defun devops-bb-review--name (info)
  "The name of INFO's block, or of the block at point, or nil."
  (or (nth 4 info)
      (ignore-errors
        (and (derived-mode-p 'org-mode)
             (org-element-property :name (org-element-at-point))))))

(defun devops-bb-review--execute (fn &rest args)
  "Around `org-babel-execute-src-block' (FN with ARGS), outermost.
In an attached buffer: refuse a suspended one before anything runs,
refuse elisp in the PARAMS a caller passes, and track the run."
  (if-let* ((run (devops-bb-review--run-buffer)))
      (devops-bb-review--in-run run
        (let ((devops-bb-review--pending nil)
              (devops-bb-review--names (cons (devops-bb-review--name (nth 1 args))
                                             devops-bb-review--names)))
          (when-let* ((why (car (devops-bb-review--check-dir run))))
            (user-error "%s" why))
          (devops-bb-review--check-params (nth 2 args))
          (apply fn args)))
    (apply fn args)))

(defun devops-bb-review--check-confirm (fn info)
  "Around `org-babel-check-confirm-evaluate' (FN with INFO).
In an attached buffer, the fingerprint decides, never
`org-confirm-babel-evaluate': a function there would be called with
the body expanded, and expanding runs noweb calls.  :eval no and
:eval query still hold."
  (if-let* ((run (devops-bb-review--run-buffer)))
      (let ((answer (let ((org-confirm-babel-evaluate t)) (funcall fn info))))
        (if (not (eq answer 'query))
            answer
          (let ((review (devops-bb-review--review run info)))
            (setq devops-bb-review--pending review)
            (if (and (not (equal (cdr (assq :eval (nth 2 info))) "query"))
                     (gethash (plist-get review :fingerprint)
                              (devops-bb-review-state-fingerprints
                               (devops-bb-review--state run))))
                t
              'query))))
    (funcall fn info)))

(defun devops-bb-review--confirm (fn info)
  "Around `org-babel-confirm-evaluate' (FN with INFO): note the yes."
  (let ((yes (funcall fn info)))
    (when (and yes devops-bb-review--run)
      (setq devops-bb-review--confirmed t))
    yes))

(defun devops-bb-review--after-execute ()
  "Record the run that just ran as confirmed, for `org-babel-after-execute-hook'."
  (when-let* ((review devops-bb-review--pending)
              ((bound-and-true-p devops-bb-review--confirmed))
              (state (devops-bb-review--state devops-bb-review--run)))
    (puthash (plist-get review :fingerprint) t (devops-bb-review-state-fingerprints state))
    (puthash (plist-get review :key)
             (list :text (plist-get review :text)
                   :target (plist-get review :target)
                   :env (plist-get review :env))
             (devops-bb-review-state-blocks state))
    (setf (devops-bb-review-state-snapshot state) (plist-get review :files))
    (setq devops-bb-review--pending nil)))

;;; Notices

(defun devops-bb-review--diff (old new old-label new-label)
  "The unified diff of OLD and NEW, strings or nil for none.
OLD-LABEL and NEW-LABEL name them."
  (let ((a (make-temp-file "devops-bb-review-"))
        (b (make-temp-file "devops-bb-review-"))
        (coding-system-for-write 'no-conversion))
    (unwind-protect
        (progn
          (write-region (or old "") nil a nil 'silent)
          (write-region (or new "") nil b nil 'silent)
          (with-temp-buffer
            (let ((coding-system-for-read 'utf-8))
              (call-process "diff" nil t nil "-u"
                            "--label" old-label "--label" new-label a b))
            (buffer-string)))
      (delete-file a)
      (delete-file b))))

(defun devops-bb-review--counts (diff)
  "The added and removed lines of DIFF, as \"+N/-M\"."
  (let ((plus 0) (minus 0))
    (dolist (line (split-string diff "\n"))
      (cond ((string-prefix-p "+++ " line))
            ((string-prefix-p "--- " line))
            ((string-prefix-p "+" line) (cl-incf plus))
            ((string-prefix-p "-" line) (cl-incf minus))))
    (format "+%d/-%d" plus minus)))

(defun devops-bb-review--notice (line detail)
  "A notice LINE with DETAIL behind `d'."
  (if (fboundp 'ob-confirm-posframe-notice)
      (ob-confirm-posframe-notice line detail)
    line))

(defun devops-bb-review--block-notice (review state)
  "The notice for REVIEW's block, if it changed since it last ran.
STATE holds what it was."
  (let ((last (gethash (plist-get review :key) (devops-bb-review-state-blocks state)))
        (text (plist-get review :text)))
    (cond
     ((null last) "not run since attach")
     ((not (equal (plist-get last :text) text))
      (let ((diff (devops-bb-review--diff (plist-get last :text) text
                                          "a/block" "b/block")))
        (devops-bb-review--notice
         (format "changed since you ran it: %s" (devops-bb-review--counts diff))
         diff))))))

(defun devops-bb-review--files-notice (review state)
  "The notice for REVIEW's files changed since the last confirmed run.
STATE holds them as they were."
  (let ((old (devops-bb-review-state-snapshot state))
        (new (plist-get review :files))
        parts diffs)
    (dolist (rel (sort (delete-dups (mapcar #'car (append old new))) #'string<))
      (let ((a (assoc rel old))
            (b (assoc rel new)))
        (unless (and a b (equal (nth 2 a) (nth 2 b)))
          (if (or (and a (null (nth 3 a))) (and b (null (nth 3 b))))
              (progn
                (push (format "%s (binary or big)" rel) parts)
                (push (format "Binary files a/%s and b/%s differ\n" rel rel) diffs))
            (let ((diff (devops-bb-review--diff
                         (nth 3 a) (nth 3 b)
                         (if a (concat "a/" rel) "/dev/null")
                         (if b (concat "b/" rel) "/dev/null"))))
              (push (format "%s %s%s" rel (devops-bb-review--counts diff)
                            (cond ((null a) " (new)") ((null b) " (deleted)") (t "")))
                    parts)
              (push diff diffs))))))
    (when parts
      (devops-bb-review--notice
       (format "files changed since you last ran a block: %s"
               (string-join (nreverse parts) ", "))
       (apply #'concat (nreverse diffs))))))

(defun devops-bb-review--target-label (pair)
  "PAIR, a (TAG . TARGET), as a prompt shows it."
  (if pair (format "%s %s" (car pair) (cdr pair)) "none"))

(defun devops-bb-review--env-label (env)
  "ENV, a scheme target's environment, as a prompt shows it."
  (mapconcat (lambda (e)
               (if (cdr e) (format "%s=%s" (car e) (cdr e)) (format "unset %s" (car e))))
             env " · "))

(defun devops-bb-review--notices (info)
  "The notices of INFO's run, for `ob-confirm-posframe-notice-functions'."
  (when-let* ((review devops-bb-review--pending)
              ((eq (plist-get review :info) info))
              (state (devops-bb-review--state devops-bb-review--run)))
    (let ((last (gethash (plist-get review :key) (devops-bb-review-state-blocks state))))
      (delq nil
            (list
             (when-let* ((resolving (bound-and-true-p devops-resolving-target)))
               (format "resolves target %s for %s"
                       (car resolving)
                       (or (cadr devops-bb-review--names) "unnamed block")))
             (when (and last (not (equal (plist-get last :target)
                                         (plist-get review :target))))
               (format "target changed: %s → %s"
                       (devops-bb-review--target-label (plist-get last :target))
                       (devops-bb-review--target-label (plist-get review :target))))
             (when-let* ((env (plist-get review :env)))
               (format "credential: target %s · %s"
                       (cdr (plist-get review :target))
                       (devops-bb-review--env-label env)))
             (devops-bb-review--block-notice review state)
             (devops-bb-review--files-notice review state))))))

(defun devops-bb-review--header (info)
  "The target and environment of INFO's run.
For `ob-confirm-posframe-header-functions'."
  (when-let* ((review devops-bb-review--pending)
              ((eq (plist-get review :info) info))
              (pair (plist-get review :target)))
    (string-join (delq nil (list (format "target %s" (devops-bb-review--target-label pair))
                                 (when-let* ((env (plist-get review :env)))
                                   (devops-bb-review--env-label env))))
                 " · ")))

;;; Revert, local variables, vc

(defun devops-bb-review--hack-local-variables (fn &rest args)
  "Around `hack-local-variables' (FN with ARGS): none when attached.
None in an attached buffer, or in a file visited by its run.  `revert-buffer' reads them
again; Claude writes the file."
  (if (devops-bb-review--run-buffer)
      (let ((enable-local-variables nil)
            (enable-local-eval nil)
            (enable-dir-local-variables nil))
        (apply fn args))
    (apply fn args)))

(defun devops-bb-review--vc-refresh (fn &rest args)
  "Around `vc-refresh-state' (FN with ARGS): no vc in an attached buffer.
It would run git in a directory Claude writes."
  (if (devops-bb-review--state)
      (let ((vc-handled-backends nil))
        (apply fn args))
    (apply fn args)))

(defun devops-bb-review--keywords ()
  "The keyword lines of the current buffer, #+PROPERTY and the like.
Not a block's own, #+NAME or #+RESULTS, or a #+CALL."
  (org-with-wide-buffer
   (goto-char (point-min))
   (let ((case-fold-search t)
         (own (cons "CALL" org-element-affiliated-keywords))
         lines)
     (while (re-search-forward "^[ \t]*#\\+\\([[:alnum:]_]+\\)\\(?:\\[.*\\]\\)?:.*$" nil t)
       (unless (member (upcase (match-string 1)) own)
         (push (match-string-no-properties 0) lines)))
     (nreverse lines))))

(defun devops-bb-review--before-revert ()
  "Keep an attached buffer's keyword lines, for `before-revert-hook'."
  (when-let* ((state (and (not (buffer-base-buffer)) (devops-bb-review--state))))
    (setf (devops-bb-review-state-keywords state) (devops-bb-review--keywords))))

(defun devops-bb-review--after-revert ()
  "Check an attached buffer's dir again, and restart org if need be.
Org restarts when the keyword lines changed, for `after-revert-hook'.  Auto-revert keeps the
mode, and org's #+PROPERTY and #+STARTUP caches with it."
  (when-let* ((state (and (not (buffer-base-buffer)) (devops-bb-review--state))))
    (devops-bb-review--check-dir (current-buffer))
    (unless (equal (devops-bb-review-state-keywords state) (devops-bb-review--keywords))
      (when (derived-mode-p 'org-mode)
        (org-mode-restart)))
    (setf (devops-bb-review-state-keywords state) nil)))

;;; Saving results

(defcustom devops-bb-review-save-results t
  "Non-nil: save an attached workbook as each result is inserted.
Claude then sees the output and a clean buffer together, and need not
ask the operator to save.  A workbook that changed on disk since Emacs
read or wrote it is not saved: Claude's write wins, and the buffer stays
modified until the operator reverts it."
  :type 'boolean
  :group 'devops)

(defun devops-bb-review--save-result (fn &rest args)
  "Around `org-babel-insert-result' (FN with ARGS): save an attached buffer.
Never prompts, as this can run in a process filter.  Skips the save when
the file changed on disk, so that the file Claude wrote is not overwritten."
  (prog1 (apply fn args)
    (when devops-bb-review-save-results
      (let ((base (or (buffer-base-buffer) (current-buffer))))
        (when (and (devops-bb-review--state base)
                   (buffer-file-name base)
                   (buffer-modified-p base))
          (with-current-buffer base
            (if (not (verify-visited-file-modtime base))
                (message "devops-bb: %s changed on disk; not saved"
                         (file-name-nondirectory buffer-file-name))
              (condition-case err
                  (let ((inhibit-message t))
                    (save-buffer))
                (error (message "devops-bb: not saved: %s"
                                (error-message-string err)))))))))))

(defun devops-bb-review--private-dir (name)
  "A directory of Emacs's own, NAME, for an attached buffer's files."
  (let ((dir (file-name-as-directory
              (locate-user-emacs-file (concat "devops-bb-sandbox/" name)))))
    (unless (file-directory-p dir)
      (with-file-modes #o700 (make-directory dir t)))
    dir))

(defun devops-bb-review-setup-buffer ()
  "Keep Emacs's own files out of the current buffer's workbook directory.
A lock file is a dotfile symlink, which would suspend the buffer, and
backups and auto-saves would change the dir, and so every fingerprint."
  (setq-local create-lockfiles nil)
  (setq-local backup-directory-alist
              `(("." . ,(devops-bb-review--private-dir "backups"))))
  (when (and buffer-auto-save-file-name buffer-file-name
             (string-prefix-p (file-name-directory buffer-file-name)
                              buffer-auto-save-file-name))
    (setq buffer-auto-save-file-name
          (expand-file-name (concat (md5 buffer-file-name) ".autosave")
                            (devops-bb-review--private-dir "auto-save")))))

;;; Attach

(defun devops-bb-review-check-attach (buffer)
  "Signal a `user-error' when BUFFER's workbook may not be attached:
a scheme without :allow, or a directory that would suspend it."
  (dolist (entry (bound-and-true-p devops-target-schemes))
    (unless (plist-get (cdr entry) :allow)
      (user-error "scheme %s has no :allow; a sandboxed workbook may only name allowed credentials"
                  (car entry))))
  (let ((file (expand-file-name (buffer-file-name buffer))))
    (when-let* ((why (car (devops-bb-review--scan (file-name-directory file) file))))
      (user-error "%s" why))))

(defun devops-bb-review-create (buffer)
  "A new review state for BUFFER's attachment.
Its snapshot is the directory as it is now, so the first prompt shows
what changed since the attach."
  (let ((file (expand-file-name (buffer-file-name buffer))))
    (devops-bb-review--make
     :snapshot (cdr (devops-bb-review--scan (file-name-directory file) file)))))

(defun devops-bb-review-attached (buffer)
  "Set up BUFFER, just attached.
Its files are kept out of its directory, its lock and vc dropped, and
any local variables it read dropped too."
  (with-current-buffer buffer
    (devops-bb-review-setup-buffer)
    (unlock-buffer)
    (when buffer-file-name
      (vc-file-clearprops buffer-file-name))
    (setq vc-mode nil)
    (when (or file-local-variables-alist dir-local-variables-alist)
      ;; Read again, without them: the advice is on.
      (normal-mode))))

(defun devops-bb-review-detached (buffer)
  "Undo `devops-bb-review-setup-buffer' in BUFFER, just detached."
  (with-current-buffer buffer
    (kill-local-variable 'create-lockfiles)
    (kill-local-variable 'backup-directory-alist)))

(defconst devops-bb-review--advice
  '((org-babel-execute-src-block devops-bb-review--execute ((depth . -95)))
    (org-babel-confirm-evaluate devops-bb-review--confirm ((depth . -95)))
    (org-babel-check-confirm-evaluate devops-bb-review--check-confirm nil)
    (org-babel-get-src-block-info devops-bb-review--src-block-info nil)
    (org-babel-lob-get-info devops-bb-review--lob-info nil)
    (org-babel-ref-resolve devops-bb-review--ref-resolve ((depth . -95)))
    (org-babel-generate-file-param devops-bb-review--file-param nil)
    (org-babel-read devops-bb-review--read nil)
    (org-babel-insert-result devops-bb-review--save-result ((depth . 95)))
    (hack-local-variables devops-bb-review--hack-local-variables nil)
    (vc-refresh-state devops-bb-review--vc-refresh nil))
  "The :around advice the review puts on while any buffer is attached.
Its run tracking is outermost, outside ob-confirm-posframe's (-90) and
devops.el's, so it sees a run before either does anything.  The save
is innermost (95), inside log.clj's, so a `save' entry precedes the
`result' one.")

(defconst devops-bb-review--hooks
  '((org-babel-after-execute-hook devops-bb-review--after-execute)
    (before-revert-hook devops-bb-review--before-revert)
    (after-revert-hook devops-bb-review--after-revert)
    (ob-confirm-posframe-notice-functions devops-bb-review--notices)
    (ob-confirm-posframe-header-functions devops-bb-review--header))
  "The hooks the review adds while any buffer is attached.")

(defun devops-bb-review-enable (enable)
  "Put the review's advice and hooks on, ENABLE non-nil, or take them off."
  (pcase-dolist (`(,symbol ,f ,props) devops-bb-review--advice)
    (if enable
        (advice-add symbol :around f props)
      (advice-remove symbol f)))
  (pcase-dolist (`(,hook ,f) devops-bb-review--hooks)
    (if enable (add-hook hook f) (remove-hook hook f))))

(provide 'devops-bb-review)

;;; devops-bb-review.el ends here
