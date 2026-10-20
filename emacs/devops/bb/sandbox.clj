;; -*- mode: clojure; cljbang-whole-buffer: t -*-
(ns devops.bb.sandbox
  "A workbook attached to a sandbox: the entries log.clj emits from its
  buffer, with each run's output, go to a child `devops-bb publish',
  which pushes them to the sandbox's feed.

  `devops-bb-sandbox-attach' asks for a sink, one of
  `devops-bb-sandbox-sinks', and a feed name, starts the publisher with
  stdin a pipe, and subscribes to the buffer's entries.  Each goes to
  the publisher as a JSON line:

  - a `result' with its run's answer as `output', read by ID from the
    session, roughly capped to twice the sink's :max-bytes;
  - a sync `execute' (not left running) with a new UUID as `id',
    `sync' true, and its #+RESULTS as `output', the block's body as
    `input';
  - anything else as it is, without output.

  A `line' is the line in the file, not in the buffer: an indirect
  buffer from `org-tree-to-indirect-buffer' is narrowed, and log.clj
  counts from its start.  The log file's lines are left as they are.

  A `status' line, with the buffer's file and `devops-mode', goes first,
  and again before the next entry whenever the file changed: a rename
  keeps the feed, and status.json follows.

  The state is a global registry, base buffer to attachment, not
  buffer-locals: `revert-buffer' kills those.  Detaching, killing the
  base buffer and quitting Emacs close the publisher's stdin, after the
  `kill' entry of a buffer killed with unsaved edits; the publisher
  pushes what it has, marks the feed detached and exits.  It ignores
  SIGHUP, which Emacs sends its children as it exits, so it outlives
  Emacs to do that.  Killing an indirect buffer does nothing, and its
  `kill' entry, which does not mean the workbook's edits are gone, is
  not sent.

  A publisher that dies while attached shows `sandbox: DEAD' in the
  mode line; attaching again offers to restart it, as a new feed.

  No review yet: an attached buffer runs blocks as any other does."
  (:require [devops-bb-scripting :as s]
            [devops.bb.log :as log]))

(el! (require 'org-id)

     (defcustom devops-bb-sandbox-sinks nil
       "Named sinks a workbook can be attached to: ((NAME . PLIST) ...).
PLIST holds the publisher's flags:

  :to DIR          the feed's directory, required
  :map ALIST       ((HOST-DIR . SANDBOX-DIR) ...): paths as the sandbox
                   sees them, for --map
  :max-lines N     the publisher's caps on one output
  :max-bytes N

A sink takes one workbook at a time.  Set here, in Emacs, and never in
a workbook: Claude writes the workbook, and a sink there could point
the push anywhere."
       :type '(alist :key-type (string :tag "Name")
                     :value-type (plist :tag "Flags"))
       :group 'devops)

     (defcustom devops-bb-sandbox-publish-command '("devops-bb" "publish")
       "The publisher, as a program and its first arguments.
The sink's flags follow them."
       :type '(repeat string)
       :group 'devops)

     (defvar devops-bb-sandbox--registry (make-hash-table :test 'eq)
       "Base buffer to its attachment, a map: :sink, the sink's name;
:feed; :process, the publisher; :subscriber; :file, the file the
publisher was last told of; :dead, once the publisher died attached.
Global, not buffer-local: `revert-buffer' kills buffer-locals.")

     (defvar devops-bb-sandbox-quit-timeout 2
       "Seconds quitting Emacs waits for the publishers to exit.")

     (defconst devops-bb-sandbox--max-bytes (* 64 1024)
       "The publisher's :max-bytes when the sink sets none.")

     (defconst devops-bb-sandbox--mode-line-entry
       '(:eval (devops-bb-sandbox--mode-line))
       "What `mode-line-misc-info' holds while a buffer is attached."))

(defn- base [buffer] (or (el/buffer-base-buffer buffer) buffer))

(defn attachment
  "The attachment of BUFFER, the current one by default, or nil.
  An indirect buffer has its base buffer's."
  [& [buffer]]
  (el/gethash (base (or buffer (el/current-buffer)))
              el/devops-bb-sandbox--registry))

(defn- sink-flags [name]
  (el/cdr (el/assoc name el/devops-bb-sandbox-sinks)))

;;; To the publisher

(defn- send!
  "Write X, a map, to ATT's publisher as a JSON line."
  [att x]
  (let [proc (:process att)]
    (when (el/process-live-p proc)
      (el/process-send-string proc (str (el/json-encode x) "\n")))))

