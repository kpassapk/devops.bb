;; -*- mode: clojure; cljbang-whole-buffer: t -*-
(ns devops.bb.edits
  "Unsaved edits, logged to `devops-execution-log'.

  Loaded in the operator's Emacs with cljbang, not by bb:

    (cljbang-load-file \"~/src/github.com/kpassapk/devops.bb/emacs/devops/bb/edits.clj\")
    (devops-bb-edits-mode 1)

  An org buffer visiting a file logs `modify' on its first change since
  it was last unmodified, `save' when it is saved, `revert' when it is
  reloaded from disk, and `kill' when it is killed with unsaved edits,
  which are then gone. The last of these for a file says whether
  Emacs holds unsaved edits to it, so a reader of the log, such as a
  sandbox, can tell without asking Emacs."
  (:require [devops-log :as-alias log]))

(el! (declare-function devops-log-append "devops-log")
     (defvar devops-bb-edits-mode))

(defn- loggable? []
  (and (el! (bound-and-true-p devops-mode))
       (el! (bound-and-true-p devops-execution-log))
       (el/derived-mode-p 'org-mode)
       (el/buffer-file-name (el/buffer-base-buffer))))

(defn- log! [event]
  (when (loggable?)
    (el/require 'devops-log)
    (log/append
     {:time (el/format-time-string "%FT%T.%3N%z")
      :event event
      :file (el/buffer-file-name (el/buffer-base-buffer))
      :buffer (el/buffer-name)})))

(defn- on-change [] (log! "modify"))
(defn- on-save [] (log! "save"))
(defn- on-revert [] (log! "revert"))
(defn- on-kill [] (when (el/buffer-modified-p) (log! "kill")))

(defn- hooks! [on?]
  (let [f (if on? el/add-hook el/remove-hook)]
    (f 'first-change-hook 'devops-bb-edits--on-change)
    (f 'after-save-hook 'devops-bb-edits--on-save)
    (f 'after-revert-hook 'devops-bb-edits--on-revert)
    (f 'kill-buffer-hook 'devops-bb-edits--on-kill)))

(el! (define-minor-mode devops-bb-edits-mode
       "Log unsaved edits of org buffers to `devops-execution-log'.
A `modify' line on a buffer's first change, `save' when it is saved,
`revert' when it is reloaded from disk, `kill' when it is killed
with unsaved edits.  Logs only while `devops-mode'
is on and `devops-execution-log' is set."
       :global t
       :group 'devops
       (devops-bb-edits--hooks! devops-bb-edits-mode)))
