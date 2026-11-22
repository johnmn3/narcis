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
    (:refer-clojure :exclude [run!])
    (:require [clojure.string :as str]
              [eve.alpha :as eve :refer [$]]
              [narcis.breakpoints :as bp]
              [narcis.commands :as commands]
              [narcis.console :as console]
              [narcis.debugger :as debugger]
              [narcis.docs :as docs]
              [narcis.eval :as eval])
    #?(:clj (:import [java.util.concurrent LinkedBlockingQueue])))

(declare breakpoint-hook)

;; The transcript's pending queue and the worker thread's identity are
;; the clj hosts' machinery: a host without threads runs no worker, so
;; it has no queue to drain and no pause to serve.
#?(:clj
   (do (defn- pending-queue [] (LinkedBlockingQueue.))
       (defn- queue-offer! [q x] (.offer q x))
       (defn- queue-poll [q] (.poll q))))
(defn- current-thread [] #?(:clj (Thread/currentThread) :cljs nil))
(defn- await-reply
       "A paused program's answer to a break-eval!, waited for up to 5 s."
       [p]
       #?(:clj (deref p 5000 {:error "The paused program did not answer."})
          :cljs {:error "The paused program did not answer."}))

(def max-chars
     "The transcript keeps about this many characters; older entries go."
     400000)

(defn open
      "The console of store st: its evaluator context, its transcript
   (the app atom's :console slot), and the queue between them."
      [st]
      (swap! (:atom st) update :console
             (fn [v] (or v {:entries [] :dropped 0 :ns "user"})))
      (let [con {:ctx (eval/make-eval-context)
                 :store st
                 :pending (pending-queue)
                 :ns (atom nil)
             ;; what was here before the user's program: never reset
                 :base-ns (set (map ns-name (all-ns)))
             ;; the namespaces evaluations have made since: a Run removes them
                 :owned (atom #{})
             ;; the debugger (narcis.debugger, lifted from jonquil): one
             ;; break per console, the pause it is serving, the last
             ;; error, and whether Break on error is on
                 :break (debugger/make-break)
                 :break-on? (atom false)
                 :stepping (atom nil)
                 :eval-thread (atom nil)
                 :last-error (atom nil)
                 :pause (atom nil)}]
           (assoc con :hook (breakpoint-hook con))))

(defn- break-handlers
       "The debugger's callbacks for this console: a pause is kept where the
   console ops read it, and a resume clears it. A frame's file is the
   path evaluations were given, so it resolves to itself."
       [con]
       {:resolve-file (fn [f] f)
        :on-pause (fn [d] (reset! (:pause con) d))
        :on-resume (fn [_] (reset! (:pause con) nil))})

(defn- breakpoint-hook
       "This console's breakpoint hook: a breakpoint pauses through the
   console's break, but only on the worker thread of the evaluation
   that is running — any other thread (a future, a lazy value realized
   while painting) runs on, as a pause there would freeze the page."
       [con]
       (debugger/breakpoint-handler
        (:break con)
        (fn [] (break-handlers con))
        (fn [] (identical? (current-thread) @(:eval-thread con)))
        (:stepping con)))

(defn- arm!
       "Install this console's breakpoint hook (which also turns debug mode
   on, so frames keep their locals). {:on? true} or {:error s} on a
   Jolt without jolt.debug, where nothing is installed and breakpoint
   forms do nothing."
       [con]
       (debugger/arm-breakpoints! (:hook con)))

(defn- value [con] ($ (get @(:atom (:store con)) :console)))

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
             (queue-offer! pending [(name kind) text])))