(defn- follow-file!
  "Send BUFFER's status line when its file is not the one ATT last sent.
  Returns the attachment as it is now."
  [buffer att]
  (let [file (el/buffer-file-name buffer)]
    (if (= file (:file att))
      att
      (let [att (assoc att :file file)]
        (el/puthash buffer att el/devops-bb-sandbox--registry)
        (send! att {:event "status"
                    :file file
                    :devops-mode (if (el! (bound-and-true-p devops-mode))
                                   true
                                   :json-false)})
        att))))

;;; Entries

(defn- rough-cap
  "ANSWER, a run-output map, with its output cut to MAX-BYTES, head and
  tail.  A cut answer says so, with the full :lines and :bytes, for the
  publisher, whose exact cap cuts it again."
  [answer max-bytes]
  (let [text (:output answer)
        n (when text (el/string-bytes text))]
    (if (and n (> n max-bytes))
      (merge answer
             {:output (el/devops-bb-scripting--head-tail text max-bytes)
              :truncated true
              ;; 10 is a newline; the output is trimmed, so no last one.
              :lines (inc (el/cl-count 10 text))
              :bytes n})
      answer)))

(defn- run-answer
  "What run ID printed in SESSION, as a map."
  [session id]
  (let [a (el/devops-bb-scripting--run session id)]
    {:session (get a :session)
     :status (get a :status)
     :id id
     :input (get a :input)
     :output (get a :output)}))

(defn- block-text
  "The body and the #+RESULTS of the src block at LINE, or nil."
  [line]
  (try
    (el! (save-excursion
          (let ((el (devops-bb-scripting--src-block-at (clj! line))))
            (list (string-trim-right (org-element-property :value el))
                  (devops-bb-scripting--results-text)))))
    (catch :default _ nil)))

(defn- file-line
  "LINE of the current buffer, narrowed maybe, as a line of the file."
  [line]
  (+ line (el/line-number-at-pos (el/point-min) true) -1))

(defn- with-output
  "ENTRY, from the current buffer, as the feed gets it."
  [entry max-bytes]
  (let [{:keys [event session status id line]} entry
        sync (and (= event "execute") line (not= status :running))
        entry (cond
                (and (= event "result") session id)
                (assoc entry :output (rough-cap (run-answer session id) max-bytes))

                sync
                (let [[input result] (block-text line)
                      id (el/org-id-uuid)]
                  (merge entry
                         {:id id
                          :sync true
                          :status :done
                          :output (rough-cap {:session nil :status :done :id id
                                              :input input :output result}
                                             max-bytes)}))

                :else entry)]
    (if line (assoc entry :line (file-line line)) entry)))

(defn- subscriber
  "The function that sends each entry from BUFFER, a base buffer."
  [buffer]
  (fn [entry]
    (let [att (el/gethash buffer el/devops-bb-sandbox--registry)]
      (when (and att
                 (not (:dead att))
                 (not (and (= (:event entry) "kill") (el/buffer-base-buffer))))
        (let [att (follow-file! buffer att)
              max-bytes (or (el/plist-get (sink-flags (:sink att)) :max-bytes)
                            el/devops-bb-sandbox--max-bytes)]
          (send! att (with-output entry (* 2 max-bytes))))))))

;;; The publisher

(defn- publish-args
  "The publisher's flags for the sink FLAGS and the feed FEED."
  [flags feed]
  (let [to (el/plist-get flags :to)
        max-lines (el/plist-get flags :max-lines)
        max-bytes (el/plist-get flags :max-bytes)]
    (when (or (el/plist-get flags :via) (el/plist-get flags :ssh))
      (el/user-error "Sinks with :via or :ssh are not supported yet"))
    (when-not to (el/user-error "The sink has no :to"))
    (concat ["--to" (el/expand-file-name to)]
            (mapcat (fn [m]
                      ["--map" (str (el/directory-file-name (el/expand-file-name (el/car m)))
                                    "=" (el/cdr m))])
                    (el/plist-get flags :map))
            (when max-lines ["--max-lines" (str max-lines)])
            (when max-bytes ["--max-bytes" (str max-bytes)])
            ["--name" feed])))

