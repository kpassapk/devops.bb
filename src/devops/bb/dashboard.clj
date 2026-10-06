(ns devops.bb.dashboard
  "bb dashboard: follow `devops-execution-log', newest first, and open
  an event to see what Emacs knows about it.

  The Elm loop of charm.clj: `update' turns a message into the next
  state and maybe a command, `view' (devops.bb.view) draws the state.
  Commands are the only place anything is read: the log, every
  `poll-ms', and Emacs, when an event is opened."
  (:require
   [babashka.fs :as fs]
   [charm.message :as msg]
   [charm.program :as charm]
   [devops.bb.emacs :as emacs]
   [devops.bb.log :as log]
   [devops.bb.model :as model]
   [devops.bb.view :as view])
  (:import
   [java.time OffsetDateTime]))

(def poll-ms 500)

;;; Commands

(defn- poll-cmd [path offset]
  (charm/cmd (fn []
               (Thread/sleep poll-ms)
               (assoc (log/read-from path offset) :type :poll))))

(defn- block-event? [e]
  (and (#{:execute :result} (:event e)) (:file e) (:line e)))

(defn- detail-cmd [{:keys [seq] :as e}]
  (charm/cmd (fn []
               (try {:type :detail :seq seq :data (emacs/block-detail e)}
                    (catch Exception ex {:type :detail :seq seq :error (ex-message ex)})))))

(defn- visit-cmd [{:keys [file line]}]
  (when file
    (charm/cmd (fn [] (emacs/visit! file line) {:type :visited}))))

;;; State

(defn- event-count [state] (count (:events (:model state))))

(defn- selected [{:keys [model cursor]}]
  (nth (vec (model/newest-first model)) cursor nil))

(defn- clamp-cursor
  "Keep the cursor on an event and the window over the cursor."
  [{:keys [cursor top height] :as state}]
  (let [n      (event-count state)
        rows   (view/feed-rows height)
        cursor (max 0 (min cursor (dec n)))
        top    (cond (< cursor top)           cursor
                     (>= cursor (+ top rows)) (inc (- cursor rows))
                     :else                    top)]
    (assoc state :cursor cursor :top (max 0 top))))

(defn- move [state f] (clamp-cursor (update state :cursor f)))

(defn- add-events
  "Add a poll's events.  A cursor at the top follows the newest; one
  further down stays on the event it was on."
  [state {:keys [events reset offset]}]
  (let [state (cond-> state reset (assoc :model model/empty-model :cursor 0 :top 0))
        n     (count events)
        down? (pos? (:cursor state))]
    (cond-> (-> state
                (update :model model/add-events events)
                (assoc :offset offset))
      down? (-> (update :cursor + n) (update :top + n))
      true  clamp-cursor)))

(defn- open [state]
  (when-let [e (selected state)]
    (if (block-event? e)
      [(assoc state :page :detail :detail {:event e :status :loading :scroll 0})
       (detail-cmd e)]
      [(assoc state :page :detail :detail {:event e :status :ok :scroll 0}) nil])))

(defn- scroll [state f]
  (update-in state [:detail :scroll] #(max 0 (f %))))

(defn- quit? [m]
  (or (msg/key-match? m "q") (msg/key-match? m "ctrl+c")))

(defn update-fn [state m]
  (let [page-rows (view/feed-rows (:height state))]
    (cond
      (msg/window-size? m)
      [(clamp-cursor (assoc state :width (:width m) :height (:height m))) nil]

      (= :poll (:type m))
      [(-> (add-events state m) (assoc :now (OffsetDateTime/now)))
       (poll-cmd (:log-path state) (:offset m))]

      (= :detail (:type m))
      [(if (= (:seq m) (get-in state [:detail :event :seq]))
         (update state :detail merge {:status (if (:error m) :error :ok)
                                      :data (:data m) :error (:error m)})
         state)
       nil]

      (quit? m)
      [state charm/quit-cmd]

      (= :detail (:page state))
      (cond
        (or (msg/key-match? m :escape) (msg/key-match? m :left) (msg/key-match? m "h"))
        [(assoc state :page :feed :detail nil) nil]

        (or (msg/key-match? m "j") (msg/key-match? m :down))     [(scroll state inc) nil]
        (or (msg/key-match? m "k") (msg/key-match? m :up))       [(scroll state dec) nil]
        (or (msg/key-match? m " ") (msg/key-match? m :page-down)) [(scroll state #(+ % page-rows)) nil]
        (msg/key-match? m :page-up)                              [(scroll state #(- % page-rows)) nil]
        (msg/key-match? m "g")                                   [(scroll state (constantly 0)) nil]
        (msg/key-match? m "e")                                   [state (visit-cmd (get-in state [:detail :event]))]
        (msg/key-match? m "r")                                   (or (open (assoc state :page :feed)) [state nil])
        :else [state nil])

      :else
      (cond
        (or (msg/key-match? m "j") (msg/key-match? m :down))      [(move state inc) nil]
        (or (msg/key-match? m "k") (msg/key-match? m :up))        [(move state dec) nil]
        (or (msg/key-match? m " ") (msg/key-match? m :page-down)) [(move state #(+ % page-rows)) nil]
        (msg/key-match? m :page-up)                               [(move state #(- % page-rows)) nil]
        (msg/key-match? m "g")                                    [(move state (constantly 0)) nil]
        (msg/key-match? m "G")                                    [(move state (constantly Long/MAX_VALUE)) nil]
        (or (msg/key-match? m :enter) (msg/key-match? m :right) (msg/key-match? m "l"))
        (or (open state) [state nil])
        (msg/key-match? m "e")                                    [state (visit-cmd (selected state))]
        :else [state nil]))))

(defn view-fn [state]
  (if (= :detail (:page state))
    (view/detail state)
    (view/feed state)))

(defn init-state
  "The first state, with everything already in the log at PATH."
  [path emacs?]
  (let [{:keys [events offset missing]} (log/read-from path 0)]
    {:log-path path
     :emacs?   emacs?
     :offset   offset
     :model    (model/add-events model/empty-model events)
     :message  (when missing (str "No log yet at " path))
     :now      (OffsetDateTime/now)
     :width 80 :height 24
     :cursor 0 :top 0
     :page :feed}))

(defn run
  "Follow the devops.el execution log in a TUI"
  [{:keys [log]}]
  (let [from-emacs (emacs/log-path)
        path       (some-> (or log from-emacs) fs/expand-home str)]
    (when-not path
      (binding [*out* *err*]
        (println "No log: devops-execution-log is nil or Emacs is out of reach; pass --log PATH."))
      (System/exit 1))
    (let [state (init-state path (some? from-emacs))]
      (charm/run {:init       (fn [] [state (poll-cmd path (:offset state))])
                  :update     update-fn
                  :view       view-fn
                  :alt-screen true}))))