(defn- drain [q]
       (loop [acc []]
             (if-let [x (queue-poll q)]
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
   transcript slot: one swap. Called with the server's lock held."
      [con]
      (let [new (joined (drain (:pending con)))
            ns-now (some-> @(:ns con) str)]
           (when (or (seq new) (and ns-now (not= ns-now (:ns (value con)))))
                 (swap! (:atom (:store con)) update :console (fn [s]
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
      (swap! (:atom (:store con)) update :console (fn [s] (assoc s :dropped (+ (:dropped s) (count (:entries s))) :entries [])))
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
                              :before-eval (fn [] (reset! (:eval-thread con) (current-thread)))
                         ;; Break on error: the throw pauses before it
                         ;; unwinds, through this console's break
                              :wrap-eval (when (and @(:break-on? con) (not (:quiet opts)))
                                               (debugger/break-wrapper (:break con) (break-handlers con)))
                              :on-complete (fn [result]
                                        ;; a pause this evaluation left
                                        ;; behind is stale now; the error
                                        ;; is the Frames panel's until the
                                        ;; next one
                                               (debugger/end-stale-pause! (:break con) (break-handlers con))
                                               (when (and (= :error (:status result)) (not (:quiet opts)))
                                                     (reset! (:last-error con) (:error result)))
                                               (let [me (current-thread)]
                                                    (when (identical? me @(:eval-thread con))
                                                          (debugger/stop-stepping! (:stepping con))
                                                          (reset! (:eval-thread con) nil)))
                                               (track-namespaces! con)
                                               (when-let [n (:ns result)] (reset! (:ns con) n))
                                               (let [[kind text] (console/result-line result)]
                                                    (say! con kind text)))}
                             opts))]
            (when (string? warning) (say! con :system warning))
            nil))

(defn- buffer-ns
       "The namespace text's ns form names, or \"user\"."
       [text]
       (if-let [nsf (bp/ns-form-text text)]
               (or (second (re-find #"\(\s*ns\s+([a-zA-Z0-9_.\-]+)" nsf)) "user")
               "user"))

(defn- instrumented!
       "text with a breakpoint form at each of breakpoints' lines that holds
   one, the hook armed first; a line that holds none is said in the
   transcript. Answers the text to evaluate. On a Jolt without
   jolt.debug the hook cannot arm and the inserted form could not even
   load, so the answer is the text itself, with the reason said —
   breakpoints there are inert, exactly as upstream's are."
       [con text breakpoints source-path]
       (let [r (arm! con)]
            (if (:error r)
                (do (say! con :system (str "; Breakpoints are off: " (:error r) "\n"))
                    text)
      ;; run!'s reset just removed `user`, and the macro census
      ;; resolves in it before the evaluation remakes it
                (let [_ (when-not (find-ns 'user) (create-ns 'user))
                      heads (debugger/macro-heads (buffer-ns text) (bp/head-symbols text))
                      opts {:heads (:heads heads) :ns-found? (:ns-found? heads) :tab source-path}
                      {new-text :text} (bp/instrument text breakpoints opts)]
                     (when-let [msg (bp/dropped-message (bp/dropped-lines text breakpoints opts))]
                               (say! con :system (str msg "\n")))
                     new-text))))

(defn arm-breakpoints!
      "Arm (or disarm) this console's breakpoint hook, as the page's gutter
   asks: {:on? b} or {:error s} on a Jolt without jolt.debug."
      [con on?]
      (if on?
          (arm! con)
          (debugger/disarm-breakpoints! (:hook con))))

(defn run!
      "Run: evaluate text (a buffer's whole text) as the program named label
   — the header line, then what it prints, then its last value. A
   bracket that does not match is said before anything runs. source-path
   is the file the text is, when it is one. breakpoints, when given, are
   the 1-based lines the page's gutter marks: the text is instrumented
   with a breakpoint form at each that holds one before it runs."
      ([con text label source-path] (run! con text label source-path nil))
      ([con text label source-path breakpoints]
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
                        (evaluate! con (if (seq breakpoints)
                                           (instrumented! con text breakpoints source-path)
                                           text)
                                   (cond-> {:ns-name 'user}
                                           source-path (assoc :source-path source-path)))))))
       nil))

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
      "Stop: ask the running evaluation to end — a pause it is serving ends
   first, so a stopped program never waits for a Continue nobody sends.
   True when one was running."
      [con]
      (debugger/stop-stepping! (:stepping con))
      (debugger/continue! (:break con))
      (debugger/break-stop! (:break con))
      (some? (eval/stop-eval! (:ctx con))))

