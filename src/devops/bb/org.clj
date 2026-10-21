(ns devops.bb.org
  "Just enough org to read a workbook's task headings without Emacs:
  each heading's keyword, title, tags and :PROPERTIES: drawer."
  (:require
   [clojure.string :as str]))

(def ^:private todo-re #"(?:TODO|NEXT|WAIT|DONE|CANCELED)\s+")

(defn- parse-heading [line n]
  (when-let [[_ stars rest] (re-matches #"(\*+)\s+(.*?)\s*" line)]
    (let [[_ todo rest]  (or (re-matches (re-pattern (str "(" todo-re ")?(.*)")) rest)
                             [nil nil rest])
          [_ title tags] (re-matches #"(.*?)(?:\s+(:[^\s:]+(?::[^\s:]+)*:))?" rest)]
      {:level (count stars)
       :line  n
       :todo  (some-> todo str/trim)
       :title (str/trim title)
       :tags  (set (some-> tags (str/split #":") (->> (remove str/blank?))))
       :props {}})))

(defn- parse-prop [line]
  (when-let [[_ k v] (re-matches #"\s*:([^\s:]+):\s*(.*?)\s*" line)]
    (when-not (#{"PROPERTIES" "END"} (str/upper-case k))
      [(str/upper-case k) v])))

(defn headings
  "The headings of org TEXT, in order, each {:level :line :todo :title
  :tags :props}.  :props has the upper-cased names of the heading's
  :PROPERTIES: drawer, which must follow the heading line (or its
  planning line)."
  [text]
  (let [step (fn [{:keys [out cur drawer] :as st} [n line]]
               (let [h (parse-heading line n)]
                 (cond
                   h (assoc st :out (cond-> out cur (conj cur)) :cur h :drawer :before)

                   (nil? cur) st

                   (= drawer :in)
                   (if (re-matches #"\s*:END:\s*" line)
                     (assoc st :drawer :done)
                     (cond-> st (parse-prop line) (update-in [:cur :props] conj (parse-prop line))))

                   (and (= drawer :before) (re-matches #"\s*:PROPERTIES:\s*" line))
                   (assoc st :drawer :in)

                   (or (str/blank? line)
                       (re-matches #"\s*(?:SCHEDULED|DEADLINE|CLOSED):.*" line))
                   st

                   :else (assoc st :drawer :done))))
        {:keys [out cur]} (reduce step {:out [] :cur nil :drawer :done}
                                  (map-indexed (fn [i l] [(inc i) l]) (str/split-lines text)))]
    (cond-> out cur (conj cur))))

(defn find-heading
  "The one heading of org TEXT that SEL names: its :ID: or :CUSTOM_ID:,
  or else a case-insensitive substring of its title.  Throws, with the
  candidates, when SEL names none or several."
  [text sel]
  (let [all (headings text)
        by-id (filter #(or (= sel (get-in % [:props "ID"]))
                           (= sel (get-in % [:props "CUSTOM_ID"]))) all)
        hits (if (seq by-id)
               by-id
               (filter #(str/includes? (str/lower-case (:title %)) (str/lower-case sel)) all))]
    (case (count hits)
      1 (first hits)
      0 (throw (ex-info (str "no heading matches " (pr-str sel)) {:selector sel}))
      (throw (ex-info (str "several headings match " (pr-str sel) ": "
                           (str/join "; " (map #(str "line " (:line %) " " (:title %)) hits)))
                      {:selector sel})))))
