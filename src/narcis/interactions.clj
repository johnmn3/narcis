(ns narcis.interactions
  "The Interactions console's server side — Run, the prompt, Stop — over
   narcis.eval (jonquil's evaluator, lifted) and narcis.console's
   decisions (what Return does, how a result reads).

   The transcript is state, so it lives in an Eve atom of the store,
   beside the editor's: {:entries [[kind text] ...] :dropped n :ns s}.
   kind is one of narcis.console's styles (\"out\" \"err\" \"prompt\"
   \"result\" \"error\" \"system\") or \"input\", what was typed at the
   prompt. Every client reads the same transcript by position (:dropped
   counts entries trimmed from the front, so a position never moves),
   and it is there after a restart.

   The evaluation runs on a worker thread. What it prints is handed to
   this namespace's queue and written to the atom by flush!, which the
   server calls with its one lock held — so every Eve write, the
   editor's and the console's, happens under that lock.

   Run resets (jonquil.session's rule, DrRacket's): every namespace an
   evaluation creates belongs to this console; a Run removes those and
   `user`, then evaluates into a fresh `user`, so a definition deleted
   from the buffer is gone from the program too. Namespaces that existed
   when the console was opened, and clojure.*, are never removed. The
   prompt continues where the last evaluation left *ns*.

   This is Jolt's evaluator: narcis.eval drives jolt.host's interrupt
   token, which is what lets Stop end a loop that never blocks."
  (:require [clojure.string :as str]
            [narcis.commands :as commands]
            [narcis.console :as console]
            [narcis.docs :as docs]
            [narcis.eval :as eval]
            [narcis.store :as store])
  (:import [java.util.concurrent LinkedBlockingQueue]))

(def atom-name "console")

(def max-chars
  "The transcript keeps about this many characters; older entries go."
  400000)

(defn open
  "The console of store st: its evaluator context, its transcript atom,
   and the queue between them."
  [st]
  {:ctx (eval/make-eval-context)
   :atom (store/open-atom st atom-name {:entries [] :dropped 0 :ns "user"})
   :pending (LinkedBlockingQueue.)
   :ns (atom nil)
   ;; what was here before the user's program: never reset
   :base-ns (set (map ns-name (all-ns)))
   ;; the namespaces evaluations have made since: a Run removes them
   :owned (atom #{})})

(defn- value [con] (store/atom-deref (:atom con)))

(defn console-styles
  "The console's style table under theme t, for the page: each kind's
   name -> {:fg :bold :italic} (narcis.console/style), with what is
   typed at the prompt in the console's plain text colour."
  [t]
  (let [kinds (keys console/styles)
        m (into {} (keep (fn [k] (when-let [s (console/style t k)] [(name k) s]))) kinds)]
    (assoc m "input" (get m "out"))))

(defn current-ns
  "The namespace the prompt names, a string."
  [con]
  (or (some-> @(:ns con) str) (:ns (value con)) "user"))

(defn status
  "\"idle\", \"running\" or \"waiting-input\"."
  [{:keys [ctx]}]
  (cond (eval/waiting-for-input? ctx) "waiting-input"
        (eval/evaluating? ctx) "running"
        :else "idle"))

;; ---------------------------------------------------------------------------
;; the transcript

(defn- say!
  "Queue one transcript entry; flush! writes it."
  [{:keys [pending]} kind text]
  (when (and text (pos? (count text)))
    (.offer ^LinkedBlockingQueue pending [(name kind) text])))

(defn- drain [^LinkedBlockingQueue q]
  (loop [acc []]
    (if-let [x (.poll q)]
      (recur (conj acc x))
      acc)))

(defn- joined
  "entries with each run of one kind as one entry."
  [entries]
  (reduce (fn [acc [k t]]
            (let [l (peek acc)]
              (if (and l (= k (first l)) (< (count (second l)) 8192))
                (conj (pop acc) [k (str (second l) t)])
                (conj acc [k t]))))
          []
          entries))

(defn- trimmed
  "s with entries dropped from the front until it holds max-chars or less."
  [{:keys [entries dropped] :as s}]
  (let [total (reduce + 0 (map (fn [e] (count (second e))) entries))]
    (loop [i 0 total total]
      (if (and (> total max-chars) (< (inc i) (count entries)))
        (recur (inc i) (- total (count (second (nth entries i)))))
        (if (zero? i)
          s
          (assoc s :entries (vec (subvec entries i)) :dropped (+ dropped i)))))))

(defn flush!
  "Write what the evaluator has said since the last flush! into the
   transcript atom: one swap. Called with the server's lock held."
  [con]
  (let [new (joined (drain (:pending con)))
        ns-now (some-> @(:ns con) str)]
    (when (or (seq new) (and ns-now (not= ns-now (:ns (value con)))))
      (store/atom-swap! (:atom con)
                        (fn [s]
                          (let [es (:entries s)
                                ;; a chunk continuing the last entry's kind joins it
                                merged (joined (into (if (seq es) [(peek es)] []) new))
                                es (into (if (seq es) (pop es) []) merged)]
                            (trimmed (cond-> (assoc s :entries es)
                                       ns-now (assoc :ns ns-now)))))))
    nil))

(defn read-from
  "The transcript from position since on: {:entries [[kind text] ...]
   :start n :from n :next n :status s :ns s} (:start is the oldest
   position still kept). A since behind what is kept reads
   from the oldest kept entry (:from says where that is); the last
   entry may still grow, so a reader asks again from its position."
  [con since]
  (flush! con)
  (let [{:keys [entries dropped]} (value con)
        total (+ dropped (count entries))
        from (max dropped (min total (or since 0)))]
    {:entries (subvec entries (- from dropped))
     :start dropped
     :from from
     :next total
     :status (status con)
     :ns (current-ns con)}))

(defn clear!
  "Forget the transcript (positions go on from where they were)."
  [con]
  (flush! con)
  (store/atom-swap! (:atom con)
                    (fn [s] (assoc s :dropped (+ (:dropped s) (count (:entries s))) :entries [])))
  nil)

;; ---------------------------------------------------------------------------
;; evaluating

(defn- track-namespaces!
  "After an evaluation: the namespaces that appeared since the console
   was opened (clojure.* apart) are this console's to reset."
  [con]
  (let [created (remove (:base-ns con) (map ns-name (all-ns)))
        created (remove (fn [n] (str/starts-with? (str n) "clojure.")) created)]
    (swap! (:owned con) into created)))

(defn- reset-namespaces!
  "Remove this console's namespaces and `user`; the next evaluation
   makes what it needs."
  [con]
  (doseq [n (conj @(:owned con) 'user)]
    (when (find-ns n) (remove-ns n)))
  (reset! (:owned con) #{})
  (reset! (:ns con) 'user))

(defn- evaluate!
  "Start code on the evaluator in the prompt's namespace; its output and
   its result line go to the transcript. opts are narcis.eval/eval-async's."
  [con code opts]
  (let [warning (eval/eval-async
                 (:ctx con) code
                 (merge {:ns-name (symbol (current-ns con))
                         :on-output (fn [kind chunk] (say! con kind chunk))
                         :on-complete (fn [result]
                                        (track-namespaces! con)
                                        (when-let [n (:ns result)] (reset! (:ns con) n))
                                        (let [[kind text] (console/result-line result)]
                                          (say! con kind text)))}
                        opts))]
    (when (string? warning) (say! con :system warning))
    nil))

(defn run!
  "Run: evaluate text (a buffer's whole text) as the program named label
   — the header line, then what it prints, then its last value. A
   bracket that does not match is said before anything runs. source-path
   is the file the text is, when it is one."
  [con text label source-path]
  (cond
    (eval/evaluating? (:ctx con))
    (say! con :system "; a program is still running: Stop it first\n")

    :else
    (do (say! con :system (str "; --- Running " label " ---\n"))
        (if-let [problem (commands/pre-run-problem text)]
          (say! con :error (str problem "\n"))
          (do (reset-namespaces! con)
              ;; names are about to be redefined: no remembered doc is theirs
              (docs/clear-doc-cache!)
              (evaluate! con text (cond-> {:ns-name 'user}
                                    source-path (assoc :source-path source-path)))))))
  nil)

(defn run-code!
  "Evaluate code sent from the editor (Run Selection, Run Form): shown
   at the prompt as if typed, then evaluated."
  [con code]
  (when-not (str/blank? code)
    (say! con :prompt (str (current-ns con) "=> "))
    (say! con :input (str code "\n"))
    (swap! (:history (:ctx con)) eval/history-add code)
    (evaluate! con code {}))
  nil)

(defn run-selection!
  "Run Selection: the selected text of snapshot snap ({:text :caret
   :sel-start :sel-end}), or the caret's line. True when there was code."
  [con snap]
  (if-let [code (some-> snap commands/run-selection-text)]
    (do (run-code! con code) true)
    false))

(defn run-form!
  "Run Form: the top-level form around snap's caret. True when there was one."
  [con snap]
  (if-let [form (some-> snap commands/run-form)]
    (do (run-code! con (:text form)) true)
    false))

(defn input!
  "Return was pressed on text at the prompt. Answers what the page does:
   {:action \"newline\"} — keep typing (Shift+Return, or a bracket still
   open); \"eval\" — sent to the evaluator (the page clears its input);
   \"stdin\" — sent to the running program's read-line; \"busy\" — a
   program is running and not reading; \"noop\" — nothing typed."
  [con text shift?]
  (let [ctx (:ctx con)
        action (console/input-action {:input text
                                      :waiting? (eval/waiting-for-input? ctx)
                                      :evaluating? (eval/evaluating? ctx)
                                      :shift? (boolean shift?)
                                      :leftover nil})]
    (case action
      :newline {:action "newline"}
      :send-stdin (do (say! con :input (str text "\n"))
                      (eval/push-stdin! ctx text)
                      {:action "stdin"})
      :busy {:action "busy"}
      :noop {:action "noop"}
      :leftover {:action "noop"}
      :eval (do (run-code! con (str/trim text))
                {:action "eval"}))))

(defn stop!
  "Stop: ask the running evaluation to end. True when one was running."
  [con]
  (some? (eval/stop-eval! (:ctx con))))

(defn history!
  "Step the prompt's history: dir is :prev or :next, current the text on
   the input line. Answers the text to show."
  [con dir current]
  (let [h (:history (:ctx con))]
    (if (= dir :prev)
      (let [[h2 text] (eval/history-prev @h (or current ""))]
        (reset! h h2)
        text)
      (let [[h2 text] (eval/history-next @h)]
        (reset! h h2)
        text))))
