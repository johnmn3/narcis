(ns narcis.view
  "Rope window -> render payload: the only text the browser ever sees.

   A window payload carries up to `count` lines starting at a line
   number — each line's text, its char offset, and its paint spans —
   plus buffer totals, so the client can virtualize a billion-line
   buffer from bounded payloads and never holds the text. Spans come
   from narcis.paint/paint-plan (the jonquil.paint lift) over
   narcis.syntax's analysis:

   - Buffers up to exact-limit chars are analysed whole (the plan and
     bracket matches are exact — this is the path the span-correctness
     gate checks against jonquil.syntax directly).
   - Above that, the analysis runs over the window's own text only:
     tokens are exact within the window; bracket depth/matching is
     window-relative (a bracket opened before the window is not seen).
     The payload says which mode served it (:spans-mode :exact or
     :window) so nothing silently pretends to be exact.

   Span keys serialize as strings for the wire: a token kind is its
   name (\"comment\", \"keyword\", ...), rainbow depth n is
   \"rainbow-n\", and :unmatched is \"unmatched\". The client resolves
   them through the style table served from narcis.theme/narcis.paint."
  (:require [narcis.rope :as r]
            [narcis.paint :as paint]
            [narcis.syntax :as syntax]))

(def exact-limit
  "Buffers at or below this many chars get whole-text paint analysis."
  2000000)

(defn serialize-key [k]
  (cond (keyword? k) (name k)
        (vector? k) (str "rainbow-" (second k))
        :else (str k)))

(defn- line-spans
  "The plan's spans clipped to line [ls, ls+llen) as [kind c0 c1] in
   line-relative columns, empties dropped, plan order preserved (a
   later span wins where two overlap — the client segments like
   jonquil.paint/style-at does)."
  [plan ls llen]
  (let [le (+ ls llen)]
    (into []
          (keep (fn [[k s e]]
                  (let [cs (max s ls) ce (min e le)]
                    (when (< cs ce)
                      [(serialize-key k) (- cs ls) (- ce ls)]))))
          plan)))

(defn window
  "Render payload for lines [start, start+count) of rope root.
   opts: {:start n :count m :caret offset-or-nil}."
  [root {:keys [start caret] :as opts}]
  (let [n (get opts :count 100)
        total-lines (r/lines root)
        total-chars (r/chars root)
        start (max 0 (min (get opts :start 0) (max 0 (dec total-lines))))
        texts (r/lines-window root start n)
        cs (r/line-start-offset root start)
        exact? (<= total-chars exact-limit)
        [plan matches base]
        (if exact?
          (let [text (r/to-string root)]
            [(paint/paint-plan text) (:matches (syntax/compute-brackets text)) 0])
          (let [last-line (+ start (count texts))
                ce (if (< last-line total-lines)
                     (r/line-start-offset root last-line)
                     total-chars)
                text (r/slice-string root cs ce)]
            [(paint/paint-plan text) (:matches (syntax/compute-brackets text)) cs]))
        match (when (some? caret)
                (let [rel (- caret base)]
                  (or (when-let [m (get matches rel)]
                        [(+ base rel) (+ base m)])
                      (when-let [m (and (pos? rel) (get matches (dec rel)))]
                        [(+ base (dec rel)) (+ base m)]))))
        lines (loop [i 0 ls cs acc []]
                (if (>= i (count texts))
                  acc
                  (let [txt (nth texts i)]
                    (recur (inc i)
                           (+ ls (count txt) 1)
                           (conj acc {:n (+ start i)
                                      :start ls
                                      :text txt
                                      :spans (line-spans plan ls (count txt))})))))]
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
