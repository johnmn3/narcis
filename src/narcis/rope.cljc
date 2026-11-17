(ns narcis.rope
  "Narcis rope: a fanout-32 B-tree of lines, dual-indexed by character
   offset AND line number — the EVE text rope library shape
   (goal_22f3fc7e2ec7), re-homed here per the Narcis spike plan.

   Representation — Eve values, not host data. A node is an
   eve.deftype RopeNode living in Eve slab storage:

     leaf  RopeNode{:h 0, :text <string>, :chars n, :lines n}
     node  RopeNode{:h h, :kids <eve array of RopeNode>, :chars n,
                    :lines n}

   :lines stores the NEWLINE count of the subtree (segments would not
   sum across children). The public line count is newlines + 1, so
   \"a\\n\" has 2 lines (\"a\" and \"\") and the empty rope 1 line.
   Every internal node's children all have the same height; all leaves
   sit at the same depth. An edit is a split + concat: O(log n) nodes
   are path-copied, every other node is shared with the previous root —
   old roots keep reading their old text forever.

   Construction context (Eve's law, measured at Eve @84bfc7c8): a
   RopeNode is constructed in the CURRENT Eve domain — inside an
   eve.atom swap! that is the atom's own domain, so an edit performed
   as a swap path-copies deftyped nodes inside the atom's storage and
   the disk representation IS the rope structure. Outside a swap, an
   op on an existing root constructs in that root's own domain (Eve's
   binding-over-a-value's-own-domain idiom); an op with no root uses
   this namespace's standalone heap domain. A tree is built entirely
   in one domain: Eve refuses to store a value allocated in a
   different domain, and a flat-sister node is never mixed into a
   persistent tree (see rebuild for the lawful transfer)."
  (:refer-clojure :exclude [chars concat])
  (:require [clojure.string :as str]
            [eve.deftype :as edt]
            [eve.deftype.array :as arr]
            [eve.deftype-proto.alloc :as alloc]
            [eve.deftype-proto.data :as d]))

(def ^:const max-fanout 32)
(def ^:dynamic *leaf-size*
  "Target leaf size in chars. Tests bind it small to stress splitting;
   large-file ingest binds it large."
  1024)

;; ---------------------------------------------------------------------------
;; the node type

(defprotocol IRopeNode
  (-height [n] "Subtree height; leaves are 0.")
  (-node-chars [n] "Character count of the subtree.")
  (-node-lines [n] "NEWLINE count of the subtree.")
  (-leaf-text [n] "The leaf's string, or nil for an internal node.")
  (-node-kids [n] "The internal node's eve array of children, or nil."))

(edt/deftype RopeNode [^:int32 h chars lines text kids]
  IRopeNode
  (-height [_] h)
  (-node-chars [_] chars)
  (-node-lines [_] lines)
  (-leaf-text [_] text)
  (-node-kids [_] kids))

;; ---------------------------------------------------------------------------
;; construction context

(def ^:private standalone-sio
  ;; The domain standalone ropes (tests, benches, pre-atom ingest) are
  ;; built in: a heap domain sized like Eve's own deftype test fixture,
  ;; with the large-block class roomy enough for editor-scale trees.
  (delay (alloc/heap-domain-sio!
           {:capacities {0 (* 64 1024 1024) 1 (* 16 1024 1024)
                         2 (* 16 1024 1024) 3 (* 192 1024 1024)
                         4 (* 16 1024 1024) 5 (* 16 1024 1024)}})))

(defn- root-sio
  "The slab sio a persistent RopeNode lives in, or nil for a flat
   sister / non-node."
  [t]
  (when (and (some? t) (RopeNode? t))
    (try (d/-eve-sio t) (catch Throwable _ nil))))

(defn domain-of
  "The slab sio t lives in (nil for a flat sister or non-node)."
  [t]
  (root-sio t))

(defn same-domain?
  "True when t is a persistent node in the domain of sio (sio may be a
   domain's logging sio, as inside a swap!)."
  [t sio]
  (let [rs (root-sio t)]
    (and (some? rs) (some? sio)
         (identical? (alloc/sio-root rs) (alloc/sio-root sio)))))

(defn in-current-domain?
  "True when t already lives in the domain the current context would
   construct in — i.e. a swap! can store it by reference as-is."
  [t]
  (and (some? d/*domain-sio*) (same-domain? t d/*domain-sio*)))

(defn- with-context
  "Run (f) in the construction context for root t: the caller's bound
   domain when there is one (inside a swap!, this is the atom's own
   domain), else t's own domain, else the standalone heap domain."
  [t f]
  (if (some? d/*domain-sio*)
    (f)
    (binding [d/*domain-sio* (or (root-sio t) @standalone-sio)]
      (f))))

;; ---------------------------------------------------------------------------
;; constructors / accessors (internal: callers hold a construction context)

(defn- count-lines [^String s]
  (loop [i 0 n 1]
    (let [j (.indexOf s "\n" i)]
      (if (neg? j) n (recur (inc j) (inc n))))))

(defn- mk-leaf [s]
  (->RopeNode 0 (count s) (dec (count-lines s)) s nil))

(defn leaf? [t] (and (some? t) (some? (-leaf-text t))))
(defn node? [t] (and (some? t) (nil? (-leaf-text t))))

(defn chars [t] (if (nil? t) 0 (-node-chars t)))
(defn lines [t] (if (nil? t) 1 (inc (-node-lines t)))) ;; segments = newlines + 1
(defn height [t] (if (nil? t) -1 (-height t)))

(defn- kids-vec
  "The children of an internal node, as a host vector of RopeNodes."
  [t]
  (vec (arr/array-seq (-node-kids t))))

(defn- mk-node
  "Build a node from a non-empty seq of children of equal height.
   One child collapses to the child itself; zero children is nil."
  [children]
  (let [cs (vec children)]
    (cond (empty? cs) nil
          (= 1 (count cs)) (first cs)
          :else (let [a (arr/make-array (count cs))]
                  (dotimes [i (count cs)] (arr/aset a i (nth cs i)))
                  (->RopeNode (inc (-height (first cs)))
                              (reduce (fn [acc c] (+ acc (-node-chars c))) 0 cs)
                              (reduce (fn [acc c] (+ acc (-node-lines c))) 0 cs)
                              nil
                              a)))))

(def empty-rope
  ;; The empty leaf as a bare value (a flat sister until first use in a
  ;; domain). Every op reads it by value — text "", 0 chars — and
  ;; constructions never embed it as a child; from-string and friends
  ;; build their own in-context empty leaf.
  (mk-leaf ""))

(defn- empty-leaf [] (mk-leaf ""))

(defn rebuild
  "Re-instantiate t's tree in the CURRENT construction context,
   node for node (same shape, same texts). The lawful cross-domain
   transfer: Eve refuses to store a value allocated in another
   domain, so a root built standalone enters an atom by being rebuilt
   inside that atom's swap!. Source may be any form of the rope —
   persistent in any domain, or a flat sister."
  [t]
  (cond (nil? t) nil
        (leaf? t) (mk-leaf (-leaf-text t))
        :else (mk-node (mapv rebuild (kids-vec t)))))

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

(defn- from-string* [s]
  (cond (nil? s) (empty-leaf)
        (zero? (count s)) (empty-leaf)
        :else (let [leaves (mapv mk-leaf (leaf-chunks s))]
                (if (= 1 (count leaves))
                  (first leaves)
                  (build-level leaves)))))

(defn from-string
  "Rope holding exactly s."
  [s]
  (with-context nil #(from-string* s)))

(defn to-string
  "Materialize the rope's full text. O(chars) — for huge ropes, use
   slice-string / lines-window instead."
  [t]
  (let [sb (StringBuilder.)]
    (letfn [(walk [t]
              (if (leaf? t)
                (.append sb ^String (-leaf-text t))
                (doseq [c (kids-vec t)] (walk c))))]
      (when t (walk t))
      (str sb))))

;; ---------------------------------------------------------------------------
;; split / concat — the edit engine (internal: context held by callers)

(defn- split*
  [t offset]
  (cond (nil? t) [nil nil]
        (leaf? t) (let [s (-leaf-text t)]
                    [(when (pos? offset) (mk-leaf (subs s 0 offset)))
                     (when (< offset (count s)) (mk-leaf (subs s offset)))])
        :else (let [kids (kids-vec t)]
                (loop [i 0 acc 0]
                  (let [c (nth kids i) cc (-node-chars c)]
                    (if (or (< offset (+ acc cc)) (= i (dec (count kids))))
                      (let [[cl cr] (split* c (- offset acc))
                            left (mk-node (into (subvec kids 0 i) (when cl [cl])))
                            right (mk-node (into (if cr [cr] []) (subvec kids (inc i))))]
                        [left right])
                      (recur (inc i) (+ acc cc))))))))

(defn split
  "Split t at char offset (0..chars) into [left right]; either may be nil.
   Path-copying: O(height) nodes are rebuilt."
  [t offset]
  (with-context t #(split* t offset)))

(defn- join-equal
  "Concat two trees of EQUAL height."
  [a b]
  (cond (and (leaf? a) (leaf? b))
        (if (<= (+ (-node-chars a) (-node-chars b)) *leaf-size*)
          (mk-leaf (str (-leaf-text a) (-leaf-text b)))
          (mk-node [a b]))
        (and (node? a) (node? b))
        (let [kids (into (kids-vec a) (kids-vec b))]
          (if (<= (count kids) max-fanout)
            (mk-node kids)
            (mk-node [a b])))
        :else (mk-node [a b])))

(defn- concat*
  [a b]
  (cond (nil? a) b
        (nil? b) a
        :else
        (let [ha (-height a) hb (-height b)]
          (cond
            (= ha hb) (join-equal a b)
            (> ha hb)
            (let [kids (kids-vec a)
                  k (peek kids)
                  c (concat* k b)]
              (if (= (-height c) (-height k))
                (mk-node (assoc kids (dec (count kids)) c))
                ;; c grew one level: splice its 2 children in
                (let [kids2 (into (pop kids) (kids-vec c))]
                  (if (<= (count kids2) max-fanout)
                    (mk-node kids2)
                    (let [half (quot (count kids2) 2)]
                      (mk-node [(mk-node (subvec kids2 0 half))
                                (mk-node (subvec kids2 half))]))))))
            :else ;; hb > ha, mirror on the left spine
            (let [kids (kids-vec b)
                  k (first kids)
                  c (concat* a k)]
              (if (= (-height c) (-height k))
                (mk-node (assoc kids 0 c))
                (let [kids2 (into (kids-vec c) (subvec kids 1))]
                  (if (<= (count kids2) max-fanout)
                    (mk-node kids2)
                    (let [half (quot (count kids2) 2)]
                      (mk-node [(mk-node (subvec kids2 0 half))
                                (mk-node (subvec kids2 half))]))))))))))

(defn concat
  "Concat two ropes. O(height). Maintains uniform leaf depth: when the
   spine splice grows a level, the grown node (always exactly 2 children
   by construction) is spliced in as two children, splitting the parent
   if it overflows fanout."
  [a b]
  (with-context a #(concat* a b)))

;; ---------------------------------------------------------------------------
;; edits

(defn- insert* [t offset s]
  (let [[l r] (split* t offset)]
    (concat* (concat* l (from-string* s)) r)))

(defn insert
  "Rope with string s inserted at char offset. New root; t unchanged."
  [t offset s]
  (with-context t #(insert* t offset s)))

(defn- delete* [t start end]
  (let [[l tmp] (split* t start)
        [_ r] (split* tmp (- end start))]
    (concat* l r)))

(defn delete
  "Rope with chars [start end) removed. New root; t unchanged."
  [t start end]
  (with-context t #(delete* t start end)))

(defn slice-rope
  "Sub-rope holding chars [start end). Shares structure with t."
  [t start end]
  (with-context t
    #(let [[_ tmp] (split* t start)
           [mid _] (split* tmp (- end start))]
       (or mid (empty-leaf)))))

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
  (cond (leaf? t) (leaf-nth-newline-after (-leaf-text t) i)
        :else (let [kids (kids-vec t)]
                (loop [i i idx 0 acc 0]
                  (let [c (nth kids idx) nl (-node-lines c)]
                    (if (<= i nl)
                      (+ acc (nth-newline-after c i))
                      (recur (- i nl) (inc idx) (+ acc (-node-chars c)))))))))

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
          end (if (< i (-node-lines t))
                (dec (line-start-offset t (inc i)))
                (chars t))]
      (slice-string t start end))))

(defn offset->line
  "Line index containing char offset (offset may equal (chars t), which
   is the last line)."
  [t offset]
  (cond (nil? t) 0
        (leaf? t) (dec (count-lines (subs (-leaf-text t) 0 offset)))
        :else (let [kids (kids-vec t)]
                (loop [offset offset idx 0 acc 0]
                  (let [c (nth kids idx) cc (-node-chars c)]
                    (if (or (< offset cc) (= idx (dec (count kids))))
                      (+ acc (offset->line c offset))
                      (recur (- offset cc) (inc idx) (+ acc (-node-lines c)))))))))

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

(defn- ingest-lines*
  ([line-seq] (ingest-lines* line-seq 32768))
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
         (cond (empty? leaves) (or rope (empty-leaf))
               (nil? rope) (build-level (mapv mk-leaf leaves))
               :else (concat* rope (build-level (mapv mk-leaf leaves)))))
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
                        (concat* rope (build-level (mapv mk-leaf (persistent! leaves))))))
               (recur (next ls) sb leaves rope)))
           (recur (next ls) sb leaves rope)))))))

(defn ingest-lines
  "Build a rope from a (possibly lazy) seq of line strings WITHOUT
   trailing newlines, streaming: lines are packed into leaves, leaves
   into balanced subtrees of at most batch leaves, subtrees concatenated.
   Memory is O(batch), never O(file). Bind *leaf-size* for huge inputs."
  ([line-seq] (with-context nil #(ingest-lines* line-seq)))
  ([line-seq batch] (with-context nil #(ingest-lines* line-seq batch))))

(defn from-lines
  "Rope from a seq of newline-terminated records: the text is each line
   followed by \\n (the POSIX text-file convention ingest-lines uses)."
  [line-seq]
  (ingest-lines line-seq))

(defn- ingest-reader* [rdr]
  (let [buf (char-array 65536)]
    (loop [carry "" rope nil]
      (let [n (.read rdr buf)]
        (if (neg? n)
          (cond (pos? (count carry))
                (if (nil? rope)
                  (from-string* carry)
                  (concat* rope (from-string* carry)))
                (nil? rope) (empty-leaf)
                :else rope)
          (let [s (str carry (String. buf 0 n))]
            (if (>= (count s) (* 2 *leaf-size*))
              (let [chunks (leaf-chunks s)
                    keep (peek chunks)
                    done (pop chunks)
                    subtree (build-level (mapv mk-leaf done))]
                (recur keep (if (nil? rope) subtree (concat* rope subtree))))
              (recur s rope))))))))

(defn ingest-reader
  "Rope holding a reader's EXACT text, streamed in char chunks — the
   text is never held whole. The reader is NOT closed (caller owns it)."
  [rdr]
  (with-context nil #(ingest-reader* rdr)))

(defn ingest-file
  "Rope holding a file's exact text, streamed — the file is never held
   whole in memory."
  [path]
  (with-open [rdr (java.io.FileReader. ^String path)]
    (ingest-reader rdr)))
