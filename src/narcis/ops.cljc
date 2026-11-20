(ns narcis.ops
  "Editor ops over narcis.buffer: the one dispatch the HTTP server
   (narcis.launcher) and the headless gate tests both drive, so what
   the browser does and what the gates measure are the same code.

   Every op takes the editor handle and a message map
   {:op kw :path p ...} and returns
   {:ok true :caret n :sel [a b]|nil :undo n :redo n :rev n} or
   {:ok false :error \"...\"}. A text edit is ONE swap! on the buffer's
   Eve atom (narcis.buffer/edit!): the op's work on the document runs
   inside the swap, in the atom's own domain, and lands as one new root
   — one undo step. Caret and selection moves are one swap too.
   Nothing here knows about HTTP or the page."
  (:require [narcis.buffer :as buffer]
            [narcis.commands :as commands]
            [narcis.docs :as docs]
            [narcis.edit :as edit]
            [narcis.nav :as nav]
            [narcis.structural :as structural]
            [narcis.syntax :as syntax]
            [narcis.text :as text]
            [narcis.theme :as theme]
            [narcis.view :as view]
            [clojure.string :as str]))

(defn- result [ed path]
  (if-let [s (buffer/snapshot ed path)]
    {:ok true
     :caret (:cursor s)
     :sel (:sel s)
     :undo (:undo s)
     :redo (:redo s)
     :rev (buffer/rev ed path)}
    {:ok true :rev (buffer/rev ed)}))

;; ---------------------------------------------------------------------------
;; where the client says the caret is
;;
;; A message may carry the client's current :caret/:sel (the page moves
;; the caret locally for responsiveness and sends it with the next op,
;; so an edit always lands where the user sees the caret). The op reads
;; them in place of the stored ones — inside its own swap, not in a
;; swap of their own.

(defn- clamp [doc offset] (max 0 (min offset (text/chars doc))))

(defn- valid-sel
  "sel when it is a non-empty range inside doc, else nil."
  [doc sel]
  (when (and (sequential? sel) (= 2 (count sel)))
    (let [a (clamp doc (first sel)) b (clamp doc (second sel))]
      (when (< a b) [a b]))))

(defn- at
  "[caret sel] the op acts at: the message's when it carries a caret,
   else the stored ones."
  [doc msg cursor sel]
  (if (contains? msg :caret)
    [(clamp doc (:caret msg)) (valid-sel doc (:sel msg))]
    [(clamp doc cursor) (valid-sel doc sel)]))

;; ---------------------------------------------------------------------------
;; the text an op can see

(defn- region
  "[text base] for an op at caret: the whole buffer at or under
   view/exact-limit chars (the painter's own string, remembered under
   key); above it, the lines from the top-level form at or above the
   caret's line (view/aligned-start, as the painter begins) to 300
   lines past it (an op and a motion clamp to that slice)."
  [doc key caret]
  (if (<= (text/chars doc) view/exact-limit)
    [(:text (view/analysis key doc)) 0]
    (let [line (text/offset->line doc caret)
          from (view/aligned-start doc line)
          to (min (text/lines doc) (+ line 301))
          base (text/line-start-offset doc from)
          end (text/line-start-offset doc to)]
      [(text/slice-string doc base end) base])))

;; ---------------------------------------------------------------------------
;; edits

(declare preparing)

