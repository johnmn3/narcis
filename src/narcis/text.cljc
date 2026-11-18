(ns narcis.text
  "The buffer's text: an eve.text document (CodeMirror 6's Text as Eve
   data — a B-tree of lines, indexed by character offset and by line
   number, every edit a path copy that shares what it did not touch),
   read and edited here in Narcis's own conventions.

   Conventions. Offsets are 0-based characters and a line break counts
   one character, whatever it was in the file (\\n, \\r\\n or \\r each
   end a line; the file's ending is kept beside the document — see
   ingest-file — and written back on save). Lines are 0-based here
   (eve.text numbers them from 1). A document always has at least one
   line; \"a\\n\" has two (\"a\" and \"\").

   Where a document is built. Building allocates Eve memory, so it
   runs in an Eve domain: inside a swap! on an Eve atom the nodes are
   the atom's own (narcis.buffer runs every edit there), and
   with-heap-domain gives tests, benches and tools a heap domain of
   their own. Reads allocate nothing and run anywhere."
  (:refer-clojure :exclude [chars replace])
  (:require [clojure.string]
            [eve.text :as t]
            [eve.deftype-proto.alloc :as alloc]
            [eve.deftype-proto.data :as d]))

;; ---------------------------------------------------------------------------
;; reads

(defn text? [x] (t/text? x))

(defn chars
  "Characters in the document, each line break counting one."
  [doc]
  (t/text-length doc))

(defn lines
  "Lines in the document (at least 1)."
  [doc]
  (t/text-lines doc))

(defn- clip [doc offset] (max 0 (min offset (t/text-length doc))))

(defn to-string
  "The whole text, lines joined by eol (\"\\n\" when not given). O(chars)."
  ([doc] (t/text->string doc))
  ([doc eol] (t/text->string doc eol)))

(defn slice-string
  "Characters [start end), lines joined by \\n."
  [doc start end]
  (t/text-slice doc start end))

(defn line-at
  "Line i (0-based), without its break; \"\" past the end."
  [doc i]
  (if (and (<= 0 i) (< i (t/text-lines doc)))
    (:text (t/text-line doc (inc i)))
    ""))

(defn line-start-offset
  "Offset of line i's first character; (chars doc) for i at or past the
   line count."
  [doc i]
  (cond (<= i 0) 0
        (>= i (t/text-lines doc)) (t/text-length doc)
        :else (:from (t/text-line doc (inc i)))))

(defn line-of
  "The line holding offset: {:line i :start offset :text s} (0-based)."
  [doc offset]
  (let [l (t/text-line-at doc (clip doc offset))]
    {:line (dec (:number l)) :start (:from l) :text (:text l)}))

(defn offset->line [doc offset] (:line (line-of doc offset)))

(defn offset->line-col [doc offset]
  (let [l (t/text-line-at doc (clip doc offset))]
    [(dec (:number l)) (- (clip doc offset) (:from l))]))

(defn line-col->offset
  "Offset of (line, col); col clamps to the line's length."
  [doc line col]
  (if (>= line (t/text-lines doc))
    (t/text-length doc)
    (let [l (t/text-line doc (inc (max 0 line)))]
      (+ (:from l) (min (max 0 col) (- (:to l) (:from l)))))))

(defn window
  "Up to n lines from line start (0-based): a vector of
   {:n line :start offset :text s} — the viewport's read, one walk of
   the tree."
  [doc start n]
  (mapv (fn [l] {:n (dec (:number l)) :start (:from l) :text (:text l)})
        (t/text-window doc (inc (max 0 start)) n)))

(defn lines-window
  "The texts of up to n lines from line start (0-based)."
  [doc start n]
  (mapv :text (t/text-window doc (inc (max 0 start)) n)))

;; ---------------------------------------------------------------------------
;; edits — each answers a new document; the old one is unchanged

(defn replace
  "The document with [start end) replaced by the string s."
  [doc start end s]
  (t/text-replace doc start end s))

(defn insert [doc offset s] (t/text-replace doc offset offset s))

(defn delete [doc start end] (t/text-replace doc start end ""))

(defn from-string
  "A document of the string s."
  [s]
  (t/text-from-string (or s "")))

(defn normalize-breaks
  "s with every line ending (\r\n, \r) as \n — what a document holds
   of it, so its count is how far an insert of s moves the caret."
  [s]
  (if (and s (clojure.string/includes? s "\r"))
    (-> s (clojure.string/replace "\r\n" "\n") (clojure.string/replace "\r" "\n"))
    s))

