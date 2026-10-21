;; -*- mode: clojure; cljbang-whole-buffer: t -*-
(ns devops.bb.log
  "The execution log: a JSON line in `devops-bb-execution-log' per thing
  the user did, for an agent that follows the file.

  Loaded in the operator's Emacs with cljbang, not by bb; devops-bb.el
  loads it.  With `devops-bb-log-mode' and `devops-mode' on and
  `devops-bb-execution-log' set, a line is written

  - `execute' when a block runs, and `result' when an async block's
    result arrives;
  - `tangle' and `drift' when `devops-tangle' and `devops-drift' run;
  - `modify' on an org buffer's first change since it was last
    unmodified, `save' when it is saved, `revert' when it is reloaded
    from disk, and `kill' when it is killed with unsaved edits.  The
    last of these for a file says whether Emacs holds unsaved edits to
    it.

  A line is a notification, not a record: it says what happened and
  where, and carries no output, #+RESULTS or diff.  The agent reads
  those with `devops-bb-scripting-block-output' or the drift functions.
  That keeps lines short, so appends from several Emacsen don't
  interleave and the file grows slowly, and keeps secrets out of it.

  Runs a script asks for are not the user's, and are not logged: blocks
  run under `devops-with-sync', for a reference, a dynamic target or a
  tangle, and tangles and drift checks called as functions.

  Each of these is an entry, a map, built once and handed to each
  subscriber: the log file, while `devops-bb-execution-log' is set; the
  functions `devops-bb-log-subscribe' added for every buffer; and those
  it added for the base buffer of the one the entry comes from, so a run
  from an indirect buffer reaches its base buffer's.  A subscriber that
  signals is reported in *Messages*, and the others, and what the user
  did, go on."
  (:require [devops-bb-scripting :as s]))

(el! (defcustom devops-bb-execution-log nil
       "File to append a JSON line to per user action, or nil for none.
An agent can follow the file with `tail -F' and learn what the user did
without being told.  See `devops-bb-log-mode' for what is logged.  No
line holds output: the agent reads it with
`devops-bb-scripting-block-output'."
       :type '(choice (const :tag "Off" nil) file)
       :group 'devops)

     (defvar devops-bb-log--async-result nil
       "The session's name while ob-comint inserts the result of an async run.
Bound by `devops-bb-log--during-async-filter', so that
`devops-bb-log--log-async-result' logs only that insertion, not every
result a block inserts, and names the session without resolving the
target.")

     (defvar devops-bb-log--inserting nil
       "Non-nil while `org-babel-insert-result' runs.
The run, not the user, is changing the buffer, so
`devops-bb-log--on-change' logs no `modify' for it.")

     (defvar devops-bb-log--subscribers nil
       "Functions `devops-bb-log-subscribe' added for every buffer, in order.")

     (defvar devops-bb-log--buffer-subscribers
       (make-hash-table :test 'eq :weakness 'key)
       "Base buffer to the functions `devops-bb-log-subscribe' added for it.
Global, not buffer-local: `revert-buffer' kills buffer-locals.")

     (defvar devops-bb-log-mode))

(defn append-entry
  "Append ENTRY, a map, to `devops-bb-execution-log' as a JSON line.
  The log's directory is created on the first write."
  [entry]
  (let [file (el/expand-file-name el/devops-bb-execution-log)]
    (el/make-directory (el/file-name-directory file) true)
    (el/write-region (str (el/json-encode entry) "\n") nil file true 'silent)))

(defn- header [event]
  {:time (el/format-time-string "%FT%T.%3N%z")
   :event event
   :file (el/buffer-file-name (el/buffer-base-buffer))
   :buffer (el/buffer-name)})

;;; Subscribers

(defn- base
  "BUFFER's base buffer, or BUFFER if it is not indirect."
  [buffer]
  (or (el/buffer-base-buffer buffer) buffer))

(defn- adjoin [f fs]
  (if (el/memq f fs) fs (el/append fs (list f))))

(defn subscribe
  "Call F with each entry emitted, a map, from then on.
  With BUFFER, only the entries from it and from the other buffers that
  share its base buffer; without, the entries from every buffer.  F is
  added once however often it is subscribed, and called in the order
  added, after the log file."
  [f & [buffer]]
  (if buffer
    (let [b (base buffer)
          table el/devops-bb-log--buffer-subscribers]
      (el/puthash b (adjoin f (el/gethash b table)) table))
    (el/set 'devops-bb-log--subscribers
            (adjoin f el/devops-bb-log--subscribers)))
  f)

(defn unsubscribe
  "Stop calling F, as `devops-bb-log-subscribe' added it with BUFFER."
  [f & [buffer]]
  (if buffer
    (let [b (base buffer)
          table el/devops-bb-log--buffer-subscribers
          fs (el/remq f (el/gethash b table))]
      (if fs (el/puthash b fs table) (el/remhash b table)))
    (el/set 'devops-bb-log--subscribers
            (el/remq f el/devops-bb-log--subscribers)))
  nil)

(defn- subscribers
  "The functions an entry from the current buffer goes to, in order."
  []
  (concat (when el/devops-bb-execution-log ['devops-bb-log-append-entry])
          el/devops-bb-log--subscribers
          (el/gethash (base (el/current-buffer))
                      el/devops-bb-log--buffer-subscribers)))

(defn- listening? []
  (and (el! (bound-and-true-p devops-mode)) (seq (subscribers))))

(defn- emit
  "Call each subscriber with the entry for EVENT from the current buffer.
  The entry is its header merged with the map FIELDS returns, and is
  built once, only when some subscriber listens."
  [event & [fields]]
  (when-let [fs (listening?)]
    (let [entry (merge (header event) (when fields (fields)))]
      (doseq [f fs]
        (try (el/funcall f entry)
             (catch :default e
               (el/message "devops-bb-log: %S failed on %s: %s"
                           f event (el/error-message-string e))))))))

;;; Block runs

(defn- block-output [line session]
  (el! (save-excursion
        (devops-bb-scripting--block-output
         (devops-bb-scripting--src-block-at (clj! line))
         (or (clj! session) (user-error "Not run on a #+TARGET"))))))

(defn- block-fields
  "The fields of an entry on the src block at POS, run in SESSION.
  Its line, and its session, status and id as
  `devops-bb-scripting-block-output' answers them for SESSION; ID, when
  given, is the run's, and replaces the one the block would be asked
  for.  The target is not resolved again: for a dynamic target that
  would run its block.  A block that ran on no target, SESSION nil, is
  logged with an error."
  [pos session id]
  (let [line (el/line-number-at-pos pos)]
    (merge {:line line}
           (try
             (let [answer (block-output line session)]
               {:session (get answer :session)
                :status (get answer :status)
                :id (or id (get answer :id))})
             (catch :default e {:error (el/error-message-string e)})))))

(defn- block! [event pos session id]
  (when (el/devops-user-run-p)
    (emit event (fn [] (block-fields pos session id)))))

(defn- on-execute
  "Log the src block just executed, for `org-babel-after-execute-hook'.
  An async block has only started: its status is `:running', and its
  result is logged again by `devops-bb-log--log-async-result'."
  []
  (when el/org-babel-current-src-block-location
    (block! "execute" el/org-babel-current-src-block-location
            el/devops-block-session nil)))

(defn- during-async-filter
  "Run F, `org-babel-comint-async-filter', with ARGS, logging results.
  ob-comint runs no hook when an async result arrives; it calls
  `org-babel-insert-result' from this filter, with point on the block."
  [f & args]
  (el! (let ((devops-bb-log--async-result (buffer-name)))
         (apply (clj! f) (clj! args)))))

(defn- placeholder-id-at
  "The run ID in #+RESULTS of the src block at POS, or nil."
  [pos]
  (el! (ignore-errors
        (save-excursion
         (devops-bb-scripting--src-block-at (line-number-at-pos (clj! pos)))
         (devops-bb-scripting--placeholder-id (devops-bb-scripting--results-text))))))

(defn- log-async-result
  "Run F, `org-babel-insert-result', with ARGS; log an async result.
  Point is on the block before F runs, which may move it.  The block's
  #+RESULTS still holds the run's ID then, and the output that replaces
  it may print the ID of any other run, so the ID is read before F."
  [f & args]
  (let [pos (el/point)
        session el/devops-bb-log--async-result
        id (when (and session (listening?)) (placeholder-id-at pos))
        value (el! (let ((devops-bb-log--inserting t))
                     (apply (clj! f) (clj! args))))]
    (when session
      (el! (let ((devops-bb-log--async-result nil))
             (clj! (block! "result" pos session id)))))
    value))

;;; Commands

(defn- heading
  "The heading at point, {:heading :line}; nil before the first one."
  []
  (el! (save-excursion
        (when (ignore-errors (org-back-to-heading t) t)
          (clj! {:heading (el/org-get-heading true true true true)
                 :line (el/line-number-at-pos)})))))

(defn- tangle-fields [results]
  {:targets (el/vconcat (map (fn [[tag target n]]
                               {:tag tag :target target :files n})
                             results))})

(defn- drift-fields
  "The drift check's files, without :diff: the log says what drifted,
  and `devops-drift-headline' or `devops-drift-all' says how."
  [entries]
  {:files (el/vconcat (map (fn [entry]
                             {:status (el/alist-get :status entry)
                              :tag (el/alist-get :tag entry)
                              :path (el/alist-get :path entry)
                              :remote (el/alist-get :remote entry)
                              :detail (el/alist-get :detail entry)})
                           entries))})

(defn- on-command
  "Log EVENT, a command on the heading at point, for
  `devops-command-functions'.  ALL non-nil means it ran on every
  target-tagged heading, and no heading is logged."
  [event all value err]
  (emit event
        (fn []
          (merge {:all (if all true :json-false)}
                 (when-not all (heading))
                 (cond err {:error (el/error-message-string err)}
                       (= event "tangle") (tangle-fields value)
                       :else (drift-fields value))))))

;;; Unsaved edits

(defn- edit! [event]
  (when (and (el/derived-mode-p 'org-mode)
             (el/buffer-file-name (el/buffer-base-buffer)))
    (emit event)))

(defn- on-change [] (when-not el/devops-bb-log--inserting (edit! "modify")))
(defn- on-save [] (edit! "save"))
(defn- on-revert [] (edit! "revert"))
(defn- on-kill [] (when (el/buffer-modified-p) (edit! "kill")))

;;; Mode

(defn- hooks! [enable]
  (let [hook (if enable el/add-hook el/remove-hook)]
    (hook 'org-babel-after-execute-hook 'devops-bb-log--on-execute)
    (hook 'devops-command-functions 'devops-bb-log--on-command)
    (hook 'first-change-hook 'devops-bb-log--on-change)
    (hook 'after-save-hook 'devops-bb-log--on-save)
    (hook 'after-revert-hook 'devops-bb-log--on-revert)
    (hook 'kill-buffer-hook 'devops-bb-log--on-kill))
  (if enable
    (do (el/advice-add 'org-babel-comint-async-filter :around
                       'devops-bb-log--during-async-filter)
        (el/advice-add 'org-babel-insert-result :around
                       'devops-bb-log--log-async-result))
    (do (el/advice-remove 'org-babel-comint-async-filter
                          'devops-bb-log--during-async-filter)
        (el/advice-remove 'org-babel-insert-result
                          'devops-bb-log--log-async-result))))

(el! (define-minor-mode devops-bb-log-mode
       "Log what the user does with devops.el to `devops-bb-execution-log'.
An `execute' line when a block runs, `result' when an async block's
result arrives, `tangle' and `drift' for `devops-tangle' and
`devops-drift', and `modify', `save', `revert' and `kill' for an org
buffer's unsaved edits.  Logs only while `devops-mode' is on and
`devops-bb-execution-log' is set; while `devops-mode' is on, the same
entries go to the functions `devops-bb-log-subscribe' added."
       :global t
       :group 'devops
       (devops-bb-log--hooks! devops-bb-log-mode)))
