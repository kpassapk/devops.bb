(ns devops.bb.emacs
  "The operator's Emacs, the one `emacsclient' reaches.

  The log says what ran; Emacs knows what it printed.  Output comes from
  devops.el's `devops-scripting-block-output', and the block itself from
  cljbang-org's `cljbang-org-src-blocks'."
  (:require
   [babashka.process :as p]
   [cheshire.core :as json]
   [clojure.string :as str]))

(defn eval-json
  "Evaluate FORM, elisp whose value `json-encode' takes, in the
  operator's Emacs; the value, read.

  Base64 on the way back, because `emacsclient' prints the value with
  `prin1', and shell output is full of what that escapes."
  [form]
  (let [{:keys [out err exit]}
        (p/shell {:out :string :err :string :continue true}
                 "emacsclient" "--eval"
                 (str "(base64-encode-string"
                      " (encode-coding-string (json-encode " form ") 'utf-8) t)"))]
    (when-not (zero? exit)
      (throw (ex-info (str/trim (str/replace (str err out) #"^\*ERROR\*: " "")) {})))
    (-> (str/trim out)
        (str/replace #"^\"|\"$" "")
        (->> (.decode (java.util.Base64/getDecoder)))
        (String. "UTF-8")
        (json/parse-string true))))

(defn- elisp-str [s] (pr-str (str s)))

(defn log-path
  "`devops-execution-log', expanded, or nil when it is off or Emacs is
  out of reach."
  []
  (try
    (eval-json "(and (boundp 'devops-execution-log) devops-execution-log
                     (expand-file-name devops-execution-log))")
    (catch Exception _ nil)))

(defn- block-form [file line session id]
  (format
   "(let* ((file %s) (line %d) (session %s) (id %s)
           (run (and session id
                     (progn (require 'devops-scripting)
                            (fboundp 'devops-scripting-run-output))
                     (devops-scripting-run-output session id)))
           (out (condition-case err
                    (progn (require 'devops-scripting)
                           (devops-scripting-block-output file line))
                  (error (list (cons :error (error-message-string err))))))
           (blk (condition-case nil
                    (progn (require 'cljbang-org)
                           (seq-find (lambda (b) (<= (map-elt b :line-start) line
                                                     (map-elt b :line-end)))
                                     (cljbang-org-src-blocks file)))
                  (error nil)))
           (heading (and blk
                         (with-current-buffer (find-file-noselect file)
                           (org-with-wide-buffer
                            (goto-char (point-min))
                            (forward-line (1- line))
                            (ignore-errors (org-get-heading t t t t)))))))
      (list (cons :run run)
            (cons :output out)
            (cons :block (and blk
                              (list (cons :language (map-elt blk :language))
                                    (cons :name (map-elt blk :name))
                                    (cons :body (map-elt blk :body))
                                    (cons :line-start (map-elt blk :line-start))
                                    (cons :line-end (map-elt blk :line-end))
                                    (cons :heading heading))))))"
   (elisp-str file) line
   (if session (elisp-str session) "nil")
   (if id (elisp-str id) "nil")))

(defn block-detail
  "What Emacs knows about a block run, from its log line:
  {:run    <devops-scripting-run-output answer for SESSION and ID, or nil>
   :output <devops-scripting-block-output answer at LINE, or {:error ..}>
   :block  {:language :name :body :line-start :line-end :heading} or nil}.
  :run is that run exactly; :output is the block's latest, or #+RESULTS.
  Nothing runs: all are reads."
  [{:keys [file line session id]}]
  (eval-json (block-form file line session id)))

(defn visit!
  "Show LINE of FILE in Emacs."
  [file line]
  (p/shell {:out :string :err :string :continue true}
           "emacsclient" "-n" (str "+" (or line 1)) file))