(defn copy-here
  "A copy of doc built in the current domain, 4,096 lines at a time: a
   document of its own, sharing nothing with doc (two atoms never hold
   one tree — each reclaims what its own writes supersede)."
  [doc]
  (let [total (t/text-lines doc)
        step 4096]
    (loop [i 0 b (t/text-builder)]
      (if (< i total)
        (let [ls (t/text-window doc (inc i) step)
              k (count ls)
              piece (clojure.string/join "\n" (map :text ls))]
          (recur (+ i k)
                 (t/text-builder-add b (if (< (+ i k) total) (str piece "\n") piece))))
        (t/text-builder-text b)))))

;; ---------------------------------------------------------------------------
;; the file door

(defn- first-eol
  "The first line ending in s (\"\\r\\n\", \"\\r\" or \"\\n\"), or nil. A
   \\r that ends s is not decided here: the next piece may start with
   its \\n."
  [^String s]
  (let [n (count s)]
    (loop [i 0]
      (when (< i n)
        (let [c (.charAt s i)]
          (cond (= c \newline) "\n"
                (= c \return) (cond (= (inc i) n) nil
                                    (= (.charAt s (inc i)) \newline) "\r\n"
                                    :else "\r")
                :else (recur (inc i))))))))

(def ^:private chunk-chars
  "Characters read from a file at a time: about 1 MB."
  1048576)

#?(:clj
   (defn ingest-reader
     "The document of a reader's text, built a chunk at a time — the text
   and its lines are never held whole (eve.text's builder writes each
   leaf as its lines complete). Answers {:doc document :eol ending}:
   the ending is the file's first one (\"\\n\" when it has none), which
   save writes back for every line. The reader is not closed."
     [^java.io.Reader rdr]
  (let [buf (char-array chunk-chars)]
    (loop [b (t/text-builder) eol nil pending-cr? false]
      (let [n (.read rdr buf)]
        (if (neg? n)
          {:doc (t/text-builder-text b)
           :eol (or eol (if pending-cr? "\r" "\n"))}
          (let [s (String. buf 0 n)
                eol (or eol
                        (when pending-cr?
                          (if (and (pos? n) (= (.charAt s 0) \newline)) "\r\n" "\r"))
                        (first-eol s))
                ends-cr? (and (pos? n) (= (.charAt s (dec n)) \return))]
            (recur (t/text-builder-add b s) eol (and (nil? eol) ends-cr?)))))))))

;; The file door itself — a host's files and streams — is the :clj arm
;; today; the document work above and below is shared by every host.

#?(:clj
   (defn ingest-file
     "{:doc :eol} of the file at path (see ingest-reader)."
     [path]
     (with-open [rdr (java.io.FileReader. ^String path)]
       (ingest-reader rdr))))

#?(:clj
   (defn spit-file!
     "Write doc to path a leaf's worth of lines at a time, each line ended
   as eol says — the text is never held whole."
     [path doc eol]
     (with-open [w (java.io.FileWriter. ^String path)]
       (let [total (t/text-lines doc)
             step 4096]
         (loop [i 0]
           (when (< i total)
             (let [ls (t/text-window doc (inc i) step)
                   k (count ls)]
               (dotimes [j k]
                 (.write w ^String (:text (nth ls j)))
                 (when (< (+ i j 1) total) (.write w ^String eol)))
               (recur (+ i k)))))))))

;; ---------------------------------------------------------------------------
;; a domain for tests, benches and tools

(def ^:private heap-sio
  ;; One heap domain for standalone documents, sized for editor-scale
  ;; trees (a line is a block of its own: a million lines is a million
  ;; small blocks and their leaves).
  (delay (alloc/heap-domain-sio!
          {:capacities {0 (* 64 1024 1024) 1 (* 64 1024 1024)
                        2 (* 32 1024 1024) 3 (* 32 1024 1024)
                        4 (* 64 1024 1024) 5 (* 32 1024 1024)}
           :coalesc-descriptors 262144})))

(defn with-heap-domain
  "(f) with a heap domain to build documents in, unless a domain is
   already current (inside a swap! it is the atom's). For tests, benches
   and tools; the editor's documents are built in their buffer atoms."
  [f]
  (if (some? d/*domain-sio*)
    (f)
    (binding [d/*domain-sio* @heap-sio]
      (f))))
