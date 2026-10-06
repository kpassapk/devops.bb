(ns devops.bb.model
  "What the log's events mean together, as plain data.

  The model is {:events [...]}, oldest first, each event given a :seq,
  its index.  An async block logs `execute' when it starts and `result'
  when its answer arrives, both with the run's :id; pairing them gives
  the result a :duration and marks the execute :ended."
  (:import
   [java.time Duration]))

(def empty-model {:events [] :exec-by-id {}})

(defn- duration [from to]
  (when (and from to) (Duration/between from to)))

(defn- add-event [{:keys [events exec-by-id] :as m} e]
  (let [n (count events)
        e (assoc e :seq n)]
    (case (:event e)
      :execute
      (cond-> (update m :events conj e)
        (:id e) (assoc-in [:exec-by-id (:id e)] n))

      :result
      (if-let [i (some-> (:id e) exec-by-id)]
        (let [start (get-in events [i :time])]
          (-> m
              (assoc-in [:events i :ended] (:time e))
              (update :events conj (assoc e :duration (duration start (:time e))
                                          :exec-seq i))))
        (update m :events conj e))

      (update m :events conj e))))

(defn add-events [m events]
  (reduce add-event m events))

(defn running? [e]
  (and (= :execute (:event e))
       (= "running" (:status e))
       (not (:ended e))))

(defn problem?
  "True for an event that went wrong: an error, or drift that isn't same."
  [e]
  (boolean
   (or (:error e)
       (and (= :drift (:event e))
            (some #(not= "same" (:status %)) (:files e))))))

(defn summary [{:keys [events]}]
  {:running (count (filter running? events))
   :results (count (filter #(= :result (:event %)) events))
   :tangles (count (filter #(= :tangle (:event %)) events))
   :drifts  (count (filter #(= :drift (:event %)) events))
   :problems (count (filter problem? events))})

(defn newest-first [{:keys [events]}]
  (rseq events))
