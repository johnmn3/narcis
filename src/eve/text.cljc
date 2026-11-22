(ns eve.text
    "A document of text in eve memory: an immutable B-tree of lines, dual-indexed
   by character offset and by line number — CodeMirror 6's Text (TextLeaf /
   TextNode, @codemirror/state) as one source for every EVE host and for
   ValueScript.

     (def d (text-from-string \"alpha\\nbeta\\ngamma\"))
     (text-lines d)                          ;; => 3
     (text-line d 2)                         ;; => {:from 6 :to 10 :number 2 :text \"beta\"}
     (text-line-at d 12)                     ;; => {:from 11 :to 16 :number 3 :text \"gamma\"}
     (text->string (text-replace d 6 10 \"BETA\\nB2\"))  ;; => \"alpha\\nBETA\\nB2\\ngamma\"

   The tree. A leaf holds up to 32 lines; a node holds children and caches,
   for each child, its length and its line count, so a lookup by offset or by
   line reads one node's numbers per level and loads one child. Line breaks
   are implicit between a leaf's lines and between siblings: a leaf
   [\"alpha\" \"beta\"] is 10 characters long (5 + 1 + 4). Offsets are 0-based,
   line numbers 1-based, replace ranges [from, to). No line ever holds a line
   break: \\r\\n, \\r and \\n each end a line, in text-from-string, in text-of's
   lines and in an inserted string, so a CRLF is one break and can never be
   split across lines, leaves or nodes. A break counts one character.

   Where it lives. eve.text.store holds the two node types: on the EVE hosts
   (eve/text/store.cljc) eve-deftypes over eve arrays (eve.deftype.array) —
   a leaf is its length and 2n slots (its n line strings, then their
   lengths), a node its length, its line count and 3n slots (its children,
   their lengths, their line counts) — in the current eve domain: the running
   swap!'s, else the process's own, so one document lives on the heap, in an
   mmap (disk-backed) atom, or in any domain an atom gives it, with no change
   here. On ValueScript (eve/text/store.vs) the same shapes as ValueScript
   values in its slab. Every node is immutable; an edit returns a new root and
   shares every subtree it did not touch, and within a touched leaf every line
   it did not touch (on the EVE hosts the new leaf's slots point at the old
   line blocks). A document is edited in the domain it lives in (text-replace
   throws for a document from another domain, where the host can tell); an
   inserted text from another domain is copied in by value.

   Shape. The rebalancing is CodeMirror's: replace is decompose (prefix,
   insert, suffix) then TextNode.from over the parts, with its two fast paths
   (an insert into one leaf that stays within 32 lines; an edit inside one
   child whose line count stays between 1/64 and 1/16 of its parent's, which
   copies that one path). So a document's shape is a function of its edit
   history: text-of and text-from-string build the same tree for the same
   lines (bottom-up, never by inserts), and replaying the same edits from the
   same document gives the same tree; equal content reached by different
   edits MAY have different shapes (test/eve/text_shape_test.cljc pins both).

   Large text. text-builder / text-builder-add / text-builder-text build a
   document from its text in pieces (a file read in chunks), writing each leaf
   as its lines complete; the result is the tree text-from-string builds.
   text-append adds text after a document's end, so a document can also grow
   across several swap!s, one piece each.

   Characters are the host's string units: UTF-16 code units on the JVM,
   ClojureScript, nbb, babashka and ClojureCLR; code points on basilisp,
   jolt and ValueScript. They agree for text in the Basic Multilingual Plane."
    (:require [clojure.string :as str]
              [eve.text.store :as s]))

(def BRANCH-SHIFT 5)
(def BRANCH 32)

(def OPEN-FROM 1)
(def OPEN-TO 2)

(def ^:private FAR 2147483647)

(defn text?
      "True for a document."
      [x]
      (s/text? x))

(defn leaf? [t] (and (s/text? t) (s/-leaf? t)))
(defn node? [t] (and (s/text? t) (not (s/-leaf? t))))

(defn- node-n [a] (quot (s/slen a) 3))

(defn- node-children
       "A node's children, as a vector."
       [t]
       (let [a (s/-slots t)]
            (mapv #(s/sget a %) (range (node-n a)))))

;;-----------------------------------------------------------------------------
;; Lines on the host while an edit runs
;;
;; A line is a string (a line the edit made) or [slots i len]: line i of a
;; leaf, not read. A line the edit did not touch stays a reference, and a leaf
;; built from references copies their slots (eve.text.store/make-leaf).
;;-----------------------------------------------------------------------------

(defn- ln-len [l] (if (string? l) (count l) (nth l 2)))
(defn- ln-str [l] (if (string? l) l (s/sget (nth l 0) (nth l 1))))

(defn- ln-slice
       "Characters [from, to) of line l, clipped to it (l itself when that is all
   of it)."
       [l from to]
       (let [n (ln-len l)
             from (max 0 from)
             to (min n to)]
            (cond
             (and (== from 0) (== to n)) l
             (>= from to) ""
             :else (subs (ln-str l) from to))))

(defn- ln-concat [a b]
       (cond
        (zero? (ln-len b)) a
        (zero? (ln-len a)) b
        :else (str (ln-str a) (ln-str b))))

(defn- lines-len
       "The length of lines joined by breaks."
       [lines]
       (reduce (fn [n l] (+ n (ln-len l))) (dec (count lines)) lines))

(defn- leaf-lines
       "A leaf's lines, as references."
       [t]
       (let [a (s/-slots t)
             n (quot (s/slen a) 2)]
            (loop [i 0 acc (transient [])]
                  (if (< i n)
                      (recur (inc i) (conj! acc [a i (s/sget a (+ n i))]))
                      (persistent! acc)))))

(defn- append-text
       "CM6's appendText: lines [from, to) of text appended to target, the first
   onto target's last line."
       ([text target] (append-text text target 0 FAR))
       ([text target from to]
        (let [n (count text)]
             (loop [i 0 pos 0 first? true target (transient (into [] target))]
                   (if (and (< i n) (<= pos to))
                       (let [l (nth text i)
                             end (+ pos (ln-len l))]
                            (if (>= end from)
                                (let [l (ln-slice l (- from pos) (- to pos))
                                      k (dec (count target))]
                                     (recur (inc i) (inc end) false
                                            (if first?
                                                (assoc! target k (ln-concat (nth target k) l))
                                                (conj! target l))))
                                (recur (inc i) (inc end) first? target)))
                       (persistent! target))))))

(defn- slice-text [text from to] (append-text text [""] from to))

;;-----------------------------------------------------------------------------
;; Parts: what decompose collects — documents, shared whole, and leaves on the
;; host (a map of lines and length), written to the store only when
;; TextNode.from puts them in the result.
;;-----------------------------------------------------------------------------

(defn- hleaf [lines len] {::lines lines ::len len})

(defn- p-len [p] (if (map? p) (::len p) (s/-chars p)))
(defn- p-nlines [p] (if (map? p) (count (::lines p)) (s/-nlines p)))
(defn- p-leaf? [p] (or (map? p) (s/-leaf? p)))
(defn- p-lines [p] (if (map? p) (::lines p) (leaf-lines p)))
(defn- empty-line? [p] (and (== 0 (p-len p)) (== 1 (p-nlines p))))

(defn- materialize [p] (if (map? p) (s/make-leaf (::lines p) (::len p)) p))

(defn- split-leaves
       "CM6's TextLeaf.split: lines (a vector) in leaves of 32 (host leaves)."
       [lines]
       (let [n (count lines)]
            (loop [i 0 acc (transient [])]
                  (if (< i n)
                      (let [part (subvec lines i (min n (+ i BRANCH)))]
                           (recur (+ i BRANCH) (conj! acc (hleaf part (lines-len part)))))
                      (persistent! acc)))))

(defn- flatten-into
       "CM6's flatten: a part's lines appended to acc."
       [acc p]
       (cond
        (map? p) (into acc (::lines p))
        (s/-leaf? p) (into acc (leaf-lines p))
        :else (reduce flatten-into acc (node-children p))))

(declare from-parts)

(defn- flush-chunk
       "CM6's flush: the chunk being gathered, as one part of the result."
       [st]
       (if (zero? (:cur-lines st))
           st
           (let [c (:cur st)]
                (assoc st
                       :chunked (conj (:chunked st) (if (== 1 (count c)) (nth c 0) (from-parts c (:cur-len st))))
                       :cur [] :cur-lines 0 :cur-len -1))))

(defn- chunk-add
       "CM6's add, in TextNode.from: child added to the chunking state."
       [{:keys [chunk max-chunk min-chunk] :as st} child]
       (let [cl (p-nlines child)
             cur (:cur st)
             cur-lines (:cur-lines st)]
            (cond
             (and (> cl max-chunk) (not (p-leaf? child)))
             (reduce chunk-add st (node-children child))

             (and (> cl min-chunk) (or (> cur-lines min-chunk) (zero? cur-lines)))
             (let [st (flush-chunk st)]
                  (update st :chunked conj child))

             (and (p-leaf? child) (pos? cur-lines)
                  (p-leaf? (peek cur))
                  (<= (+ cl (p-nlines (peek cur))) BRANCH))
             (let [prev (peek cur)]
                  (assoc st
                         :cur (conj (pop cur) (hleaf (into (p-lines prev) (p-lines child))
                                                     (+ (p-len prev) 1 (p-len child))))
                         :cur-lines (+ cur-lines cl)
                         :cur-len (+ (:cur-len st) (p-len child) 1)))

             :else
             (let [st (if (> (+ cur-lines cl) chunk) (flush-chunk st) st)]
                  (assoc st
                         :cur (conj (:cur st) child)
                         :cur-lines (+ (:cur-lines st) cl)
                         :cur-len (+ (:cur-len st) (p-len child) 1))))))

(defn- from-parts
       "CM6's TextNode.from: one document over parts (texts joined by breaks),
   length len."
       [parts len]
       (let [lines (reduce (fn [n p] (+ n (p-nlines p))) 0 parts)]
            (if (< lines BRANCH)
                (s/make-leaf (reduce flatten-into [] parts) len)
                (let [chunk (max BRANCH (bit-shift-right lines BRANCH-SHIFT))
                      st (flush-chunk (reduce chunk-add
                                              {:chunk chunk
                                               :max-chunk (bit-shift-left chunk 1)
                                               :min-chunk (bit-shift-right chunk 1)
                                               :chunked [] :cur [] :cur-lines 0 :cur-len -1}
                                              parts))
                      chunked (:chunked st)]
                     (if (== 1 (count chunked))
                         (materialize (nth chunked 0))
                         (s/make-node (mapv materialize chunked) len))))))

;;-----------------------------------------------------------------------------
;; decompose and replace (CM6's Text.replace, TextLeaf.replace,
;; TextNode.replace)
;;-----------------------------------------------------------------------------

(declare decompose)

(defn- decompose-leaf [t from to target open]
       (let [len (p-len t)
             line (if (and (<= from 0) (>= to len))
                      t
                      (hleaf (slice-text (p-lines t) from to) (- (min to len) (max 0 from))))]
            (if (pos? (bit-and open OPEN-FROM))
                (let [prev (peek target)
                      target (pop target)]
                     (cond
          ;; appending an empty line, or onto one, changes nothing: the
          ;; other side as it is (CM6 copies it; the content is the same)
                      (empty-line? line) (conj target prev)
                      (empty-line? prev) (conj target line)
                      :else
                      (let [joined (append-text (p-lines line) (p-lines prev) 0 (p-len line))]
                           (if (<= (count joined) BRANCH)
                               (conj target (hleaf joined (+ (p-len prev) (p-len line))))
                               (let [mid (bit-shift-right (count joined) 1)
                                     l (subvec joined 0 mid)
                                     r (subvec joined mid)]
                                    (conj target (hleaf l (lines-len l)) (hleaf r (lines-len r))))))))
                (conj target line))))

(defn- decompose-node [t from to target open]
       (let [a (s/-slots t)
             n (node-n a)]
            (loop [i 0 pos 0 target target]
                  (if (and (< i n) (<= pos to))
                      (let [end (+ pos (s/sget a (+ n i)))]
                           (recur (inc i) (inc end)
                                  (if (and (<= from end) (>= to pos))
                                      (let [child-open (bit-and open (bit-or (if (<= pos from) OPEN-FROM 0)
                                                                             (if (>= end to) OPEN-TO 0)))
                                            child (s/sget a i)]
                                           (if (and (>= pos from) (<= end to) (zero? child-open))
                                               (conj target child)
                                               (decompose child (- from pos) (- to pos) target child-open)))
                                      target)))
                      target))))

(defn- decompose
       "CM6's decompose: the parts of p over [from, to) appended to target."
       [p from to target open]
       (if (p-leaf? p)
           (decompose-leaf p from to target open)
           (decompose-node p from to target open)))

(defn- base-replace
       "CM6's Text.replace: decompose, then TextNode.from."
       [t from to ins]
       (let [parts (decompose t 0 from [] OPEN-TO)
             parts (if (pos? (p-len ins))
                       (decompose ins 0 (p-len ins) parts (bit-or OPEN-FROM OPEN-TO))
                       parts)
             parts (decompose t to (s/-chars t) parts OPEN-FROM)]
            (from-parts parts (+ (- (s/-chars t) (- to from)) (p-len ins)))))

(declare replace*)

(defn- leaf-replace
       "CM6's TextLeaf.replace: an inserted leaf joins this one's lines."
       [t from to ins]
       (if-not (p-leaf? ins)
               (base-replace t from to ins)
               (let [lines (leaf-lines t)
                     joined (append-text lines (append-text (p-lines ins) (slice-text lines 0 from)) to FAR)
                     len (- (+ (s/-chars t) (p-len ins)) (- to from))]
                    (if (<= (count joined) BRANCH)
                        (s/make-leaf joined len)
                        (from-parts (split-leaves joined) len)))))

(defn- node-replace
       "CM6's TextNode.replace: an edit inside one child whose line count stays in
   bounds replaces that child (a path copy); anything else rebalances."
       [t from to ins]
       (let [a (s/-slots t)
             n (node-n a)
             lines (s/-nlines t)]
            (if (< (p-nlines ins) lines)
                (loop [i 0 pos 0]
                      (if (< i n)
                          (let [end (+ pos (s/sget a (+ n i)))]
                               (if (and (>= from pos) (<= to end))
                                   (let [updated (replace* (s/sget a i) (- from pos) (- to pos) ins)
                                         ul (s/-nlines updated)
                                         total (+ (- lines (s/sget a (+ n n i))) ul)]
                                        (if (and (< ul (bit-shift-right total (dec BRANCH-SHIFT)))
                                                 (> ul (bit-shift-right total (inc BRANCH-SHIFT))))
                                            (s/node-with-child t i updated (+ (- (s/-chars t) (- to from)) (p-len ins)))
                                            (base-replace t pos end updated)))
                                   (recur (inc i) (inc end))))
                          (base-replace t from to ins)))
                (base-replace t from to ins))))

(defn- replace* [t from to ins]
       (if (s/-leaf? t)
           (leaf-replace t from to ins)
           (node-replace t from to ins)))

;;-----------------------------------------------------------------------------
;; Lines from strings
;;-----------------------------------------------------------------------------

(defn- split-breaks
       "s split at its line breaks (\\r\\n, \\r, \\n)."
       [s]
       (let [s (if (str/includes? s "\r")
                   (str/replace (str/replace s "\r\n" "\n") "\r" "\n")
                   s)]
            (s/split-lf s)))

(defn- has-break? [s] (or (str/includes? s "\n") (str/includes? s "\r")))

(defn- normalize-lines
       "lines as a vector of break-free strings, [\"\"] for none."
       [lines]
       (let [v (vec lines)]
            (cond
             (empty? v) [""]
             (some has-break? v) (into [] (mapcat split-breaks) v)
             :else v)))

(defn- build
       "The tree over break-free lines, built bottom-up (CM6's Text.of)."
       [lines]
       (if (<= (count lines) BRANCH)
           (s/make-leaf lines (lines-len lines))
           (from-parts (split-leaves lines) (lines-len lines))))

(defn- lines-part [lines]
       (if (<= (count lines) BRANCH)
           (hleaf lines (lines-len lines))
           (build lines)))

(declare text-seq)

(defn- insert-part
       "What text-replace inserts: a document of this domain as it is; a string, a
   collection of lines, or a document of another domain as its lines."
       [ins]
       (cond
        (string? ins) (lines-part (split-breaks ins))
        (s/text? ins) (if (s/foreign? ins) (lines-part (vec (text-seq ins))) ins)
        :else (lines-part (normalize-lines ins))))

;;-----------------------------------------------------------------------------
;; Public API
;;-----------------------------------------------------------------------------

(defn text-of
      "A document of these lines (a collection of strings). No lines is the empty
   document: one empty line, length 0. A line holding a line break is split
   there."
      [lines]
      (build (normalize-lines lines)))

(defn text-from-string
      "A document of the string s, split at its line breaks (\\r\\n, \\r, \\n)."
      [s]
      (build (split-breaks s)))

(defn text-length
      "The number of characters, each line break counting one. O(1)."
      [t]
      (s/-chars t))

(defn text-lines
      "The number of lines (at least 1). O(1)."
      [t]
      (s/-nlines t))

(defn text-replace
      "The document with [from, to) replaced by ins: a document, a string (split
   at its line breaks) or a collection of lines. from and to are clipped to
   the document, as CM6 clips them. A new root; every subtree the edit did
   not touch is shared."
      [t from to ins]
      (when (s/foreign? t)
            (throw (ex-info "eve.text: this document lives in another eve domain; edit it inside that domain's swap!"
                            {:from from :to to})))
      (let [len (s/-chars t)
            from (max 0 (min len from))
            to (max from (min len to))]
           (replace* t from to (insert-part ins))))

(defn text-append
      "The document with ins (a document, a string or a collection of lines) after
   its last character: ins's first line continues t's last line."
      [t ins]
      (text-replace t (s/-chars t) (s/-chars t) ins))

;;-----------------------------------------------------------------------------
;; A builder: a document from its text in pieces
;;
;; text-from-string holds the whole string and the vector of all its lines
;; before it writes a node. A builder takes the text a piece at a time (a
;; file read in chunks) and writes each leaf as soon as its 32 lines are
;; complete, so the host holds the piece in hand, the lines of the leaf being
;; filled, and one leaf per 32 lines written — never the text or its lines.
;;
;; A builder is a value on the host: text-builder-add answers a new one. The
;; leaves it has written are in the current eve domain, reachable only from
;; the builder until text-builder-text puts them in a document — so a builder
;; lives inside one swap!, as any document under construction does.
;;-----------------------------------------------------------------------------

(defn text-builder
      "A builder of a document from its text in pieces: add pieces with
   text-builder-add, read the document with text-builder-text. after-cr?
   says the text before the first piece ended in \\r (text-builder-cr? of
   the builder that read it), so a \\n that starts the first piece is the
   rest of that break and adds no line."
      ([] (text-builder false))
      ([after-cr?]
       {::leaves [] ::pending [] ::carry "" ::done 0 ::cr? (boolean after-cr?)}))

(defn text-builder-cr?
      "True when the last piece added ended in \\r: a \\n starting the next piece
   belongs to the same line break."
      [b]
      (::cr? b))

(defn text-builder-add
      "The builder with the string s added after what it holds. A piece may end
   anywhere — inside a line, or between the \\r and the \\n of one break."
      [b s]
      (let [n (count s)]
           (if (zero? n)
               b
               (let [cr? (= "\r" (subs s (dec n) n))
                     s (if (and (::cr? b) (= "\n" (subs s 0 1))) (subs s 1) s)]
                    (if (zero? (count s))
                        (assoc b ::cr? false)
                        (let [carry (::carry b)
                              pieces (split-breaks (if (zero? (count carry)) s (str carry s)))
                ;; every piece but the last is a whole line
                              lines (into (::pending b) (pop pieces))
                              k (count lines)
                              full (- k (rem k BRANCH))
                              leaves (loop [i 0 acc (transient (::leaves b))]
                                           (if (< i full)
                                               (let [part (subvec lines i (+ i BRANCH))]
                                                    (recur (+ i BRANCH) (conj! acc (s/make-leaf part (lines-len part)))))
                                               (persistent! acc)))]
                             {::leaves leaves
                              ::pending (into [] (subvec lines full k))
                              ::carry (peek pieces)
                              ::done (reduce (fn [m l] (+ m (count l) 1)) (::done b) (pop pieces))
                              ::cr? cr?}))))))

(defn text-builder-text
      "The document of everything added so far — the tree text-from-string builds
   for the same text. The builder is unchanged and can be added to again."
      [b]
      (let [carry (::carry b)
            tail (conj (::pending b) carry)
            len (+ (::done b) (count carry))
            leaves (::leaves b)]
           (if (zero? (count leaves))
               (s/make-leaf tail len)
               (from-parts (conj leaves (hleaf tail (lines-len tail))) len))))

(defn- line-inner
       "CM6's lineInner: the line at target (a line number when line?, else an
   offset), from a node whose first line is number line at offset offset."
       [t target line? line offset]
       (let [a (s/-slots t)]
            (if (s/-leaf? t)
                (let [n (quot (s/slen a) 2)]
                     (loop [i 0 line line offset offset]
                           (let [end (+ offset (s/sget a (+ n i)))]
                                (if (>= (if line? line end) target)
                                    {:from offset :to end :number line :text (s/sget a i)}
                                    (recur (inc i) (inc line) (inc end))))))
                (let [n (node-n a)]
                     (loop [i 0 line line offset offset]
                           (let [end (+ offset (s/sget a (+ n i)))
                                 end-line (+ line (s/sget a (+ n n i)) -1)]
                                (if (>= (if line? end-line end) target)
                                    (line-inner (s/sget a i) target line? line offset)
                                    (recur (inc i) (inc end-line) (inc end)))))))))

(defn text-line
      "Line n (1-based): {:from :to :number :text}, :from the offset of its first
   character, :to the offset just past its last. Throws for a line number
   outside 1..(text-lines t), as CM6's line does."
      [t n]
      (when-not (and (integer? n) (<= 1 n (s/-nlines t)))
                (throw (ex-info (str "eve.text: no line " n " in a " (s/-nlines t) "-line document")
                                {:line n :lines (s/-nlines t)})))
      (line-inner t n true 1 0))

(defn text-line-at
      "The line holding offset pos (the line a position at its end belongs to):
   {:from :to :number :text}. Throws for an offset outside 0..(text-length t),
   as CM6's lineAt does."
      [t pos]
      (when-not (and (integer? pos) (<= 0 pos (s/-chars t)))
                (throw (ex-info (str "eve.text: no offset " pos " in a document of length " (s/-chars t))
                                {:pos pos :length (s/-chars t)})))
      (line-inner t pos false 1 0))

(defn- window-into
       "The lines of t numbered lo..hi appended to acc (a transient vector of
   {:from :to :number :text}); t's first line is number line at offset."
       [t lo hi line offset acc]
       (let [a (s/-slots t)]
            (if (s/-leaf? t)
                (let [n (quot (s/slen a) 2)]
                     (loop [i 0 line line offset offset acc acc]
                           (if (and (< i n) (<= line hi))
                               (let [end (+ offset (s/sget a (+ n i)))]
                                    (recur (inc i) (inc line) (inc end)
                                           (if (>= line lo)
                                               (conj! acc {:from offset :to end :number line :text (s/sget a i)})
                                               acc)))
                               acc)))
                (let [n (node-n a)]
                     (loop [i 0 line line offset offset acc acc]
                           (if (and (< i n) (<= line hi))
                               (let [nl (s/sget a (+ n n i))
                                     end (+ offset (s/sget a (+ n i)))]
                                    (recur (inc i) (+ line nl) (inc end)
                                           (if (>= (+ line nl -1) lo)
                                               (window-into (s/sget a i) lo hi line offset acc)
                                               acc)))
                               acc))))))

(defn text-window
      "Up to n lines from line number line (1-based), a vector of
   {:from :to :number :text} as text-line answers them — a viewport's read:
   one walk of the tree, the leaves between its first and last line, where n
   calls of text-line walk it n times. Lines past the document's end are not
   in the answer; line is clipped to 1."
      [t line n]
      (let [lo (max 1 line)
            hi (min (s/-nlines t) (+ lo n -1))]
           (if (or (< n 1) (> lo hi))
               []
               (persistent! (window-into t lo hi 1 0 (transient []))))))

(defn- slice-into
       "CM6's sliceString: [from, to) of t's characters appended to acc (the
   store's string builder), lines joined by sep."
       [t from to sep acc]
       (let [a (s/-slots t)]
            (if (s/-leaf? t)
                (let [n (quot (s/slen a) 2)]
                     (loop [i 0 pos 0 acc acc]
                           (if (and (<= pos to) (< i n))
                               (let [l (s/sget a (+ n i))
                                     end (+ pos l)
                                     acc (if (and (> pos from) (pos? i)) (s/sb-add! acc sep) acc)
                                     acc (if (and (< from end) (> to pos))
                                             (let [line (s/sget a i)]
                                                  (s/sb-add! acc (if (and (<= from pos) (>= to end))
                                                                     line
                                                                     (subs line (max 0 (- from pos)) (min l (- to pos))))))
                                             acc)]
                                    (recur (inc i) (inc end) acc))
                               acc)))
                (let [n (node-n a)
                      from (max 0 (min (s/-chars t) from))
                      to (max from (min (s/-chars t) to))]
                     (loop [i 0 pos 0 acc acc]
                           (if (and (< i n) (<= pos to))
                               (let [end (+ pos (s/sget a (+ n i)))
                                     acc (if (and (> pos from) (pos? i)) (s/sb-add! acc sep) acc)
                                     acc (if (and (< from end) (> to pos))
                                             (slice-into (s/sget a i) (- from pos) (- to pos) sep acc)
                                             acc)]
                                    (recur (inc i) (inc end) acc))
                               acc))))))

(defn text-slice
      "The characters [from, to) as a string, lines joined by sep (\"\\n\" by
   default). from and to are clipped to the document."
      ([t from to] (text-slice t from to "\n"))
      ([t from to sep]
       (let [len (s/-chars t)
             from (max 0 (min len from))
             to (max from (min len to))]
            (s/sb-str (slice-into t from to sep (s/sb))))))

(defn text->string
      "The whole document as a string, lines joined by sep (\"\\n\" by default)."
      ([t] (text-slice t 0 (s/-chars t) "\n"))
      ([t sep] (text-slice t 0 (s/-chars t) sep)))

(defn text-seq
      "The document's lines, a lazy seq of strings."
      [t]
      (if (s/-leaf? t)
          (let [a (s/-slots t)]
               (map #(s/sget a %) (range (quot (s/slen a) 2))))
          (mapcat text-seq (node-children t))))

(defn text-depth
      "The tree's depth: 1 for a leaf, else 1 + its deepest child's."
      [t]
      (if (s/-leaf? t)
          1
          (inc (reduce max 0 (map text-depth (node-children t))))))

(defn text-children
      "A node's children (a vector; empty for a leaf) — for inspecting sharing."
      [t]
      (if (s/-leaf? t) [] (node-children t)))

(defn text-same-node?
      "True when a and b are the same node — on the EVE hosts the same block of
   eve memory — not merely equal text."
      [a b]
      (boolean (and (s/text? a) (s/text? b) (s/same-node? a b))))

(defn text-shares-child?
      "True when the roots of a and b hold a child in common — the same node."
      [a b]
      (boolean
       (and (node? a) (node? b)
            (let [bs (node-children b)]
                 (some (fn [ac] (some #(s/same-node? ac %) bs)) (node-children a))))))
