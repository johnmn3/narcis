(ns narcis.rope
  "Narcis rope: a fanout-32 B-tree of lines, dual-indexed by character
   offset AND line number — the EVE text rope library shape
   (goal_22f3fc7e2ec7), re-homed here per the Narcis spike plan.

   Representation — plain immutable data, i.e. exactly the values an Eve
   disk atom stores, so a rope root IS the buffer value in the atom:

     leaf  {:leaf <string> :chars n :lines n :h 0}
     node  {:node [<child> ...] :chars n :lines n :h h}   ; 2..32 children

   :lines stores the NEWLINE count of the subtree (segments would not
   sum across children). The public line count is newlines + 1, so
   \"a\\n\" has 2 lines (\"a\" and \"\") and the empty rope 1 line.
   Every internal node's children all have the same height; all leaves
   sit at the same depth. An edit is a split + concat: O(log n) nodes
   are path-copied, every other node is shared with the previous root —
   old roots keep reading their old text forever.

   Divergence from the spike brief, named: nodes are plain Eve values,
   not eve/deftype slab objects. eve.deftype in the checked Eve trees is
   slab-offset machinery with :clj/:cljs arms only and Eve does not load
   on the spike's target host (jolt v0.8.23 — see REPORT.md), so a
   slab-offset rope could not run there at all. Building the rope from
   Eve *values* keeps it loadable on every host today and storable in
   the disk atom unchanged; re-homing node storage onto eve/deftype is
   a storage-layer swap behind this same API when Eve-on-jolt lands."
  (:refer-clojure :exclude [chars concat])
  (:require [clojure.string :as str]))

(def ^:const max-fanout 32)
(def ^:dynamic *leaf-size*
  "Target leaf size in chars. Tests bind it small to stress splitting;
   large-file ingest binds it large."
  1024)

;; ---------------------------------------------------------------------------
;; constructors / accessors

(defn- count-lines [^String s]
  (loop [i 0 n 1]
    (let [j (.indexOf s "\n" i)]
      (if (neg? j) n (recur (inc j) (inc n))))))

(defn mk-leaf [s]
  {:leaf s :chars (count s) :lines (dec (count-lines s)) :h 0})

(defn leaf? [t] (contains? t :leaf))
(defn node? [t] (contains? t :node))

(defn chars [t] (if (nil? t) 0 (:chars t)))
(defn lines [t] (if (nil? t) 1 (inc (:lines t)))) ;; segments = newlines + 1
(defn height [t] (if (nil? t) -1 (:h t)))

(defn mk-node
  "Build a node from a non-empty seq of children of equal height.
   One child collapses to the child itself; zero children is nil."
  [children]
  (let [cs (vec children)]
    (cond (empty? cs) nil
          (= 1 (count cs)) (first cs)
          :else {:node cs
                 :chars (reduce (fn [a c] (+ a (:chars c))) 0 cs)
                 :lines (reduce (fn [a c] (+ a (:lines c))) 0 cs)
                 :h (inc (:h (first cs)))})))

(def empty-rope (mk-leaf ""))

;; ---------------------------------------------------------------------------
;; bulk construction

(defn leaf-chunks
  "Split s into leaf strings of at most *leaf-size* chars, preferring to
   break right after a newline."
  [^String s]
  (let [n (count s) size *leaf-size*]
    (loop [i 0 acc []]
      (if (>= i n)
        acc
        (let [end (min n (+ i size))]
          (if (= end n)
            (conj acc (subs s i n))
            (let [nl (.lastIndexOf s "\n" (dec end))]
              (if (> nl i)
                (recur (inc nl) (conj acc (subs s i (inc nl))))
                (recur end (conj acc (subs s i end)))))))))))

(defn- build-level [ts]
  (if (<= (count ts) max-fanout)
    (mk-node ts)
    (build-level (mapv mk-node (partition-all max-fanout ts)))))

(defn from-string
  "Rope holding exactly s."
  [s]
  (cond (nil? s) empty-rope
        (zero? (count s)) empty-rope
        :else (let [leaves (mapv mk-leaf (leaf-chunks s))]
                (if (= 1 (count leaves))
                  (first leaves)
                  (build-level leaves)))))

(defn to-string
  "Materialize the rope's full text. O(chars) — for huge ropes, use
   slice-string / lines-window instead."
  [t]
  (let [sb (StringBuilder.)]
    (letfn [(walk [t]
              (if (leaf? t)
                (.append sb ^String (:leaf t))
                (doseq [c (:node t)] (walk c))))]
      (when t (walk t))
      (str sb))))

;; ---------------------------------------------------------------------------
;; split / concat — the edit engine