;; ---------------------------------------------------------------------------
;; the debugger: frames, locals, pauses

(defn debug-state
      "Where the debugger stands, for every console answer: whether this
   Jolt has jolt.debug at all, whether Break on error is on, and the
   pause being served, if any (its title and breakpoint line)."
      [con]
      {:available (debugger/available?)
       :break-on? (boolean @(:break-on? con))
       :paused? (some? @(:pause con))
       :pause (some-> @(:pause con) (select-keys [:title :breakpoint :step? :tab :error-id]))})

(defn frames!
      "The Frames panel's data: the pause's frames while paused, else the
   last error's, else a note that nothing has gone wrong. :rows are
   narcis.debugger/rows of the same data, ready to render."
      [con]
      (if-let [d @(:pause con)]
              (assoc d :rows (debugger/rows d) :paused? true)
              (if-let [e @(:last-error con)]
                      (let [d (debugger/frames-data e {:frames-fn (debugger/frames-fn)
                                                       :resolve-file (fn [f] f)})]
                           (assoc d :rows (debugger/rows d) :paused? false))
                      {:title "Frames" :frames []
                       :rows [{:kind :note :text "Nothing has gone wrong yet."}]
                       :paused? false})))

(defn local!
      "One local's full text: {:text s}, or {:error s} when it is gone or
   the panel is showing an older error. error-id is the frames' own."
      [con error-id frame local]
      (let [p (debugger/paused-local (:break con) error-id frame local)]
           (if (not= :narcis.debugger/not-paused p)
               (if (= :narcis.debugger/missing p)
                   {:error "That local is gone."}
                   {:text p})
               (if-let [e @(:last-error con)]
                       (cond (debugger/stale? e error-id) {:error debugger/STALE}
                             :else (let [v (debugger/local-value e frame local
                                                                 {:frames-fn (debugger/frames-fn)})]
                                        (if (= :narcis.debugger/missing v)
                                            {:error "That local is gone."}
                                            {:text v})))
                       {:error "That local is gone."}))))

(defn debug-eval!
      "Evaluate code in frame's locals: in the pause while paused (the
   paused thread serves it), else in the last error's frame. Answers
   eval-in-frame's {:text :out} or {:error s}."
      [con error-id frame code]
      (if @(:pause con)
          (let [p (promise)]
               (debugger/break-eval! (:break con) frame code (fn [r] (deliver p r)))
               (await-reply p))
          (if-let [e @(:last-error con)]
                  (if (and error-id (debugger/stale? e error-id))
                      {:error debugger/STALE}
                      (debugger/eval-in-frame e frame code {:frames-fn (debugger/frames-fn)
                                                            :token (debugger/make-token)}))
                  {:error "Nothing is paused, and no error's frames are showing."})))

(defn continue!
      "Continue a pause. True when one was waiting."
      [con]
      (debugger/continue! (:break con)))

(defn step!
      "Step a pause: mode is :step (into), :over or :out. True when a
   breakpoint pause was waiting."
      [con mode]
      (debugger/step! (:break con) (:stepping con) mode))

(defn toggle-break!
      "Flip Break on error. Turning it on turns debug mode on too (frames
   keep their locals); on a Jolt without jolt.debug the answer is
   {:error s} and nothing changes."
      [con]
      (if @(:break-on? con)
          (do (reset! (:break-on? con) false) {:on? false})
          (let [r (debugger/set-debug-mode! true)]
               (if (:error r)
                   r
                   (do (reset! (:break-on? con) true) {:on? true})))))

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