(defn- extent-edit-op
  "edit-op on a partial buffer. The op's target — its selection,
   or its caret — is in document coordinates, the frame the
   whole file will have; when ONE extent's document covers it,
   the edit runs on that document, in its own coordinates, and
   lands as one swap on the extent (buffer/edit-extent!): the
   stretch the reader navigated to is editable while the rest
   of the file is still arriving. A target no extent covers —
   the unhydrated skeleton, the root's prefix, a straddle of
   an extent's edge — keeps the partial buffer's answer,
   :preparing. An edit that does not apply inside the extent
   (a backspace at its first char, which would reach into the
   skeleton) answers why, as it would at the start of any
   document."
  [ed path msg why f]
  (let [[cursor ssel] (buffer/read ed path (fn [{c :cursor s :sel}] [c s]))
        caret (if (contains? msg :caret) (:caret msg) cursor)
        sel (if (contains? msg :sel) (:sel msg) ssel)
        sel (when (and (sequential? sel) (= 2 (count sel))
                       (< (first sel) (second sel)))
              sel)
        [a b] (if sel sel [caret caret])
        cover (when (and (integer? a) (integer? b))
                (buffer/covering-extent ed path a b))]
    (if-not cover
      (preparing ed path)
      (let [[s e] cover
            base (:start-char e)
            applied (buffer/edit-extent!
                     ed path s
                     (fn [edoc]
                       (let [lcaret (- caret base)
                             lsel (when sel [(- (first sel) base)
                                             (- (second sel) base)])
                             r (f edoc lcaret lsel
                                  (delay (region edoc
                                                 [:extent path s (:chars edoc)]
                                                 lcaret)))]
                         (when r (assoc r :caret (get r :caret lcaret))))))]
        (cond (= applied ::gone) (preparing ed path)
              applied (merge (result ed path)
                             (select-keys applied [:copy]))
              :else {:ok false :error why})))))

(defn- edit-op
  "Run f as one edit of path: (f doc caret sel region) -> {:doc :caret
   :sel} or nil, where region is a delay of the text the op can see.
   why is the answer's :error when f does not apply. On a partial
   buffer the edit runs on an extent when one covers the op's
   target (extent-edit-op); the whole-text commands never reach
   here on a partial buffer — the dispatch refuses them first."
  [ed path msg why f]
  (if (buffer/partial? ed path)
    (extent-edit-op ed path msg why f)
    (let [applied (buffer/edit! ed path
                                (fn [doc cursor sel key]
                                  (let [[caret sel] (at doc msg cursor sel)]
                                    (f doc caret sel (delay (region doc key caret))))))]
      (if applied
        ;; an op may hand the page something beside the new state: :copy is
        ;; text for the clipboard (Kill Form, Copy Form)
        (merge (result ed path) (select-keys applied [:copy]))
        {:ok false :error why}))))

(defn- insert-at
  "The plain insert of s at caret, replacing sel when there is one."
  [doc caret sel s]
  (let [n (count (text/normalize-breaks s))]
    (if sel
      (let [[a b] sel]
        {:doc (text/replace doc a b s) :caret (+ a n) :sel nil})
      {:doc (text/insert doc caret s) :caret (+ caret n) :sel nil})))