(defn split
  "Split t at char offset (0..chars) into [left right]; either may be nil.
   Path-copying: O(height) nodes are rebuilt."
  [t offset]
  (cond (nil? t) [nil nil]
        (leaf? t) (let [s (:leaf t)]
                    [(when (pos? offset) (mk-leaf (subs s 0 offset)))
                     (when (< offset (count s)) (mk-leaf (subs s offset)))])
        :else (let [kids (:node t)]
                (loop [i 0 acc 0]
                  (let [c (nth kids i) cc (:chars c)]
                    (if (or (< offset (+ acc cc)) (= i (dec (count kids))))
                      (let [[cl cr] (split c (- offset acc))
                            left (mk-node (into (subvec kids 0 i) (when cl [cl])))
                            right (mk-node (into (if cr [cr] []) (subvec kids (inc i))))]
                        [left right])
                      (recur (inc i) (+ acc cc))))))))

(defn- join-equal
  "Concat two trees of EQUAL height."
  [a b]
  (cond (and (leaf? a) (leaf? b))
        (if (<= (+ (:chars a) (:chars b)) *leaf-size*)
          (mk-leaf (str (:leaf a) (:leaf b)))
          (mk-node [a b]))
        (and (node? a) (node? b))
        (let [kids (into (:node a) (:node b))]
          (if (<= (count kids) max-fanout)
            (mk-node kids)
            (mk-node [a b])))
        :else (mk-node [a b])))

(defn concat
  "Concat two ropes. O(height). Maintains uniform leaf depth: when the
   spine splice grows a level, the grown node (always exactly 2 children
   by construction) is spliced in as two children, splitting the parent
   if it overflows fanout."
  [a b]
  (cond (nil? a) b
        (nil? b) a
        :else
        (let [ha (:h a) hb (:h b)]
          (cond
            (= ha hb) (join-equal a b)
            (> ha hb)
            (let [kids (:node a)
                  k (peek kids)
                  c (concat k b)]
              (if (= (:h c) (:h k))
                (mk-node (assoc kids (dec (count kids)) c))
                ;; c grew one level: splice its 2 children in
                (let [kids2 (into (pop kids) (:node c))]
                  (if (<= (count kids2) max-fanout)
                    (mk-node kids2)
                    (let [half (quot (count kids2) 2)]
                      (mk-node [(mk-node (subvec kids2 0 half))
                                (mk-node (subvec kids2 half))]))))))
            :else ;; hb > ha, mirror on the left spine
            (let [kids (:node b)
                  k (first kids)
                  c (concat a k)]
              (if (= (:h c) (:h k))
                (mk-node (assoc kids 0 c))
                (let [kids2 (into (:node c) (subvec kids 1))]
                  (if (<= (count kids2) max-fanout)
                    (mk-node kids2)
                    (let [half (quot (count kids2) 2)]
                      (mk-node [(mk-node (subvec kids2 0 half))
                                (mk-node (subvec kids2 half))]))))))))))

;; ---------------------------------------------------------------------------
;; edits

(defn insert
  "Rope with string s inserted at char offset. New root; t unchanged."
  [t offset s]
  (let [[l r] (split t offset)]
    (concat (concat l (from-string s)) r)))

(defn delete
  "Rope with chars [start end) removed. New root; t unchanged."
  [t start end]
  (let [[l tmp] (split t start)
        [_ r] (split tmp (- end start))]
    (concat l r)))

(defn slice-rope
  "Sub-rope holding chars [start end). Shares structure with t."
  [t start end]
  (let [[_ tmp] (split t start)
        [mid _] (split tmp (- end start))]
    (or mid empty-rope)))

(defn slice-string [t start end]
  (to-string (slice-rope t start end)))

;; ---------------------------------------------------------------------------
;; line index

(defn- leaf-nth-newline-after
  "Offset within s just after its i-th newline (i is 1-based,
   1 <= i <= newline count of s)."
  [^String s i]
  (loop [i i pos 0]
    (let [j (.indexOf s "\n" pos)]
      (if (= i 1)
        (inc j)
        (recur (dec i) (inc j))))))

(defn- nth-newline-after
  "Char offset in t just after its i-th newline (1-based)."
  [t i]
  (cond (leaf? t) (leaf-nth-newline-after (:leaf t) i)
        :else (let [kids (:node t)]
                (loop [i i idx 0 acc 0]
                  (let [c (nth kids idx) nl (:lines c)]
                    (if (<= i nl)
                      (+ acc (nth-newline-after c i))
                      (recur (- i nl) (inc idx) (+ acc (:chars c)))))))))

(defn line-start-offset
  "Char offset of the start of line i (0-based). Line 0 starts at 0;
   line i starts just after the i-th newline. i may equal (lines t)
   only as the EOF sentinel, giving (chars t)."
  [t i]
  (cond (nil? t) 0
        (zero? i) 0
        (>= i (lines t)) (chars t)
        :else (nth-newline-after t i)))

