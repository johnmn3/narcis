(ns narcis.edit
  "Pure editor operations. Each op takes a snapshot
  {:text :caret :sel-start :sel-end} and returns
  {:edits [[start end replacement] ...] :caret n :sel [a b]}, or nil when the
  op does not apply. Offsets are character offsets into :text, edits are
  non-overlapping and listed in ascending order, and the front-end applies them
  back to front inside one undoable user action.
  Narcis lift (2026-11-16): from jonquil.edit @0ac90991 (b12n-oss/jonquil,
  EPL-2.0; see UPSTREAM.md and NOTICE in this repo), verbatim apart from the
  namespace. The document layer at the bottom of this file is Narcis's
  own: it builds snapshots from a region of the buffer's document and
  applies an op's :edits to the document as ONE new root (one undo step
  in narcis.buffer)."
  (:require [clojure.string :as str]
            [narcis.text :as text]
            [narcis.syntax :as syntax]))

(def open->close {\( \), \[ \], \{ \}})

(defn apply-edits
  "Applies :edits to text in one pass: they are ascending and don't
  overlap, so the text between them is copied once (2,000 edits on 46k
  characters measured 149 ms by one str per edit, 0 ms this way,
  2026-11-03). The test oracle for every op, and what a front-end without
  native edits would do."
  [^String text edits]
  (let [sb (StringBuilder.)
        end (reduce (fn [from [s e r]]
                      (.append sb text from s)
                      (.append sb ^String r)
                      e)
                    0
                    edits)]
    (.append sb text end (count text))
    (str sb)))