(defn- start!
  "Start the publisher of BUFFER's feed FEED, to the sink FLAGS.
  Under `sh' with SIGHUP ignored, which `exec' keeps: Emacs sends it
  its children as it exits, and the publisher must live to flush."
  [buffer flags feed]
  (let [proc (el/make-process
              :name (str "devops-bb-sandbox " feed)
              :buffer (el/generate-new-buffer (str " *devops-bb-sandbox " feed "*"))
              :command (concat ["sh" "-c" "trap '' HUP; exec \"$@\"" "sh"]
                               el/devops-bb-sandbox-publish-command
                               (publish-args flags feed))
              :connection-type 'pipe
              :noquery true
              :sentinel 'devops-bb-sandbox--sentinel)]
    (el/process-put proc 'devops-bb-sandbox-buffer buffer)
    proc))

(defn- sentinel
  "Mark the attachment dead when its publisher exits while attached.
  A publisher that exits after a detach is done; its buffer goes, unless
  it failed."
  [proc event]
  (when-not (el/process-live-p proc)
    (let [buffer (el/process-get proc 'devops-bb-sandbox-buffer)
          att (el/gethash buffer el/devops-bb-sandbox--registry)]
      (cond
        (and att (el/eq (:process att) proc))
        (do (el/puthash buffer (assoc att :dead true) el/devops-bb-sandbox--registry)
            (el/force-mode-line-update true)
            (el/display-warning
             'devops-bb
             (el/format "Sandbox publisher for feed %s died (%s); see buffer %s.  M-x devops-bb-sandbox-attach restarts it"
                        (:feed att)
                        (str/trim event)
                        (el/buffer-name (el/process-buffer proc)))
             :error))

        (= 0 (el/process-exit-status proc))
        (el/kill-buffer (el/process-buffer proc))))))

;;; Attachments

(defn- mode-line []
  (when-let [att (attachment)]
    (str " sandbox: "
         (if (:dead att) (el/propertize "DEAD" 'face 'error) (:feed att))
         " ")))

(defn- hooks!
  "Add the hooks the attached buffers need, or remove them."
  [enable]
  (if enable
    (do (el/add-hook 'kill-buffer-hook 'devops-bb-sandbox--on-kill-buffer 90)
        (el/add-hook 'kill-emacs-hook 'devops-bb-sandbox--on-kill-emacs)
        (el/add-hook 'after-set-visited-file-name-hook 'devops-bb-sandbox--on-rename)
        (el/add-hook 'after-change-major-mode-hook 'devops-bb-sandbox--on-major-mode)
        (el/add-to-list 'mode-line-misc-info el/devops-bb-sandbox--mode-line-entry))
    (do (el/remove-hook 'kill-buffer-hook 'devops-bb-sandbox--on-kill-buffer)
        (el/remove-hook 'kill-emacs-hook 'devops-bb-sandbox--on-kill-emacs)
        (el/remove-hook 'after-set-visited-file-name-hook 'devops-bb-sandbox--on-rename)
        (el/remove-hook 'after-change-major-mode-hook 'devops-bb-sandbox--on-major-mode)
        (el/set 'mode-line-misc-info (el/delete el/devops-bb-sandbox--mode-line-entry
                                                el/mode-line-misc-info)))))

(defn- close!
  "Detach BUFFER: stop sending its entries and close its publisher's
  stdin, so the publisher pushes what it has and exits.  Returns the
  attachment, or nil."
  [buffer]
  (when-let [att (el/gethash buffer el/devops-bb-sandbox--registry)]
    (log/unsubscribe (:subscriber att) buffer)
    (el/remhash buffer el/devops-bb-sandbox--registry)
    (when (el/buffer-live-p buffer)
      (el! (with-current-buffer (clj! buffer)
             (kill-local-variable 'backup-by-copying))))
    (when (el/process-live-p (:process att))
      (el/process-send-eof (:process att)))
    (when (zero? (el/hash-table-count el/devops-bb-sandbox--registry))
      (hooks! false))
    (el/force-mode-line-update true)
    att))

;;; Hooks

(defn- on-kill-buffer
  "Detach a base buffer as it is killed, after log.clj sent its `kill'."
  []
  (close! (el/current-buffer)))

(defn- on-kill-emacs
  "Detach every buffer, and wait for the publishers to exit, a while."
  []
  (let [procs (map (fn [b] (:process (close! b)))
                   (el/hash-table-keys el/devops-bb-sandbox--registry))
        deadline (+ (el/float-time) el/devops-bb-sandbox-quit-timeout)]
    (loop []
      (when (and (some #(el/process-live-p %) procs)
                 (< (el/float-time) deadline))
        (el/accept-process-output nil 0.05)
        (recur)))))

(defn- on-rename
  "Tell the publisher of the file a renamed attached buffer now visits."
  []
  (when-let [att (el/gethash (el/current-buffer) el/devops-bb-sandbox--registry)]
    (follow-file! (el/current-buffer) att)))

(defn- on-major-mode
  "Copy, not rename, on backup: the sandbox's mount and pull follow the
  file.  Again after a revert, which kills buffer-locals."
  []
  (when (el/gethash (el/current-buffer) el/devops-bb-sandbox--registry)
    (el! (setq-local backup-by-copying t))))

;;; Commands

(defn- check!
  "Signal a `user-error' when BUFFER may not be attached."
  [buffer]
  (let [file (el/buffer-file-name buffer)]
    (when-not (and file (el! (with-current-buffer (clj! buffer)
                               (derived-mode-p 'org-mode))))
      (el/user-error "Not an org buffer visiting a file"))
    (when (el/file-remote-p file)
      (el/user-error "A remote workbook cannot be attached: %s" file))
    (when-not (el! (bound-and-true-p ob-confirm-posframe-mode))
      (el/user-error "Turn on ob-confirm-posframe-mode first: an attached buffer's runs need its review"))))

(defn- warn-siblings
  "Warn when FILE's directory holds other .org files: the sandbox gets
  the whole directory, and their #+RESULTS hold past server output."
  [file]
  (let [others (remove #(= % (el/file-name-nondirectory file))
                       (el/directory-files (el/file-name-directory file) nil
                                           "\\`[^.].*\\.org\\'"))]
    (when (seq others)
      (el/display-warning
       'devops-bb
       (el/format "%s shares its directory with %s; give a sandboxed workbook a directory of its own"
                  (el/file-name-nondirectory file) (str/join ", " others))))))

(defn- attach-args
  "Ask for `devops-bb-sandbox-attach's sink and feed name.
  A dead publisher's attachment offers its own, to restart."
  []
  (let [buffer (base (el/current-buffer))
        att (attachment buffer)
        names (map #(el/car %) el/devops-bb-sandbox-sinks)]
    (check! buffer)
    (if (and att (:dead att)
             (el/y-or-n-p (el/format "The publisher of feed %s died; restart it, as a new feed? "
                                     (:feed att))))
      (list (:sink att) (:feed att))
      (let [_ (when-not (seq names)
                (el/user-error "No sinks: set devops-bb-sandbox-sinks"))
            sink (el/completing-read "Sink: " names nil true nil nil
                                     (or (:sink att) (first names)))
            default (or (:feed att) (el/file-name-base (el/buffer-file-name buffer)))
            feed (el/read-string (el/format "Feed name (default %s): " default)
                                 nil nil default)]
        (list sink feed)))))

(defn attach
  "Attach the current buffer's workbook to the sink SINK, as feed FEED.
  SINK names one of `devops-bb-sandbox-sinks'; FEED defaults to the
  file's base name.  Starts the publisher, and from then on sends it
  every entry log.clj emits from the buffer, or any indirect buffer of
  it.  A buffer already attached is asked about first, unless its
  publisher died."
  [sink feed]
  (interactive (devops-bb-sandbox--attach-args))
  (let [buffer (base (el/current-buffer))
        flags (sink-flags sink)
        att (attachment buffer)]
    (check! buffer)
    (when-not (el/assoc sink el/devops-bb-sandbox-sinks)
      (el/user-error "No sink %s in devops-bb-sandbox-sinks" sink))
    (doseq [[b a] (map (fn [b] [b (el/gethash b el/devops-bb-sandbox--registry)])
                       (el/hash-table-keys el/devops-bb-sandbox--registry))]
      (when (and (not (el/eq b buffer)) (= sink (:sink a)) (not (:dead a)))
        (el/user-error "Sink %s is %s's" sink (el/buffer-name b))))
    (when (and att (not (:dead att))
               (not (el/y-or-n-p (el/format "Attached to sink %s as feed %s; replace it? "
                                            (:sink att) (:feed att)))))
      (el/user-error "Still attached to sink %s" (:sink att)))
    (publish-args flags feed)
    (close! buffer)
    (warn-siblings (el/buffer-file-name buffer))
    (let [f (subscriber buffer)]
      (el/puthash buffer {:sink sink
                          :feed feed
                          :process (start! buffer flags feed)
                          :subscriber f
                          :file nil}
                  el/devops-bb-sandbox--registry)
      (hooks! true)
      (follow-file! buffer (attachment buffer))
      (log/subscribe f buffer)
      (el! (with-current-buffer (clj! buffer)
             (devops-bb-sandbox--on-major-mode)))
      (el/force-mode-line-update true)
      (el/message "Attached %s to sink %s as feed %s" (el/buffer-name buffer) sink feed)
      (attachment buffer))))

(defn detach
  "Detach the current buffer's workbook from its sandbox.
  The publisher pushes what it has, marks the feed detached and exits."
  []
  (interactive)
  (if-let [att (close! (base (el/current-buffer)))]
    (el/message "Detached from sink %s" (:sink att))
    (el/message "Not attached")))