(defn line-at
  "The i-th line (0-based), without its newline. A line may span
   several leaves; it is read by offsets, never by leaf-local index."
  [t i]
  (if (nil? t)
    ""
    (let [start (line-start-offset t i)
          end (if (< i (:lines t))
                (dec (line-start-offset t (inc i)))
                (chars t))]
      (slice-string t start end))))

(defn offset->line
  "Line index containing char offset (offset may equal (chars t), which
   is the last line)."
  [t offset]
  (cond (nil? t) 0
        (leaf? t) (dec (count-lines (subs (:leaf t) 0 offset)))
        :else (let [kids (:node t)]
                (loop [offset offset idx 0 acc 0]
                  (let [c (nth kids idx) cc (:chars c)]
                    (if (or (< offset cc) (= idx (dec (count kids))))
                      (+ acc (offset->line c offset))
                      (recur (- offset cc) (inc idx) (+ acc (:lines c)))))))))

(defn line-col->offset
  "Char offset of (line, col); col clamps to the line's length."
  [t line col]
  (let [ls (line-start-offset t line)
        llen (count (line-at t line))]
    (+ ls (min col llen))))

(defn offset->line-col [t offset]
  (let [line (offset->line t offset)]
    [line (- offset (line-start-offset t line))]))

(defn insert-at-line-col [t line col s]
  (insert t (line-col->offset t line col) s))

(defn delete-at-line-col
  "Delete n chars starting at (line, col)."
  [t line col n]
  (let [start (line-col->offset t line col)]
    (delete t start (min (chars t) (+ start n)))))

(defn lines-window
  "Vector of up to n line strings starting at line start-line — the
   renderer's viewport read. O(n * height)."
  [t start-line n]
  (let [total (lines t)]
    (loop [i start-line acc []]
      (if (or (>= i total) (>= (count acc) n))
        acc
        (recur (inc i) (conj acc (line-at t i)))))))

;; ---------------------------------------------------------------------------
;; ingest — the file door

(defn ingest-lines
  "Build a rope from a (possibly lazy) seq of line strings WITHOUT
   trailing newlines, streaming: lines are packed into leaves, leaves
   into balanced subtrees of at most batch leaves, subtrees concatenated.
   Memory is O(batch), never O(file). Bind *leaf-size* for huge inputs."
  ([line-seq] (ingest-lines line-seq 32768))
  ([line-seq batch]
   (loop [ls (seq line-seq)
          sb (StringBuilder.)
          leaves (transient [])
          rope nil]
     (if (nil? ls)
       (let [leaves (if (pos? (.length sb))
                      (conj! leaves (str sb))
                      leaves)
             leaves (persistent! leaves)]
         (cond (empty? leaves) (or rope empty-rope)
               (nil? rope) (build-level (mapv mk-leaf leaves))
               :else (concat rope (build-level (mapv mk-leaf leaves)))))
       (let [line (first ls)]
         (.append sb ^String line)
         (.append sb "\n")
         (if (>= (.length sb) *leaf-size*)
           (let [leaves (conj! leaves (str sb))
                 sb (StringBuilder.)]
             (if (>= (count leaves) batch)
               (recur (next ls) sb (transient [])
                      (if (nil? rope)
                        (build-level (mapv mk-leaf (persistent! leaves)))
                        (concat rope (build-level (mapv mk-leaf (persistent! leaves))))))
               (recur (next ls) sb leaves rope)))
           (recur (next ls) sb leaves rope)))))))

(defn from-lines
  "Rope from a seq of newline-terminated records: the text is each line
   followed by \\n (the POSIX text-file convention ingest-lines uses)."
  [line-seq]
  (ingest-lines line-seq))

(defn ingest-reader
  "Rope holding a reader's EXACT text, streamed in char chunks — the
   text is never held whole. The reader is NOT closed (caller owns it)."
  [rdr]
  (let [buf (char-array 65536)]
    (loop [carry "" rope nil]
      (let [n (.read rdr buf)]
        (if (neg? n)
          (cond (pos? (count carry))
                (if (nil? rope)
                  (from-string carry)
                  (concat rope (from-string carry)))
                (nil? rope) empty-rope
                :else rope)
          (let [s (str carry (String. buf 0 n))]
            (if (>= (count s) (* 2 *leaf-size*))
              (let [chunks (leaf-chunks s)
                    keep (peek chunks)
                    done (pop chunks)
                    subtree (build-level (mapv mk-leaf done))]
                (recur keep (if (nil? rope) subtree (concat rope subtree))))
              (recur s rope))))))))

(defn ingest-file
  "Rope holding a file's exact text, streamed — the file is never held
   whole in memory."
  [path]
  (with-open [rdr (java.io.FileReader. ^String path)]
    (ingest-reader rdr)))
