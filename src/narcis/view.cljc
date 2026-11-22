(ns narcis.view
    "Document window -> render payload: the only text the browser ever sees.

   A window payload carries up to `count` lines starting at a line
   number — each line's text, its char offset, and its paint spans —
   plus buffer totals, so the client can virtualize a billion-line
   buffer from bounded payloads and never holds the text. Spans come
   from narcis.paint/paint-plan (the jonquil.paint lift) over
   narcis.syntax's analysis:

   - Buffers up to exact-limit chars are analysed whole (the plan and
     bracket matches are exact — this is the path the span-correctness
     gate checks against jonquil.syntax directly). The analysis of one
     text is done once and remembered under the buffer's :text-key, so
     scrolling, caret moves and the edit ops that need the text all
     read the same one; an edit makes a new text and one new analysis.
   - Above that, the analysis runs over the window's lines, begun at
     the nearest line above the window that starts a top-level form (a
     line whose first character is an open paren, looked for up to
     align-lines back), so bracket depth inside the window is the
     buffer's own in ordinary code. Tokens are exact from that line
     on; a bracket opened before it is not seen. The payload says
     which mode served it (:spans-mode :exact or :window) so nothing
     silently pretends to be exact.

   Span keys serialize as strings for the wire: a token kind is its
   name (\"comment\", \"keyword\", ...), rainbow depth n is
   \"rainbow-n\", and :unmatched is \"unmatched\". The client resolves
   them through the style table served from narcis.theme/narcis.paint."
    (:require [narcis.paint :as paint]
              [narcis.syntax :as syntax]
              [narcis.text :as text]))

(def exact-limit
     "Buffers at or below this many chars get whole-text paint analysis:
   about 600 lines. An edit makes a new text and the whole text is
   analysed again, which on Jolt costs about 3 µs a character (measured:
   590 ms for 190,000) — so the limit is what keeps a keystroke's
   repaint under a tenth of a second. Larger buffers are analysed a
   window at a time, from the top-level form above it."
     24000)

(def align-lines
     "How far above a window the large-buffer analysis looks for a line
   that starts a top-level form."
     2000)

(defn serialize-key [k]
      (cond (keyword? k) (name k)
            (vector? k) (str "rainbow-" (second k))
            :else (str k)))

;; ---------------------------------------------------------------------------
;; analysis of one text, remembered while that text is current

(defn- analyse-text
       "{:text :tokens :brackets :matches}: the paint plan of text as its two
   runs (token spans, then bracket spans — each ascending by start, the
   brackets painted over the tokens), and its bracket matches."
       [text]
       (let [plan (paint/paint-plan text)
             token? (fn [[k _ _]] (keyword? k))
        ;; paint-plan is the token spans followed by the bracket spans
        ;; (:unmatched is a bracket's key too)
             n (count plan)
             split (loop [i 0]
                         (if (and (< i n) (token? (nth plan i)) (not= :unmatched (first (nth plan i))))
                             (recur (inc i))
                             i))]
            {:text text
             :tokens (subvec plan 0 split)
             :brackets (subvec plan split)
             :matches (:matches (syntax/compute-brackets text))}))

(def ^:private remembered
  ;; the last few analyses, newest first: [{:key text-key ...} ...]
     (atom []))

(defn analysis
      "The analysis of doc's whole text (see analyse-text). key is the
   buffer's :text-key — it names this text and no other — and the
   analysis is remembered under it; with a nil key it is computed and
   not kept."
      [key doc]
      (or (when (some? key)
                (some (fn [a] (when (= key (:key a)) a)) @remembered))
          (let [a (assoc (analyse-text (text/to-string doc)) :key key)]
               (when (some? key)
                     (swap! remembered
                            (fn [as] (vec (take 4 (cons a (remove (fn [x] (= key (:key x))) as)))))))
               a)))

;; ---------------------------------------------------------------------------
;; spans of a window

(defn- first-ending-after
       "Index of the first span of spans (ascending, non-overlapping) whose
   end is past offset: a binary search."
       [spans offset]
       (loop [lo 0 hi (count spans)]
             (if (< lo hi)
                 (let [mid (quot (+ lo hi) 2)]
                      (if (> (nth (nth spans mid) 2) offset)
                          (recur lo mid)
                          (recur (inc mid) hi)))
                 lo)))

(defn- spans-in
       "The spans of one run that touch [from to), in order."
       [spans from to]
       (let [n (count spans)]
            (loop [i (first-ending-after spans from) acc (transient [])]
                  (if (and (< i n) (< (nth (nth spans i) 1) to))
                      (recur (inc i) (conj! acc (nth spans i)))
                      (persistent! acc)))))

(defn- line-spans
       "spans (plan order: a later one wins where two overlap, as
   jonquil.paint/style-at reads them) clipped to the line [ls, ls+llen)
   as [kind c0 c1] in line-relative columns, empties dropped."
       [spans ls llen]
       (let [le (+ ls llen)]
            (into []
                  (keep (fn [[k s e]]
                            (let [cs (max s ls) ce (min e le)]
                                 (when (< cs ce)
                                       [(serialize-key k) (- cs ls) (- ce ls)]))))
                  spans)))

(defn aligned-start
      "The line the large-buffer analysis of a window at line start begins
   on: the nearest line at or above start whose first character is an
   open paren, within align-lines; else start."
      [doc start]
      (loop [hi start]
            (let [lo (max 0 (- hi 200))]
                 (if (or (>= lo hi) (> (- start lo) align-lines))
                     start
                     (let [ls (text/window doc lo (- hi lo))
                           hit (last (filter (fn [l] (let [t (:text l)]
                                                          (and (pos? (count t)) (= \( (.charAt ^String t 0)))))
                                             ls))]
                          (cond hit (:n hit)
                                (zero? lo) 0
                                :else (recur lo)))))))

(defn window
      "Render payload for lines [start, start+count) of doc.
   opts: {:start n :count m :caret offset-or-nil :key text-key-or-nil}."
      [doc {:keys [caret key] :as opts}]
      (let [n (get opts :count 100)
            total-lines (text/lines doc)
            total-chars (text/chars doc)
            start (max 0 (min (get opts :start 0) (max 0 (dec total-lines))))
            rows (text/window doc start n)
            ws (if (seq rows) (:start (first rows)) total-chars)
            we (if (seq rows)
                   (let [l (peek rows)] (+ (:start l) (count (:text l))))
                   total-chars)
            exact? (<= total-chars exact-limit)
        ;; base: where the analysed text sits in the document
            [a base] (if exact?
                         [(analysis key doc) 0]
                         (let [from (aligned-start doc start)
                               base (text/line-start-offset doc from)]
                              [(analyse-text (text/slice-string doc base we)) base]))
            spans (let [f (- ws base) t (- we base)]
                       (into (spans-in (:tokens a) f t) (spans-in (:brackets a) f t)))
            matches (:matches a)
            match (when (some? caret)
                        (let [rel (- caret base)]
                             (or (when-let [m (get matches rel)]
                                           [(+ base rel) (+ base m)])
                                 (when-let [m (and (pos? rel) (get matches (dec rel)))]
                                           [(+ base (dec rel)) (+ base m)]))))
            lines (mapv (fn [{:keys [n start text]}]
                            {:n n
                             :start start
                             :text text
                             :spans (line-spans spans (- start base) (count text))})
                        rows)]
           {:start-line start
            :total-lines total-lines
            :total-chars total-chars
            :spans-mode (if exact? :exact :window)
            :match match
            :lines lines}))

(defn theme-payload
      "The style table the client renders with: every paint style key
   resolved under theme t (a narcis.theme map), plus the editor
   colours. Keys are the serialized span keys of window payloads."
      [t]
      (let [kinds (concat (keys paint/token-styles)
                          [:symbol :bracket :unmatched]
                          (map (fn [n] [:rainbow n]) (range paint/rainbow-count)))
            styles (into {}
                         (keep (fn [k]
                                   (when-let [s (paint/style t k)]
                                             [(serialize-key k) s])))
                         kinds)]
           {:theme (select-keys t [:name :dark? :background :text :caret :selection
                                   :find-highlight :red :changed-mark])
            :styles styles}))
