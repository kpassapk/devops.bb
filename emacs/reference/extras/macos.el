;;; -*- lexical-binding: t -*-
;;;
;;; Emacs Bedrock
;;;
;;; Extra config: macOS

;;; Usage: Append or require this file from init.el.  Off macOS it
;;; does nothing.

;;; Contents:
;;;
;;;  - The shell's PATH
;;;  - SF Symbols tool bar icons

;; Assisted-by: Claude:claude-opus-5-5

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;
;;;   The shell's PATH
;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; GUI Emacs on macOS doesn't get the shell's PATH, where bb, docker
;; and podman live.
(use-package exec-path-from-shell
  :if (memq window-system '(mac ns))
  :config (exec-path-from-shell-initialize))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;
;;;   SF Symbols tool bar icons
;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; Calle 24 draws the tool bar with SF Symbols, the icons macOS apps
;; use, in place of Emacs's own.  Only the NS build has them.
;;
;; The images are copied into calle24/ here once: run
;; M-x calle24-install, and restart Emacs.  It saves the new
;; `image-load-path' to custom.el.
(use-package calle24
  :if (eq window-system 'ns)
  :config
  ;; Bedrock's early-init.el turns the tool bar off; these are its icons.
  (tool-bar-mode 1)
  (if (file-directory-p calle24-image-directory)
      (calle24-refresh-appearance)
    (message "For SF Symbols in the tool bar, run M-x calle24-install and restart"))
  (add-hook 'compilation-mode-hook #'calle24-refresh-appearance))
