(ns devops.bb.view
  "State to text: (feed state) and (detail state) each return the whole
  screen as one string, WIDTH by HEIGHT.  Pure, so a test can compare
  a screen to the text it expects."
  (:require
   [charm.style.color :as color]
   [charm.style.core :as style]
   [clojure.string :as str]
   [devops.bb.model :as model])
  (:import
   [java.time Duration OffsetDateTime]
   [java.time.format DateTimeFormatter]))

;;; Text

(def ^:private styles
  {:dim  (style/style :faint true)
   :ok   (style/style :fg color/green)
   :warn (style/style :fg color/yellow)
   :err  (style/style :fg color/red :bold true)
   :head (style/style :bold true)
   :sel  (style/style :reverse true)})

(defn- paint [k s] (if-let [st (styles k)] (style/render st s) s))

(defn strip-ansi [s] (str/replace (str s) #"\x1b\[[0-9;?]*[A-Za-z]" ""))

(defn- clean
  "S as plain text: no escape sequences, no carriage-return overdraw, no tabs."
  [s]
  (->> (str/split-lines (strip-ansi s))
       (map #(-> % (str/split #"\r") last str (str/replace "\t" "    ")))))

(defn- fit
  "S cut or padded to exactly N columns."
  [s n]
  (let [s (str s) c (count s)]
    (cond (<= n 0) ""
          (> c n)  (str (subs s 0 (dec n)) "…")
          :else    (str s (apply str (repeat (- n c) " "))))))

(defn- fit-left [s n]
  (let [s (str s) c (count s)]
    (if (> c n) (fit s n) (str (apply str (repeat (- n c) " ")) s))))

(defn- vis
  "Columns S takes on screen."
  [s]
  (count (strip-ansi s)))

(defn- spread
  "LEFT and RIGHT on one line of WIDTH, RIGHT flush right.  Either may
  be painted; LEFT loses its paint only if it has to be cut."
  [left right width]
  (let [room (- width (vis right))
        lv   (vis left)]
    (str (if (> lv room)
           (fit (strip-ansi left) room)
           (str left (apply str (repeat (- room lv) " "))))
         right)))

;; ━, not ─: jline draws ─ with the terminal's line-drawing charset,
;; which tmux shows as "q", and charm's render turns it into "--".
(defn- rule [width] (apply str (repeat width "━")))

;;; Values

(def ^:private hms (DateTimeFormatter/ofPattern "HH:mm:ss"))
(def ^:private md-hm (DateTimeFormatter/ofPattern "MM-dd HH:mm"))

(defn fmt-time [^OffsetDateTime t ^OffsetDateTime now]
  (cond (nil? t) "        "
        (and now (= (.toLocalDate t) (.toLocalDate now))) (.format t hms)
        :else (.format t md-hm)))

(defn fmt-duration [^Duration d]
  (when d
    (let [s (.getSeconds d)]
      (cond (< s 10)   (format "%.1fs" (/ (.toMillis d) 1000.0))
            (< s 60)   (str s "s")
            (< s 3600) (format "%dm%02ds" (quot s 60) (rem s 60))
            :else      (format "%dh%02dm" (quot s 3600) (rem (quot s 60) 60))))))

(defn- session-name [s] (some-> s (str/replace #"^devops:" "")))

(defn- where [{:keys [buffer file line all]}]
  (str (or buffer (some-> file (str/replace #".*/" "")))
       (when (and line (not (true? all))) (str ":" line))))

(defn- count-by-status [files]
  (->> (frequencies (map :status files))
       (sort-by key)
       (map (fn [[k n]] (str n " " k)))
       (str/join ", ")))

(defn- n-files
  "Files tangled over TARGETS; the log gives each target's as a count."
  [targets]
  (let [n (reduce + (map #(or (:files %) 0) targets))]
    (str n (if (= 1 n) " file" " files"))))

(defn row-parts
  "How the feed shows event E: {:glyph :label :what :status :tone}."
  [e]
  (let [err (:error e)]
    (merge
     {:label (name (:event e)) :what (or (:heading e) (session-name (:session e)) "")}
     (case (:event e)
       :execute (if (model/running? e)
                  {:glyph "▶" :tone :warn :status "running…"}
                  {:glyph "▶" :tone :dim :status ""})
       :result  {:glyph "✓" :tone :ok :status (or (fmt-duration (:duration e)) (:status e))}
       :tangle  {:glyph "⇣" :tone :ok :what (if (true? (:all e)) "all headings" (:heading e))
                 :status (n-files (:targets e))}
       :drift   {:glyph (if (model/problem? e) "≠" "=")
                 :tone (if (model/problem? e) :warn :ok)
                 :what (if (true? (:all e)) "all headings" (:heading e))
                 :status (count-by-status (:files e))}
       {:glyph "·" :tone :dim :status ""})
     (when err {:glyph "✗" :tone :err :status "error" :what err}))))

;;; Feed

(defn feed-rows
  "Rows the feed has room for, given HEIGHT: header, rule, rule, footer."
  [height]
  (max 1 (- height 4)))

(defn- feed-row [e now width selected?]
  (let [{:keys [glyph label what status tone]} (row-parts e)
        w-status 16
        w-where  20
        fixed    (+ 1 8 2 2 8 1 w-where 1 1 w-status)
        w-what   (max 4 (- width fixed))
        plain    (str " " (fmt-time (:time e) now) "  "
                      glyph " " (fit label 8) " "
                      (fit (where e) w-where) " "
                      (fit what w-what) " "
                      (fit-left status w-status))
        plain    (fit plain width)]
    (if (or selected? (< width fixed))
      (cond->> plain selected? (paint :sel))
      (str " " (paint :dim (fmt-time (:time e) now)) "  "
           (paint tone glyph) " " (fit label 8) " "
           (fit (where e) w-where) " "
           (fit what w-what) " "
           (paint tone (fit-left status w-status))))))

(defn- header [{:keys [log-path emacs? now width]}]
  (spread (str " devops.bb · " (str/replace (str log-path) (System/getProperty "user.home") "~")
               " · emacs "
               (if emacs? (paint :ok "●") (paint :err "○")))
          (str (some-> ^OffsetDateTime now (.format hms)) " ")
          width))

(defn feed
  "The feed screen: every event, newest at the top."
  [{:keys [model now width height cursor top message] :as state}]
  (let [events (vec (model/newest-first model))
        rows   (feed-rows height)
        shown  (subvec events (min top (count events)) (min (count events) (+ top rows)))
        {:keys [running results tangles drifts problems]} (model/summary model)
        counts (str " " (paint :warn (str "▶" running)) " running "
                    (paint :ok (str "✓" results)) " done "
                    "⇣" tangles " tangled "
                    "=" drifts " drift "
                    (when (pos? problems) (paint :err (str "✗" problems " problems"))))
        keys   (paint :dim "⏎ open  e emacs  q quit ")
        body   (concat
                (map-indexed (fn [i e] (feed-row e now width (= (+ top i) cursor))) shown)
                (when (empty? events)
                  [(paint :dim (fit (str " " (or message "Waiting for the first line of the log…")) width))])
                (repeat ""))]
    (str/join "\n"
              (concat [(header state) (rule width)]
                      (take rows body)
                      [(rule width)
                       (spread counts keys width)]))))

;;; Detail

(defn- section
  "A rule with TITLE in it."
  [title width]
  (let [title (let [n (max 1 (- width 4))]
                (if (> (count title) n) (fit title n) title))]
    (str "━━ " (paint :head title) " " (rule (- width 4 (count title))))))

(defn- output-section [title text width]
  (concat [(section title width)] (map #(str " " %) (clean text))))

(defn- code-lines [s]
  (->> (str/split-lines (str s)) (map str/trim) (remove str/blank?)))

(defn current-block
  "The block now at the event's line, if it is the one that ran.
  The file may have changed since; with the run's own input in hand,
  a block whose code differs is someone else's."
  [{:keys [run block]}]
  (when (and block
             (or (not (:input run))
                 (= (code-lines (:input run)) (code-lines (:body block)))))
    block))

(defn- block-lines
  "Body lines for a block event, from Emacs' answer DATA.
  The run's own output when the session still holds it; otherwise
  the block's latest run, or its #+RESULTS, said as such."
  [e {:keys [run output block] :as data} width]
  (let [{:keys [session status id source error]} output
        own?  (#{"done" "running"} (:status run))
        other (and (not own?) id (not= id (:id e)))]
    (concat
     [(str " " (paint :dim "session ") (session-name (or (:session e) session)))
      (str " " (paint :dim "status  ") (or (:status run) status (:status e))
           (when-let [d (or (:duration e) (some-> (:ended e) (#(Duration/between (:time e) %))))]
             (str "  " (fmt-duration d))))
      (str " " (paint :dim "run     ") (or (:id e) "—"))
      ""]
     (cond
       (current-block data)
       (concat [(section (str "source · " (:language block)
                              " · lines " (:line-start block) "–" (:line-end block)) width)]
               (map #(str " " %) (clean (:body block))))

       (:input run)
       (concat (output-section "sent · this run" (:input run) width)
               [(paint :dim (str " the block at line " (:line e) " has changed since"))])

       :else
       [(section "source" width)
        (paint :dim " no src block at that line now; the file changed since it ran")])
     [""]
     (cond
       own?
       (output-section "output · this run" (:output run) width)

       error
       [(section "output" width) (paint :err (str " " error))]

       (:output output)
       (concat
        (when (:id e)
          [(paint :warn (fit (str " this run is gone from the session; showing "
                                  (if other (str "run " id) "#+RESULTS")) width))])
        (output-section (str "output · " (if (= "session" source) (str "run " id) "#+RESULTS"))
                        (:output output) width))

       :else
       [(section "output" width)
        (paint :dim (str " none: " (case status
                                     "no-session" (str "no session " session)
                                     (str session " holds no run of this block"))))]))))

(defn- command-lines [e width]
  (concat
   (when-let [err (:error e)] [(paint :err (str " " err)) ""])
   (case (:event e)
     :tangle
     (concat [(section "targets" width)]
             (for [{:keys [tag target files]} (:targets e)]
               (str " " (fit tag 10) " " (fit-left files 4) (if (= 1 files) " file   " " files  ") target)))

     :drift
     (concat [(section "files" width)]
             (for [{:keys [status tag path remote detail]} (:files e)]
               (let [tone (if (= "same" status) :ok :warn)]
                 (str " " (paint tone (fit status 8)) " " (fit tag 10) " "
                      (or remote path) (when detail (paint :dim (str "  " detail)))))))
     [])))

(defn detail-lines
  "Every line of the detail screen's body, before scrolling."
  [{:keys [width] {:keys [event status data error]} :detail}]
  (cond
    (#{:tangle :drift} (:event event)) (command-lines event width)
    (= status :loading)                [(paint :dim " asking Emacs…")]
    error                              [(paint :err (str " Emacs: " error))]
    :else                              (block-lines event data width)))

(defn detail
  "The detail screen for the event the feed had selected."
  [{:keys [now width height] {:keys [event scroll data]} :detail :as state}]
  (let [rows  (feed-rows height)
        lines (vec (detail-lines state))
        top   (max 0 (min scroll (- (count lines) rows)))
        title (str " " (where event)
                   (when-let [h (or (:heading (current-block data)) (:heading event))] (str " · " h))
                   " · " (name (:event event))
                   " · " (fmt-time (:time event) now))
        keys  (paint :dim "↑↓ scroll  e emacs  r reload  ← back  q quit ")]
    (str/join "\n"
              (concat [(paint :head (fit title width)) (rule width)]
                      (->> (concat (drop top lines) (repeat ""))
                           (take rows)
                           (map #(if (> (vis %) width) (fit (strip-ansi %) width) %)))
                      [(rule width)
                       (spread (paint :dim (str " " (inc top) "/" (max 1 (count lines))))
                               keys
                               width)]))))