(def ^:private open-chars #{\( \[ \{ \"})
(def ^:private close-chars #{\) \] \}})

(defn- type-char [doc caret sel region ch]
  (or (cond
        (contains? open-chars ch) (edit/insert-open-doc doc @region caret sel ch)
        (and (nil? sel) (contains? close-chars ch)) (edit/insert-close-doc doc @region caret nil ch))
      (insert-at doc caret sel (str ch))))

;; ---------------------------------------------------------------------------
;; structural editing and formatting — jonquil.structural's and
;; jonquil.commands' ops (lifted), each a fn of a snapshot

(def structural-ops
  "The structural commands by name: what {:op :structural :name n} runs."
  {"slurp-forward" structural/slurp-forward
   "barf-forward" structural/barf-forward
   "slurp-backward" structural/slurp-backward
   "barf-backward" structural/barf-backward
   "raise" structural/raise
   "splice" structural/splice
   "wrap-round" structural/wrap-round
   "wrap-square" structural/wrap-square
   "wrap-curly" structural/wrap-curly
   "wrap-set" structural/wrap-set
   "wrap-quote" structural/wrap-quote
   "rewrap-round" structural/rewrap-round
   "rewrap-square" structural/rewrap-square
   "rewrap-curly" structural/rewrap-curly
   "rewrap-set" structural/rewrap-set
   "drag-backward" structural/drag-backward
   "drag-forward" structural/drag-forward
   "transpose" structural/transpose
   "join" structural/join
   "split" structural/split
   "convolute" structural/convolute
   "kill-form" structural/kill-form
   "kill-form-backward" structural/kill-form-backward
   "kill-right" structural/kill-right
   "kill-left" structural/kill-left
   "kill-list-forward" structural/kill-list-forward
   "kill-list-backward" structural/kill-list-backward
   "splice-killing-forward" structural/splice-killing-forward
   "splice-killing-backward" structural/splice-killing-backward
   "copy-form" structural/copy-form
   "expand-selection" structural/expand-selection
   "shrink-selection" structural/shrink-selection
   "select-forward" structural/select-forward
   "select-backward" structural/select-backward
   "forward" structural/forward
   "backward" structural/backward
   "up" structural/up
   "down" structural/down
   "forward-up" structural/forward-up
   "backward-down" structural/backward-down
   "list-start" structural/list-start
   "list-end" structural/list-end})

(defn- snapshot-op
  "Run op-fn (a fn of a snapshot, as narcis.edit's and
   narcis.structural's are) over the text the op can see, as one edit."
  [ed path msg why op-fn]
  (edit-op ed path msg why
           (fn [doc caret sel region]
             (edit/run-op doc @region caret sel op-fn :all))))

(def whole-text-limit
  "A command that must read the whole text (Rename Symbol, Format
   Buffer) reads buffers up to this many chars."
  4000000)

(defn- whole-text-op
  "Run op-fn over the buffer's WHOLE text as one edit: for the commands
   whose answer depends on all of it. They are asked for by name, not
   run on every keystroke, so they pay for the text."
  [ed path msg why op-fn]
  (if (> (:chars (buffer/snapshot ed path)) whole-text-limit)
    {:ok false :error "this command reads the whole text; the buffer is too large for it"}
    (edit-op ed path msg why
             (fn [doc caret sel _]
               (edit/run-op doc (edit/region-of doc) caret sel op-fn :all)))))

;; ---------------------------------------------------------------------------
;; completion and documentation — read-only

(defn- reading
  "(f text base caret) over the text an op at the message's caret sees,
   read in place; f answers plain data."
  [ed path msg f]
  (buffer/read ed path
               (fn [{doc :root key :text-key cursor :cursor ssel :sel}]
                 (let [[caret _] (at doc msg cursor ssel)
                       [text base] (region doc key caret)]
                   (f text base caret)))))

(defn- buffer-ns-sym
  "The buffer's (ns ...) name as a symbol, or 'user when it has none:
   completion and documentation are looked up in that namespace
   (narcis.docs), so what a Run defined there is found; a namespace that
   does not exist yet reads as `user`."
  [text]
  (if-let [m (re-find #"\(ns\s+([a-zA-Z0-9_.\-]+)" (subs text 0 (min (count text) 4000)))]
    (symbol (second m))
    'user))

(defn- complete-op
  "The candidates for the word before the caret: {:prefix s :start n
   :end n :candidates [{:symbol s :category c} ...]} (offsets into the
   buffer; :end is where an accepted candidate's replacement ends)."
  [ed path msg]
  (reading ed path msg
           (fn [text base caret]
             (let [{:keys [prefix start word-end]} (syntax/get-symbol-prefix-at-pos text (- caret base))
                   cands (docs/candidates (buffer-ns-sym text)
                                          {:prefix prefix :text text :pos (- caret base)})]
               {:ok true
                :prefix prefix
                :start (+ base start)
                :end (+ base word-end)
                :candidates (mapv (fn [c] {:symbol (str (:symbol c)) :category (name (:category c))})
                                  (take 200 cands))}))))

(defn- doc-op
  "The documentation of a symbol — the message's :symbol, else the one at
   the caret — as the completion popup and Docs for the Symbol show it."
  [ed path msg]
  (reading ed path msg
           (fn [text base caret]
             (let [sym (or (:symbol msg)
                           (let [{:keys [start word-end]} (syntax/get-symbol-prefix-at-pos text (- caret base))]
                             (when (< start word-end) (subs text start word-end))))]
               (if (str/blank? sym)
                 {:ok false :error "no symbol there"}
                 {:ok true
                  :symbol sym
                  :doc (syntax/format-autocomplete-doc (docs/symbol-doc (buffer-ns-sym text) sym text (- caret base))
                                                       {:symbol sym
                                                        :category (some-> (:category msg) keyword)})})))))

;; ---------------------------------------------------------------------------
;; caret moves and finds

(defn- move-caret! [ed path offset sel]
  (buffer/set-caret! ed path offset sel)
  (result ed path))

;; A find reads the document a slice at a time — never the whole text —
;; so it works on a buffer of any size. Slices overlap by the query's
;; length less one, so a match across a slice's edge is found.

(def ^:private find-slice 65536)

(defn- find-forward
  "Start of the first match of query at or after offset from, or nil."
  [doc query from]
  (let [q (count query) n (text/chars doc)]
    (loop [pos (max 0 from)]
      (when (<= (+ pos q) n)
        (let [end (min n (+ pos find-slice q -1))
              i (str/index-of (text/slice-string doc pos end) query)]
          (cond i (+ pos i)
                (>= end n) nil
                :else (recur (+ pos find-slice))))))))

(defn- find-backward
  "Start of the last match of query that ends at or before offset to, or nil."
  [doc query to]
  (let [q (count query)]
    (loop [end (min to (text/chars doc))]
      (when (>= end q)
        (let [start (max 0 (- end find-slice q -1))
              i (str/last-index-of (text/slice-string doc start end) query)]
          (cond i (+ start i)
                (zero? start) nil
                ;; the next slice ends where a match could still end
                :else (recur (+ start q -1))))))))

(defn- find-in-doc
  "[start end] of the match for a find from offset `from`: direction
   :next searches forward, :prev backward; wraps once when wrap?."
  [doc query from dir wrap?]
  (let [q (count query)]
    (when (pos? q)
      (when-let [i (case dir
                     :next (or (find-forward doc query from)
                               (when wrap? (find-forward doc query 0)))
                     :prev (or (find-backward doc query from)
                               (when wrap? (find-backward doc query (text/chars doc)))))]
        [i (+ i q)]))))

(defn- find-op [ed path msg dir]
  (let [query (some-> (:query msg) text/normalize-breaks)]
    (if (or (nil? query) (zero? (count query)))
      {:ok false :error "empty find query"}
      (let [wrap? (not (false? (:wrap msg)))
            m (if (buffer/partial? ed path)
                ;; a partial buffer's document is its file's prefix,
                ;; so the find runs over the file itself — the whole
                ;; text is there even where no document is yet
                (let [[cursor ssel] (buffer/read ed path (fn [{c :cursor s :sel}] [c s]))
                      caret (if (contains? msg :caret) (:caret msg) cursor)
                      sel (if (contains? msg :sel) (:sel msg) ssel)
                      from (or (:from msg)
                               (case dir :next caret :prev (if sel (first sel) caret)))
                      i (case dir
                          :next (or (text/file-find-forward path query from)
                                    (when wrap? (text/file-find-forward path query 0)))
                          :prev (or (text/file-find-backward path query from)
                                    (when wrap? (text/file-find-backward path query nil))))]
                  (when i [i (+ i (count query))]))
                (buffer/read ed path
                             (fn [{doc :root cursor :cursor ssel :sel}]
                               (let [[caret sel] (at doc msg cursor ssel)
                                     from (or (:from msg)
                                              (case dir :next caret :prev (if sel (first sel) caret)))]
                                 (find-in-doc doc query from dir wrap?)))))]
        (if m
          (assoc (move-caret! ed path (second m) m) :match m)
          {:ok false :error (str "not found: " query)})))))

(defn- sexp-op [ed path msg move]
  (move-caret! ed path
               (reading ed path msg (fn [text base caret] (+ base (move text (- caret base)))))
               nil))

;; ---------------------------------------------------------------------------
;; files

(defn- save! [ed path to]
  (buffer/read ed path (fn [{doc :root eol :eol}] (text/spit-file! to doc eol) true)))

(defn- fresh-name [ed]
  ;; A loop, not a filter over candidates: the first call of a
  ;; closure that enters the buffer read path from inside a lazy
  ;; seq costs ~30 s on jolt (measured: filter with an open?
  ;; predicate 33 s, open? alone 0.08 s, the same call after the
  ;; first 0 s — probe-newatom9). No ad-hoc closure calls into the
  ;; read path from a seq fn anywhere; loops call it directly.
  (loop [i 0]
    (when (< i 1000)
      (let [p (if (zero? i) "untitled" (str "untitled-" (inc i)))]
        (if (buffer/open? ed p)
          (recur (inc i))
          p)))))

;; ---------------------------------------------------------------------------
;; the dispatch

(def ^:private load-safe-ops
  "The ops a partial buffer answers: the ones that neither change
   its document nor write it out — they run over the document it
   has, or over the file itself (find). The rest need the whole
   document: they start its materialization and wait for it — an
   edit would race the loader's appends, and a save would write a
   partial document over the file."
  #{:open :activate :close :new :theme/use :find-next :find-prev
    :complete :doc :forward-sexp :backward-sexp :set-caret})

(def ^:private extent-editable-ops
  "The text-edit ops: the ones that run through edit-op, on a
   document and its caret. On a partial buffer these are the
   ops an extent can answer — when the op's target sits inside
   one extent, the edit runs on that extent's document (see
   extent-edit-op). The whole-text commands are not here (they
   read the whole text by definition), and neither are saves
   or undo (the histories hold root documents)."
  #{:insert-text :type-char :smart-enter :indent :unindent
    :toggle-comment :backspace :delete-forward :structural})

(defn- extent-editable?
  "True when op is a text-edit op an extent can answer: one of
   extent-editable-ops, or a structural command sent by name."
  [op]
  (or (contains? extent-editable-ops op)
      (and (keyword? op) (contains? structural-ops (name op)))))

(defn- preparing
  "A partial buffer's answer to an op that needs the whole
   document: the materialization is started (or continued)
   and the answer says how far in it is."
  [ed path]
  (buffer/materialize! ed path)
  {:ok false :preparing true
   :error (str path " is a partial document — preparing it whole — "
               (let [{:keys [loaded total]} (get (buffer/loading-map ed) path)]
                 (if (and loaded total (pos? total))
                   (str (quot (* 100 loaded) total) "% in")
                   "almost in")))})

(defn apply-op
  "Apply one op message to the editor. See the ns docstring for the
   result shape. Unknown ops and inapplicable structural ops answer
   {:ok false :error ...} rather than throwing, except programming
   errors, which propagate."
  [ed {:keys [op path] :as msg}]
  (let [path (or path (buffer/active ed))]
    (if (and (not (#{:open :activate :new :theme/use} op)) (not (buffer/open? ed path)))
      {:ok false :error (str "no such buffer: " path)}
      (if (and (not (load-safe-ops op)) (buffer/partial? ed path)
               (not (extent-editable? op)))
        (preparing ed path)
        (case op
        :open (do (buffer/open-file! ed (:path msg)) (result ed (:path msg)))
        :activate (do (buffer/activate! ed path) (result ed path))
        :close (do (buffer/close-buffer! ed path)
                   (assoc (result ed (buffer/active ed)) :path (buffer/active ed)))
        :new (let [p (fresh-name ed)]
               (buffer/open-buffer! ed p "")
               (assoc (result ed p) :path p))
        :theme/use (let [choice (keyword (:name msg))]
                     (if (theme/choice? choice)
                       (do (buffer/set-theme! ed choice)
                           {:ok true :theme (name choice)
                            :resolved (name (theme/resolve-choice choice (boolean (:system-dark msg))))
                            :rev (buffer/rev ed)})
                       {:ok false :error (str "unknown theme: " (:name msg))}))
        :save-as (let [np (:new-path msg)]
                   (cond
                     (or (nil? np) (str/blank? np)) {:ok false :error "save-as needs a new path"}
                     (= np path) {:ok false :error "save-as path is the current path"}
                     (buffer/open? ed np) {:ok false :error (str "already open: " np)}
                     :else (do (save! ed path np)
                               ;; the new buffer is the saved file opened: its own
                               ;; document in its own slot (two slots never hold
                               ;; one tree)
                               (buffer/open-file! ed np)
                               (buffer/close-buffer! ed path)
                               (assoc (result ed np) :path np))))
        :save (do (save! ed path path) (result ed path))
        :find-next (find-op ed path msg :next)
        :find-prev (find-op ed path msg :prev)

        :insert-text (edit-op ed path msg "nothing to insert"
                              (fn [doc caret sel _]
                                (when (or sel (pos? (count (:text msg))))
                                  (insert-at doc caret sel (or (:text msg) "")))))
        :type-char (edit-op ed path msg "no character"
                            (fn [doc caret sel region]
                              (when-let [ch (first (:ch msg))]
                                (type-char doc caret sel region ch))))
        :smart-enter (edit-op ed path msg "smart-enter does not apply"
                              (fn [doc caret sel region]
                                (edit/smart-enter-doc doc @region caret sel)))
        :indent (edit-op ed path msg "op does not apply"
                         (fn [doc caret sel region] (edit/indent-doc doc @region caret sel)))
        :unindent (edit-op ed path msg "op does not apply"
                           (fn [doc caret sel region] (edit/unindent-doc doc @region caret sel)))
        :toggle-comment (edit-op ed path msg "op does not apply"
                                 (fn [doc caret sel region] (edit/toggle-comment-doc doc @region caret sel)))
        :backspace (edit-op ed path msg "start of buffer"
                            (fn [doc caret sel region]
                              (cond
                                sel {:doc (text/delete doc (first sel) (second sel)) :caret (first sel) :sel nil}
                                :else (or (edit/backspace-pair-doc doc @region caret nil)
                                          (when (pos? caret)
                                            {:doc (text/delete doc (dec caret) caret) :caret (dec caret) :sel nil})))))
        :delete-forward (edit-op ed path msg "end of buffer"
                                 (fn [doc caret sel _]
                                   (cond
                                     sel {:doc (text/delete doc (first sel) (second sel)) :caret (first sel) :sel nil}
                                     (< caret (text/chars doc))
                                     {:doc (text/delete doc caret (inc caret)) :caret caret :sel nil})))
        :structural (if-let [f (get structural-ops (:name msg))]
                      (snapshot-op ed path msg "does not apply here" f)
                      {:ok false :error (str "unknown structural op: " (:name msg))})
        :format-all (whole-text-op ed path msg "already formatted" commands/format-all-op)
        :format-selection (whole-text-op ed path msg "already formatted" commands/format-selection-op)
        :complete (complete-op ed path msg)
        :doc (doc-op ed path msg)
        :forward-sexp (sexp-op ed path msg nav/forward-sexp)
        :backward-sexp (sexp-op ed path msg nav/backward-sexp)
        :undo (if (buffer/undo! ed path)
                (result ed path)
                {:ok false :error "nothing to undo"})
        :redo (if (buffer/redo! ed path)
                (result ed path)
                {:ok false :error "nothing to redo"})
        :set-caret (let [n (:chars (buffer/snapshot ed path))
                         in (fn [x] (max 0 (min n x)))
                         sel (when (and (sequential? (:sel msg)) (= 2 (count (:sel msg))))
                               (let [a (in (first (:sel msg))) b (in (second (:sel msg)))]
                                 (when (< a b) [a b])))]
                     ;; :offset, or the caret every op of the page carries
                     (move-caret! ed path (in (or (:offset msg) (:caret msg) 0)) sel))
        :rename (whole-text-op ed path msg "no symbol to rename there"
                               (fn [s] (edit/rename s (:name msg))))
        ;; a structural command may be sent by its own name
        (if-let [f (and (keyword? op) (get structural-ops (name op)))]
          (snapshot-op ed path msg "does not apply here" f)
          {:ok false :error (str "unknown op: " op)}))))))
