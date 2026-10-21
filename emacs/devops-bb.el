;;; devops-bb.el --- devops.bb's side of the operator's Emacs -*- lexical-binding: t; -*-

;; Copyright (C) 2026 Kyle S Passarelli

;; Author: Kyle S Passarelli <kyle.passarelli@gmail.com>
;; Assisted-by: Claude:claude-opus-5-5
;; URL: https://github.com/kpassapk/devops.bb
;; Package-Requires: ((emacs "30.1") (devops "0") (cljbang "0.0.9"))

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
;; What devops.bb needs in the Emacs it asks, beyond devops.el:
;;
;; - devops-bb-scripting.el  functions for scripts over emacsclient
;; - devops/bb/log.clj       `devops-bb-log-mode', which writes
;;                           `devops-bb-execution-log'; cljbang
;; - devops/bb/sandbox.clj   `devops-bb-sandbox-attach', which feeds a
;;                           workbook's runs to a sandbox; cljbang
;; - devops-bb-review.el     the review of each run in an attached
;;                           workbook, shown by ob-confirm-posframe
;;
;; And one fix to org: an async result no longer re-runs the block's
;; :var dependencies (see `devops-bb--async-filter').
;;
;; Setup:
;;
;;   (add-to-list 'load-path "~/src/github.com/kpassapk/devops.bb/emacs")
;;   (require 'devops-bb)
;;   (setq devops-bb-execution-log "~/.cache/devops/executions.jsonl")
;;   (devops-bb-log-mode 1)
;;   ;; A sandbox's feed, for M-x devops-bb-sandbox-attach, which
;;   ;; needs ob-confirm-posframe-mode for its review:
;;   (setq devops-bb-sandbox-sinks '(("box" :to "~/feeds/box")))

;;; Code:

(require 'devops-bb-scripting)
(require 'devops-bb-review)
(require 'cljbang)

(add-to-list 'cljbang-load-path
             (file-name-directory (or load-file-name buffer-file-name)))
(cljbang-require 'devops.bb.log)
(cljbang-require 'devops.bb.sandbox)

;;; Async results

(defvar devops-bb--in-async-filter nil
  "Non-nil while `org-babel-comint-async-filter' inserts a result.")

(defun devops-bb--async-filter (fn &rest args)
  "Around `org-babel-comint-async-filter' (FN with ARGS).
The filter reads the block's info only for its result params, but
`org-babel-get-src-block-info' resolves every :var reference, and
resolving one runs its block: once per result, three times for :file.
Resolve none while the filter runs."
  (let ((devops-bb--in-async-filter t))
    (apply fn args)))

(defun devops-bb--skip-ref-in-filter (fn ref)
  "Around `org-babel-ref-resolve' (FN with REF): nil in the async filter."
  (unless devops-bb--in-async-filter
    (funcall fn ref)))

(advice-add 'org-babel-comint-async-filter :around #'devops-bb--async-filter)
;; Outside the review's (-95), which would confirm a reference first.
(advice-add 'org-babel-ref-resolve :around #'devops-bb--skip-ref-in-filter
            '((depth . -100)))

(provide 'devops-bb)

;;; devops-bb.el ends here
