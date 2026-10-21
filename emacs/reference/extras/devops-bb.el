;;; -*- lexical-binding: t -*-
;;;
;;; Emacs Bedrock
;;;
;;; Extra config: devops.bb

;;; Usage: Append or require this file from init.el.  It needs MELPA,
;;; which Bedrock's init.el adds, and Emacs 31 or later, like Bedrock.

;;; Contents:
;;;
;;;  - What this needs from your setup
;;;  - Org babel and devops.el
;;;  - devops.bb's Emacs side
;;;  - The emacsclient server

;; Assisted-by: Claude:claude-opus-5-5

;; The smallest set of packages that runs everything devops.bb needs:
;;
;; - devops.el            blocks on remote targets, `devops-mode'
;; - ob-confirm-posframe  the review before each block runs
;; - cljbang              devops.bb's Emacs side is partly cljbang
;; - devops.bb's emacs/   the execution log, scripting over emacsclient,
;;                        and sandbox feeds; from this checkout
;; - an emacsclient server, for the `devops-bb' commands

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;
;;;   What this needs from your setup
;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; Copied into your own config, this file no longer sits in the
;; checkout: set this before loading it.
(defvar ref-devops-bb-dir
  (expand-file-name "../../.." (file-name-directory
                                (file-truename (or load-file-name buffer-file-name))))
  "The devops.bb checkout whose emacs/ to load.
The `devops-bb' command on PATH should be from the same checkout, so
that both sides agree.")

;; The sandboxed agent writes the workbook through a container's
;; mount, which file notifications can miss.  Bedrock's init.el turns
;; auto-revert on but stops it polling; keep polling.
(setopt auto-revert-avoid-polling nil)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;
;;;   Org babel and devops.el
;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(use-package org
  :ensure nil                           ; built-in
  :custom
  (org-confirm-babel-evaluate t)        ; ob-confirm-posframe replaces the prompt
  :config
  (org-babel-do-load-languages 'org-babel-load-languages
                               '((emacs-lisp . t) (shell . t))))

(use-package devops
  :vc (:url "https://github.com/kpassapk/devops.el" :rev :newest)
  :custom
  (devops-enable-session-async t)
  :config
  (devops-mode 1)
  (require 'devops-lob)                 ; a project's tools.org, for #+call:
  (devops-lob-mode 1)
  (require 'devops-drift))              ; M-x devops-drift

(use-package posframe)

(use-package ob-confirm-posframe
  :vc (:url "https://github.com/kpassapk/ob-confirm-posframe" :rev :newest)
  :config
  (ob-confirm-posframe-mode 1))         ; a sandbox attach refuses without it

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;
;;;   devops.bb's Emacs side
;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(use-package cljbang
  :vc (:url "https://github.com/borkdude/cljbang.el" :rev :newest))

;; Not in an archive, so `:ensure nil'.  The log mode goes under
;; :config: :custom would only set the variable.
(use-package devops-bb
  :ensure nil
  :load-path (lambda () (expand-file-name "emacs" ref-devops-bb-dir))
  :demand t
  :after (devops ob-confirm-posframe cljbang)
  :custom
  (devops-bb-execution-log "~/.cache/devops/executions.jsonl")
  :config
  (devops-bb-log-mode 1)
  ;; The sandboxes a workbook's feed can go to, for
  ;; M-x devops-bb-sandbox-attach.  See the README's "Attaching a
  ;; workbook".
  (setq devops-bb-sandbox-sinks
        `(("wb-box" :via "docker exec -i -u root wb-box" :to "/feed"
           :map ((,(expand-file-name "~/work/wb") . "/work"))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;
;;;   The emacsclient server
;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; The devops-bb commands reach Emacs through emacsclient.
(require 'server)
(unless (or noninteractive (server-running-p))
  (server-start))