(defn smart-enter
  "Upstream ui.clj handle-smart-enter! (41c56b8) as data. Replaces the
  selection with a newline; between an empty pair opens an indented middle
  line with the closer on its own line; otherwise indents by
  syntax/compute-smart-indent measured with the selection removed.
  {:between-pair? false} skips the pair branch: with Parinfer on the
  closer is Parinfer's to manage, as upstream's handle-smart-enter! has it."
  ([snap] (smart-enter snap {}))
  ([{:keys [text sel-start sel-end]} {:keys [between-pair?] :or {between-pair? true}}]
   (let [text' (str (subs text 0 sel-start) (subs text sel-end))
         pos sel-start
         line-start (inc (or (str/last-index-of text' "\n" (dec pos)) -1))
         line-prefix (subs text' line-start pos)
         prev-ch (when (pos? pos) (nth text' (dec pos)))
         next-ch (when (< pos (count text')) (nth text' pos))
         base-indent (or (re-find #"^[ ]+" line-prefix) "")]
     (if (and between-pair? prev-ch next-ch (= (open->close prev-ch) next-ch))
       (let [inner (str base-indent "  ")]
         {:edits [[sel-start sel-end (str "\n" inner "\n" base-indent)]]
          :caret (+ pos 1 (count inner))
          :sel nil})
       (let [ins (str "\n" (syntax/compute-smart-indent text' pos))]
         {:edits [[sel-start sel-end ins]]
          :caret (+ pos (count ins))
          :sel nil})))))

;; --- Line ranges shared by indent, unindent and toggle-comment ---
;;
;; Upstream's indent-selection!, unindent-selection! and toggle-comment! each
;; carry their own copy (via Swing's root Element / getElementIndex) of "which
;; lines does this selection span, and does the selection merely touch the
;; next line's own start without entering it". This is that logic once, in
;; character offsets instead of Swing elements.

(defn- line-starts
  "Ascending vector of the character offset each line of `text` starts at,
  always including 0."
  [^String text]
  (loop [from 0 acc [0]]
    (if-let [idx (str/index-of text "\n" from)]
      (recur (inc idx) (conj acc (inc idx)))
      acc)))

(defn- line-idx-at
  "Index into `starts` of the line containing character offset `pos`."
  [starts pos]
  (loop [i (dec (count starts))]
    (if (<= (nth starts i) pos) i (recur (dec i)))))

(defn- line-span
  "[start end] character offsets of line `idx`'s content, excluding its
  trailing newline."
  [text starts idx]
  (let [s (nth starts idx)
        e (if (< (inc idx) (count starts)) (dec (nth starts (inc idx))) (count text))]
    [s e]))

(defn- selection-lines
  "[start-line end-line starts]: the inclusive line-index range `sel-start`
  to `sel-end` spans. When the selection is non-empty and its end lands
  exactly on a line's own start offset, that line is excluded -- mirrors
  upstream's exclude-if-getStartOffset-equals-sel-end guard, so selecting up
  to (but not past) a line boundary doesn't drag the next line in."
  [text sel-start sel-end]
  (let [starts (line-starts text)
        start-line (line-idx-at starts sel-start)
        end-raw (line-idx-at starts sel-end)
        end-line (if (and (> sel-end sel-start) (= sel-end (nth starts end-raw)))
                   (dec end-raw)
                   end-raw)]
    [start-line end-line starts]))

(defn- shift-offset
  "Maps character offset `pos` in the original text to its offset after
  ascending, non-overlapping `edits` are applied. A pos at or after an edit's
  end shifts by that edit's length delta; a pos strictly inside an edit's
  span snaps to the end of that edit's replacement. Every op below that
  moves a caret or selection after editing multiple lines uses this, since
  upstream's own per-function arithmetic (new-start = sel-start + n, and so
  on) is this same rule applied by hand."
  [edits pos]
  (reduce (fn [p [s e r]]
            (cond
              (< pos s) p
              (< pos e) (+ p (- s pos) (count r))
              :else (+ p (- (count r) (- e s)))))
          pos
          edits))

(defn indent
  "Upstream ui.clj indent-selection! (41c56b8). No selection: two spaces at
  the caret, like typing them. A selection: two spaces at the start of every
  line it spans."
  [{:keys [text caret sel-start sel-end]}]
  (if (= sel-start sel-end)
    {:edits [[caret caret "  "]] :caret (+ caret 2) :sel nil}
    (let [[start-line end-line starts] (selection-lines text sel-start sel-end)
          edits (mapv (fn [idx] [(nth starts idx) (nth starts idx) "  "])
                      (range start-line (inc end-line)))]
      {:edits edits
       :caret (shift-offset edits sel-end)
       :sel [(shift-offset edits sel-start) (shift-offset edits sel-end)]})))

(defn unindent
  "Upstream ui.clj unindent-selection! (41c56b8). Strips up to one leading
  tab or two leading spaces from every line the selection spans (or just the
  caret's line, with no selection). nil when no spanned line has any
  matching leading whitespace to remove."
  [{:keys [text caret sel-start sel-end]}]
  (let [[start-line end-line starts] (selection-lines text sel-start sel-end)
        removals (keep (fn [idx]
                         (let [[s e] (line-span text starts idx)
                               line (subs text s e)]
                           (when-let [m (re-find #"^(?:[ ]{1,2}|\t)" line)]
                             [(+ s (count m)) (count m)])))
                       (range start-line (inc end-line)))]
    (when (seq removals)
      (let [edits (mapv (fn [[end n]] [(- end n) end ""]) removals)]
        (if (= sel-start sel-end)
          {:edits edits :caret (shift-offset edits caret) :sel nil}
          {:edits edits
           :caret (shift-offset edits sel-end)
           :sel [(shift-offset edits sel-start) (shift-offset edits sel-end)]})))))

(defn- comment-edits
  "Edits for toggle-comment over lines ([line-start text] pairs): strip the
  comment marker when every non-blank line has one, else add \"; \" to each
  non-blank line."
  [lines]
  (let [non-blank (remove (fn [[_ t]] (str/blank? t)) lines)
        all-commented? (and (seq non-blank)
                            (every? (fn [[_ t]] (boolean (re-find #"^[ ]*;+" t))) non-blank))]
    (if all-commented?
      (keep (fn [[ls t]]
              (when-let [m (re-find #"^([ ]*)(;+[ ]?)" t)]
                (let [lead (count (nth m 1)) chars (count (nth m 2))]
                  [(+ ls lead) (+ ls lead chars) ""])))
            lines)
      (keep (fn [[ls t]] (when-not (str/blank? t) [ls ls "; "])) lines))))

(defn toggle-comment
  "Upstream ui.clj toggle-comment! (41c56b8): comments every non-blank line
  the selection spans (or the caret's line) with a leading \"; \", or, when
  every non-blank spanned line is already commented, strips one leading
  \";\" run (plus at most one following space) from each. Upstream never
  repositions the caret or selection itself -- Swing's own default
  insert/remove caret tracking does that -- so caret and :sel here are
  computed with the same shift-offset every other multi-line op uses."
  [{:keys [text caret sel-start sel-end]}]
  (let [[start-line end-line starts] (selection-lines text sel-start sel-end)
        lines (mapv (fn [idx]
                      (let [[s e] (line-span text starts idx)]
                        [(nth starts idx) (subs text s e)]))
                    (range start-line (inc end-line)))
        edits (comment-edits lines)]
    (when (seq edits)
      (let [edits (vec edits)]
        {:edits edits
         :caret (shift-offset edits caret)
         :sel (when (not= sel-start sel-end)
                [(shift-offset edits sel-start) (shift-offset edits sel-end)])}))))

;; --- Auto-brackets ---
;;
;; Upstream's setup-auto-brackets! (41c56b8) matches on one open->matching-close
;; map that includes the quote pair (\" -> \"), which smart-enter's own
;; open->close above does not need and does not have -- so this is a second,
;; separate map rather than widening the prescribed one.

(def ^:private bracket-pairs {\( \), \[ \], \{ \}, \" \"})

(defn insert-open
  "Upstream setup-auto-brackets! keyTyped (41c56b8), the open-bracket/quote
  branch: wraps a selection in ch and its closer, steps a same quote over an
  existing one instead of nesting, or inserts a fresh empty pair with the
  caret in the middle. nil when ch isn't one of ( [ { \"."
  [{:keys [text caret sel-start sel-end]} ch]
  (when (contains? bracket-pairs ch)
    (let [close (bracket-pairs ch)]
      (cond
        (< sel-start sel-end)
        (let [wrapped (str ch (subs text sel-start sel-end) close)
              edits [[sel-start sel-end wrapped]]]
          {:edits edits
           :caret (+ sel-start (count wrapped))
           :sel [sel-start (+ sel-start (count wrapped))]})

        (and (= ch \") (< caret (count text)) (= (nth text caret) \"))
        {:edits [[caret (inc caret) "\""]] :caret (inc caret) :sel nil}

        :else
        {:edits [[caret caret (str ch close)]] :caret (inc caret) :sel nil}))))

(def ^:private closers
  ;; ) ] } only -- typing a quote goes through insert-open, whose own
  ;; step-over case already covers the quote pair.
  #{\) \] \}})

(defn insert-close
  "Upstream setup-auto-brackets! keyTyped (41c56b8), the close-delimiter
  branch: typing a closer that's already the next character steps over it
  instead of inserting a second one. nil otherwise -- let GTK insert it."
  [{:keys [text caret sel-start sel-end]} ch]
  (when (and (= sel-start sel-end)
             (contains? closers ch)
             (< caret (count text))
             (= (nth text caret) ch))
    {:edits [[caret (inc caret) (str ch)]] :caret (inc caret) :sel nil}))

(defn backspace-pair
  "Upstream setup-auto-brackets! keyPressed VK_BACK_SPACE (41c56b8): caret
  sitting inside an empty bracket or quote pair deletes both characters
  together. nil otherwise -- let GTK do a plain single-character backspace."
  [{:keys [text caret sel-start sel-end]}]
  (when (and (= sel-start sel-end)
             (pos? caret)
             (< caret (count text))
             (= (bracket-pairs (nth text (dec caret))) (nth text caret)))
    {:edits [[(dec caret) (inc caret) ""]] :caret (dec caret) :sel nil}))

(defn matching-bracket
  "Upstream ui.clj find-matching-bracket (41c56b8) and bracket-matching-test:
  :caret is the bracket's own offset (not a between-characters caret), and
  the return is [pos match] or nil, never an edit."
  [{:keys [text caret]}]
  (when (and (<= 0 caret) (< caret (count text)))
    (when-let [m (get (:matches (syntax/compute-brackets text)) caret)]
      [caret m])))

(def ^:private code-symbol-re #"^[-_a-zA-Z0-9\p{L}\p{N}.!$%&*+/<=>?#]+$")

(defn rename
  "Upstream ui.clj rename-symbol-dialog! (41c56b8), by way of
  syntax/find-symbol-occurrences rather than porting the JOptionPane dialog:
  renames every code occurrence (never inside a comment, string, regex or
  keyword) of the symbol at the selection or caret to new-name. nil when
  there's no symbol at the caret/selection, new-name is blank, isn't a valid
  Clojure symbol, equals the old name, or there are no occurrences to rename
  -- each of those is upstream's own no-op or error-dialog path, and none of
  them has anything to apply."
  [{:keys [text caret sel-start sel-end]} new-name]
  (let [new-name (str/trim (str new-name))
        selected (when (< sel-start sel-end) (str/trim (subs text sel-start sel-end)))
        old-sym (if (and selected (re-matches code-symbol-re selected))
                  selected
                  (:symbol (syntax/symbol-at-pos text caret)))]
    (when (and old-sym
               (not (str/blank? new-name))
               (re-matches code-symbol-re new-name)
               (not= new-name old-sym))
      (let [occs (syntax/find-symbol-occurrences text old-sym)]
        (when (seq occs)
          (let [edits (mapv (fn [[s e]] [s e new-name]) occs)]
            {:edits edits :caret (shift-offset edits caret) :sel nil}))))))

;; ---------------------------------------------------------------------------
;; Narcis document layer (not part of the jonquil lift)
;;
;; The ops above consume a snapshot {:text :caret :sel-start :sel-end}.
;; On a document (narcis.text, an eve.text tree in the buffer's atom)
;; the snapshot's text is the REGION the op can see, handed in by the
;; caller as [text base]: the whole buffer for buffers small enough to
;; analyse whole (the caller has that string already — the painter's),
;; a slice of lines around the caret on a larger one. Scope :form
;; narrows the region to the top-level form enclosing the caret
;; (narcis.syntax brackets); :all keeps the region. The op runs on that
;; text with offsets rebased to it, and its :edits are applied back to
;; the document as one new root.
;;
;; These build a document, so they run where building may: inside the
;; buffer atom's swap! (narcis.buffer/edit!), or under
;; narcis.test-util/with-heap-domain in the tests.

(defn apply-edits-to-doc
  "Apply jonquil-style :edits ([start end replacement] ascending,
   non-overlapping, offsets into the document) to doc, producing ONE
   new root. Equivalent to (apply-edits (text/to-string doc) edits)
   but path-copies only the edited spans."
  [doc edits]
  (loop [doc doc shift 0 edits (seq edits)]
    (if (nil? edits)
      doc
      (let [[s e r] (first edits)
            s (+ s shift) e (+ e shift)]
        (recur (text/replace doc s e r)
               (+ shift (- (count r) (- e s)))
               (next edits))))))

(defn- form-scope
  "Bounds [start end] of the top-level form enclosing offset in text,
   or [0 (count text)] when offset is not inside one. Bracket pairs
   come from narcis.syntax/compute-brackets' :matches map (open ->
   close entries only); the top-level form is the OUTERMOST enclosing
   pair."
  [text offset]
  (let [matches (:matches (syntax/compute-brackets text))
        enclosing (filter (fn [[o c]] (and (< o c) (<= o offset) (< offset c))) matches)
        outer (first (sort-by first enclosing))]
    (if outer
      [(first outer) (inc (second outer))]
      [0 (count text)])))

(defn region-of
  "The whole document as an op's region: [text 0]."
  [doc]
  [(text/to-string doc) 0])

(defn snapshot
  "An op snapshot of region [text base] at caret with selection [a b]
   (or nil), offsets into the document. scope is :all or :form.
   Returns {:snap {...} :base offset-of-the-snapshot-text-in-the-document}."
  [[text rbase] caret sel scope]
  (let [c (- caret rbase)
        [fs fe] (if (= scope :form) (form-scope text c) [0 (count text)])
        base (+ rbase fs)]
    {:base base
     :snap {:text (subs text fs fe)
            :caret (- caret base)
            :sel-start (- (if sel (first sel) caret) base)
            :sel-end (- (if sel (second sel) caret) base)}}))

(defn run-op
  "Run a jonquil edit op (a fn of snapshot -> {:edits :caret :sel} or
   nil) against doc at caret/sel. region is [text base] (see above);
   without one it is the whole document. Returns {:doc new-root
   :caret n :sel [a b] or nil} with offsets rebased to the document, or
   nil when the op does not apply. The new root is ONE edit:
   narcis.buffer records it as one undo step."
  ([doc caret sel op-fn] (run-op doc (region-of doc) caret sel op-fn :all))
  ([doc caret sel op-fn scope] (run-op doc (region-of doc) caret sel op-fn scope))
  ([doc region caret sel op-fn scope]
   (let [{:keys [base snap]} (snapshot region caret sel scope)
         res (op-fn snap)]
     (when res
       (let [edits (mapv (fn [[s e r]] [(+ s base) (+ e base) r]) (:edits res))]
         ;; what the op answered beside its edits (:copy, the clipboard's
         ;; text) rides along; an op with no edits answers doc itself
         (assoc (dissoc res :edits)
                :doc (apply-edits-to-doc doc edits)
                :caret (+ (:caret res) base)
                :sel (when (:sel res)
                       [(+ (first (:sel res)) base) (+ (second (:sel res)) base)])))))))

(defn smart-enter-doc
  ([doc region caret sel] (run-op doc region caret sel (fn [s] (smart-enter s)) :form))
  ([doc region caret sel opts] (run-op doc region caret sel (fn [s] (smart-enter s opts)) :form)))

(defn indent-doc [doc region caret sel] (run-op doc region caret sel indent :form))
(defn unindent-doc [doc region caret sel] (run-op doc region caret sel unindent :form))
(defn toggle-comment-doc [doc region caret sel] (run-op doc region caret sel toggle-comment :form))
(defn insert-open-doc [doc region caret sel ch] (run-op doc region caret sel (fn [s] (insert-open s ch)) :form))
(defn insert-close-doc [doc region caret sel ch] (run-op doc region caret sel (fn [s] (insert-close s ch)) :form))
(defn backspace-pair-doc [doc region caret sel] (run-op doc region caret sel backspace-pair :form))
(defn rename-doc [doc region caret sel new-name] (run-op doc region caret sel (fn [s] (rename s new-name)) :all))
