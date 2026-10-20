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
;;
;; Setup:
;;
;;   (add-to-list 'load-path "~/src/github.com/kpassapk/devops.bb/emacs")
;;   (require 'devops-bb)
;;   (setq devops-bb-execution-log "~/.cache/devops/executions.jsonl")
;;   (devops-bb-log-mode 1)

;;; Code:

(require 'devops-bb-scripting)
(require 'cljbang)

(add-to-list 'cljbang-load-path
             (file-name-directory (or load-file-name buffer-file-name)))
(cljbang-require 'devops.bb.log)

(provide 'devops-bb)

;;; devops-bb.el ends here
