;;; -*- lexical-binding: t -*-
;;;
;;; Emacs Bedrock
;;;
;;; Extra config: Vim emulation

;;; Usage: Append or require this file from init.el for bindings in Emacs.

;;; Contents:
;;;
;;;  - Core Packages

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;
;;;   Core Packages
;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; Evil: vi emulation
(use-package evil
  :init
  (setq evil-respect-visual-line-mode t)
  (setq evil-undo-system 'undo-redo)
  (setopt evil-want-fine-undo t)

  ;; Bug as of 2026-01-12; see https://github.com/emacs-evil/evil/issues/1983
  (defvar evil-mode-buffers '())

  ;; Enable this if you want C-u to scroll up, more like pure Vim
  ;(setq evil-want-C-u-scroll t)

  ;; Digraphs: hit C-k <char1> <char2> to insert special characters
  ;; e.g. `C-k ?!' inserts `‽'. There are many built-in with
  ;; evil-mode; this table is the set of user-defined extras. Good for
  ;; quickly inserting commonly-used characters; use `insert-char'
  ;; (bound to C-x 8 RET) for all Unicode characters.
  (setopt evil-digraphs-table-user
          '(((?? ?!) . ?\x203d)   ; ‽
            ((?l ?l) . ?\x03bb)   ; λ
            ))

  :config
  (evil-mode)

  ;; If you use Magit, start editing in insert state
  (add-hook 'git-commit-setup-hook 'evil-insert-state)

  ;; Configuring initial major mode for some modes
  (evil-set-initial-state 'eat-mode 'emacs)
  (evil-set-initial-state 'vterm-mode 'emacs))
