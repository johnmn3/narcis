(ns narcis.session
  "One editor's evaluation session: Run and the REPL prompt over one
   narcis.eval context, with the console as a vector of entries the
   page renders. This is jonquil.session's DrRacket semantics — Run
   resets, the REPL continues where the last evaluation left *ns* —
   without jonquil's debugger/trace/world runtime stack, which is a
   later port; the evaluation engine itself is narcis.eval, ported
   whole.

   Reset rule (jonquil.session's): every namespace an evaluation
   creates belongs to this session; a Run removes those namespaces
   and `user`, then evaluates into a fresh `user`. Library namespaces
   (clojure.* and anything that existed when the session was made)
   are never removed.

   Sessions live server-side (see narcis.ops), not in the Eve atom:
   a running evaluation and its half-printed output are ephemeral
   process state, not document state."
  (:require [clojure.string :as str]
            [narcis.eval :as eval]))

(defn make-session
  "A fresh session. Snapshots the namespaces that already exist;
   everything created later is this session's to reset."
  []
  {:ctx (eval/make-eval-context)
   :out (atom [])
   :ns (atom 'user)
   :base-ns (set (map ns-name (all-ns)))
   :owned (atom #{})})

(defn status
  ":running, :waiting-input (the program is blocked on stdin) or :idle."
  [{:keys [ctx]}]
  (cond (eval/waiting-for-input? ctx) :waiting-input
        (eval/evaluating? ctx) :running
        :else :idle))

(defn prompt-ns [s] @(:ns s))

(defn- append! [s kind text]
  (when (and text (not (str/blank? text)))
    (swap! (:out s) conj {:kind kind :text text}))
  nil)

(defn console
  "Console entries from index `since`, with the total count, the
   session status and the prompt namespace."
  [s since]
  (let [entries @(:out s)
        n (count entries)]
    {:entries (subvec entries (min since n))
     :total n
     :status (status s)
     :ns (prompt-ns s)}))

(defn- track-namespaces!
  "After an evaluation: namespaces that appeared since the snapshot
   (and are not libraries) become session-owned; the prompt follows
   the evaluation's resulting *ns*."
  [s result]
  (let [now (set (map ns-name (all-ns)))
        created (remove (:base-ns s) now)
        created (remove #(str/starts-with? (str %) "clojure.") created)]
    (swap! (:owned s) into created))
  (when (:ns result)
    (reset! (:ns s) (:ns result))))

(defn- reset-namespaces!
  "Removes the session's namespaces and `user` (jonquil.session's
   reset). Evaluations recreate what they need."
  [s]
  (doseq [n (conj @(:owned s) 'user)]
    (when (find-ns n)
      (remove-ns n)))
  (reset! (:owned s) #{})
  (reset! (:ns s) 'user))

(defn- callbacks
  "The eval-async callbacks that feed the console. print-result?
   distinguishes the REPL (results are shown) from Run (definitions
   are quiet; only errors and interruptions show)."
  [s print-result?]
  {:on-output (fn [kind chunk] (append! s kind chunk))
   :on-complete
   (fn [result]
     (track-namespaces! s result)
     (case (:status result)
       :ok (when print-result?
             (append! s :result (:printed result)))
       :error (append! s :error (eval/format-error (:error result)))
       :interrupted (append! s :system "Evaluation interrupted")
       nil))})

(defn run!
  "Run = reset, clear the console, evaluate `code` (a buffer's whole
   text) with `source-path` as its file. Answers {:ok true} or
   {:ok false :error} when an evaluation is already in progress."
  [s code source-path]
  (if (eval/evaluating? (:ctx s))
    {:ok false :error "an evaluation is already in progress; Stop it first"}
    (do (reset-namespaces! s)
        (reset! (:out s) [])
        (append! s :system (str "Run: " (or source-path "buffer")))
        (eval/eval-async (:ctx s) code
                         (assoc (callbacks s false)
                                :ns-name 'user
                                :quiet true
                                :source-path source-path))
        {:ok true})))

(defn repl!
  "Evaluates one REPL input in the session's prompt namespace. The
   input is echoed to the console as the prompt line; the result (or
   error) follows when the evaluation completes."
  [s input]
  (if (eval/evaluating? (:ctx s))
    {:ok false :error "an evaluation is already in progress; Stop it first"}
    (do (append! s :in (str (prompt-ns s) "=> " input))
        (eval/eval-async (:ctx s) input
                         (assoc (callbacks s true)
                                :ns-name (prompt-ns s)))
        {:ok true})))

(defn stop!
  "Asks the running evaluation to stop (narcis.eval/stop-eval!)."
  [s]
  (eval/stop-eval! (:ctx s))
  {:ok true})

(defn push-stdin!
  "Feeds one line to a program blocked reading stdin."
  [s text]
  (eval/push-stdin! (:ctx s) text)
  {:ok true})
