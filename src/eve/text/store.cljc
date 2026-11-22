(ns eve.text.store
    "Where eve.text's nodes live, on the EVE hosts (the JVM, ClojureScript, nbb,
   babashka, ClojureCLR, basilisp, jolt): TextLeaf and TextNode as eve
   types (eve.deftype/deftype) over eve arrays (eve.deftype.array), in the
   current eve domain. ValueScript reads eve/text/store.vs instead (it finds a .vs file
   before a .cljc one): the same names over its own slab values.

   A leaf's slots: its n line strings, then their n lengths. A node's slots:
   its n children, their lengths, their line counts. A line handed to
   make-leaf is a string, or [slots i len] — line i of a leaf's slots — which
   is copied slot for slot: the new leaf points at the old line's block."
    (:require [clojure.string :as str]
              [eve.deftype :as edt]
              [eve.deftype-proto.alloc :as alloc]
              [eve.deftype-proto.data :as d]
              [eve.deftype.array :as arr]))

(defprotocol IText
             "A node of a document, the way eve.text reads it on every host."
             (-leaf? [t])
             (-chars [t] "its length, line breaks included")
             (-nlines [t])
             (-slots [t]))

(edt/deftype TextLeaf [^:int32 chars slots]
             IText
             (-leaf? [_] true)
             (-chars [_] chars)
             (-nlines [_] (quot (arr/alength slots) 2))
             (-slots [_] slots))

(edt/deftype TextNode [^:int32 chars ^:int32 nlines slots]
             IText
             (-leaf? [_] false)
             (-chars [_] chars)
             (-nlines [_] nlines)
             (-slots [_] slots))

(defn text?
      "True for a document node."
      [x]
      (satisfies? IText x))

(defn sget "Slot i." [a i] (arr/aget a i))
(defn slen "The number of slots." [a] (arr/alength a))

;; s split at its \n (a vector; a trailing \n ends in an empty line)
(defn split-lf [s]
      (loop [from 0 acc (transient [])]
            (if-let [i (str/index-of s "\n" from)]
                    (recur (inc i) (conj! acc (subs s from i)))
                    (persistent! (conj! acc (subs s from))))))

;; pieces of a string being built: a transient vector, joined once
(defn sb [] (transient []))
(defn sb-add! [acc piece] (conj! acc piece))
(defn sb-str [acc] (clojure.string/join (persistent! acc)))

(defn- same-run? [l src j]
       (and (not (string? l)) (identical? (nth l 0) src) (== (nth l 1) j)))

(defn make-leaf
      "A TextLeaf of these lines (at most 32), length len: a string is written, a
   run of references to consecutive lines of one leaf's slots is copied slot
   for slot."
      [lines len]
      (let [n (count lines)
            a (arr/make-array (* 2 n))]
           (loop [i 0]
                 (when (< i n)
                       (let [l (nth lines i)]
                            (if (string? l)
                                (do (arr/aset a i l)
                                    (arr/aset a (+ n i) (count l))
                                    (recur (inc i)))
                                (let [src (nth l 0)
                                      j (nth l 1)
                                      end (loop [k (inc i)]
                                                (if (and (< k n) (same-run? (nth lines k) src (+ j (- k i))))
                                                    (recur (inc k))
                                                    k))
                                      sn (quot (arr/alength src) 2)]
                                     (arr/array-copy src j a i (- end i))
                                     (arr/array-copy src (+ sn j) a (+ n i) (- end i))
                                     (recur end))))))
           (->TextLeaf len a)))

(defn make-node
      "A TextNode over children (nodes), length len."
      [children len]
      (let [n (count children)
            a (arr/make-array (* 3 n))]
           (loop [i 0 nlines 0]
                 (if (< i n)
                     (let [c (nth children i)
                           cl (-nlines c)]
                          (arr/aset a i c)
                          (arr/aset a (+ n i) (-chars c))
                          (arr/aset a (+ n n i) cl)
                          (recur (inc i) (+ nlines cl)))
                     (->TextNode len nlines a)))))

(defn node-with-child
      "Node t with child i replaced by c, length len: one new node, its other
   slots copied."
      [t i c len]
      (let [old (-slots t)
            n (quot (arr/alength old) 3)
            a (arr/aclone old)]
           (arr/aset a i c)
           (arr/aset a (+ n i) (-chars c))
           (arr/aset a (+ n n i) (-nlines c))
           (->TextNode len (+ (- (-nlines t) (arr/aget old (+ n n i))) (-nlines c)) a)))

(defn same-node?
      "True when a and b are the same node: the same block of the same domain
   (two reads of one child are two handles on one block; an eve type's = is
   not the block's identity on every eve)."
      [a b]
      (and (satisfies? d/IEveRoot a) (satisfies? d/IEveRoot b)
           (== (d/-root-header-off a) (d/-root-header-off b))
           (let [sa (d/-eve-sio a) sb (d/-eve-sio b)]
                (or (identical? sa sb)
                    (and sa sb (identical? (alloc/sio-root sa) (alloc/sio-root sb)))))))

(defn foreign?
      "True when t lives in an eve domain other than the current one (where the
   host can tell: an instance carries its domain's slab I/O on the JVM and its
   family, not on ClojureScript, which has one domain at a time)."
      [t]
      (let [s (d/-eve-sio t)]
           (boolean (and s (not (identical? (alloc/sio-root s) (alloc/sio-root (alloc/current-sio))))))))
