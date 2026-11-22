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
   the atom's own (narcis.buffer runs every edit there). Reads
   allocate nothing and run anywhere."
    (:refer-clojure :exclude [chars replace])
    (:require [clojure.string]
              [eve.text :as t]))

;; ---------------------------------------------------------------------------
;; reads

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

#?(:clj (defn- first-eol
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
                                           :else (recur (inc i)))))))))

#?(:clj (def ^:private chunk-chars
             "Characters read from a file at a time: about 1 MB."
             1048576))

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

;; ---------------------------------------------------------------------------
;; progressive ingest: the head now, the tail a chunk at a time
;;
;; ingest-file above builds the whole document before anything can be
;; shown of it. The progressive door splits the same work in two:
;; ingest-open reads a bounded head synchronously — enough for the
;; first screens — and answers a tail state; ingest-tail-read then
;; hands over one chunk at a time to append to the document the head
;; built. A
;; count called loaded runs through both: the raw stream characters
;; the document already accounts for (a trailing \r held back
;; between pieces is counted only when the piece that resolves it
;; lands), which is what a resumed load skips and what progress is
;; measured in. The document the two phases build is ingest-file's
;; document: same builder, same break handling, same seams.

#?(:clj (def ^:private head-chars
             "How much text a progressive head aims to hold before the open
   returns: enough for the first screens, small enough to be quick."
             65536))

#?(:clj
   (defn file-length
         "The length of the file at path in bytes, or nil when it is not there."
         [path]
         (let [f (java.io.File. ^String path)]
              (when (.exists f) (.length f)))))

(defn from-pieces
      "A document of the pieces, built with eve.text's builder — the
   pieces are one text, split where they split. Document
   construction, so it runs where documents are built: inside a
   swap (see narcis.buffer/open-file!)."
      [pieces]
      (t/text-builder-text
       (reduce t/text-builder-add (t/text-builder) pieces)))

#?(:clj
   (defn ingest-open
         "Open path and read its head's text, progressively: {:pieces
      [s ...] :eol :loaded-chars n :total-bytes n :done? b :tail
      tail-or-nil}. Pure IO and string work — the pieces become a
      document with from-pieces, inside the caller's swap. The head
      holds at least :head characters (head-chars unless said
      otherwise) and the file's first line ending — the buffer's
      :eol — unless a 1 MB cap is reached first (a first line longer
      than the cap: :eol is then \"\\n\" on a guess; the tail's
      endings still load as breaks, only the ending a save writes
      back is the guess). When the whole file fits the head, :done?
      is true, the reader is closed and :tail is nil. Otherwise
      :tail is a host-side state for ingest-tail-read, holding the
      open reader and a \r the head ended on, if any (held back: it
      may be half of a \r\n the document must see whole)."
         [path & [{:keys [head] :or {head head-chars}}]]
         (let [file (java.io.File. ^String path)
               total (.length file)
               rdr (java.io.FileReader. file)
               buf (char-array (min head chunk-chars))]
              (loop [pieces [] eol nil pending-cr? false loaded 0]
                    (let [n (.read rdr buf)]
                         (if (neg? n)
                             (do (.close rdr)
                                 {:pieces pieces
                                  :eol (or eol (if pending-cr? "\r" "\n"))
                                  :loaded-chars loaded
                                  :total-bytes total
                                  :done? true
                                  :tail nil})
                             (let [s (String. buf 0 n)
                                   eol (or eol
                                           (when pending-cr?
                                                 (if (and (pos? n) (= (.charAt s 0) \newline)) "\r\n" "\r"))
                                           (first-eol s))
                                   ends-cr? (and (pos? n) (= (.charAt s (dec n)) \return))
                                   loaded (+ loaded n)
                                   pieces (conj pieces s)]
                                  (if (and (>= loaded head)
                                           (or (some? eol) (>= loaded chunk-chars)))
                                      (let [last-s (peek pieces)
                                            hold? (and ends-cr? (pos? (count last-s)))
                                            pieces (if hold?
                                                       (assoc pieces (dec (count pieces))
                                                              (subs last-s 0 (dec (count last-s))))
                                                       pieces)
                                            loaded (if hold? (dec loaded) loaded)]
                                           {:pieces pieces
                                            :eol (or eol "\n")
                                            :loaded-chars loaded
                                            :total-bytes total
                                            :done? false
                                            :tail {:rdr rdr :pending (if hold? "\r" "") :loaded loaded}})
                                      (recur pieces eol (and (nil? eol) ends-cr?) loaded)))))))))

;; ---------------------------------------------------------------------------
;; the skeleton: one scan of the whole file, reads by line
;;
;; A big file is not materialized whole to be looked at. One scan of
;; the file — bytes only, no document built — counts its lines and
;; its document characters a megabyte at a time and keeps the
;; per-chunk counts as a table. With the count, the file has its
;; full height from the start (the page's scroll bar is the
;; document's real size while almost none of it exists as a
;; document); with the table, any stretch of lines can be read out
;; of the file on demand — the part of the skeleton the user
;; scrolls to materializes, the rest stays a count.
;;
;; Counts are the document's own: a break is \n, or \r not followed
;; by \n (\r\n is one break and one document character); characters
;; are document characters (UTF-8 continuation bytes are not
;; characters). Lines are breaks + 1, as eve.text counts them.
;;
;; Counting is bulk, never a character walk: a chunk is decoded
;; once and its breaks, \r\n pairs and characters are counted by
;; the host's string machinery; the one short walk (byte-boundary)
;; runs only over the single chunk that holds the line a read
;; starts at. (Two ways that were tried and measured on this host:
;; a per-byte loop counts ~3 MB/s, and an indexOf walk over the
;; decoded string is far slower still — the host's replace-based
;; counting is the fast one, ~10 MB/s and one pass.)

#?(:clj
   (defn- utf8-carry-len
          "How many of buf's first n bytes form an incomplete UTF-8
      sequence at the end (0–3): those bytes are carried into the
      next chunk, so a character is never split by a chunk
      boundary."
          [^bytes buf n]
          (loop [k 1]
                (if (or (> k 3) (> k n))
                    0
                    (let [b (bit-and (aget buf (- n k)) 0xFF)]
                         (if (= (bit-and b 0xC0) 0x80)
                             (recur (inc k))
                             (let [need (cond (= (bit-and b 0xF8) 0xF0) 4
                                              (= (bit-and b 0xF0) 0xE0) 3
                                              (= (bit-and b 0xE0) 0xC0) 2
                                              :else 1)]
                                  (if (< k need) k 0))))))))

#?(:clj
   (defn chunk-stats
         "Bulk counts for one decoded chunk of file text: {:breaks n
      :pairs n :units n} — breaks (a \\r\\n pair is one), pairs
      among them, and characters in UTF-16 units, as the hosts
      count them. Document characters are units − pairs (the \\n
      of a pair is a stream character but not a document one);
      stream characters are units. The string must not end with a
      \\r (it may be half of a pair the next chunk resolves — the
      callers hold it out and prepend it there)."
         [^String s]
         (let [l (.length s)
               ncount (- l (.length (clojure.string/replace s "\n" "" )))
               rcount (- l (.length (clojure.string/replace s "\r" "" )))
               pairs (quot (- l (.length (clojure.string/replace s "\r\n" "" ))) 2)]
              {:breaks (+ ncount rcount (- pairs)) :pairs pairs :units l})))

#?(:clj
   (defn- decode-chunk
          "Decode buf's first m bytes as UTF-8, with carry (the previous
      chunk's incomplete tail bytes) in front: a String."
          [^bytes carry ^bytes buf m]
          (if (zero? (alength carry))
              (String. buf 0 m "UTF-8")
              (let [all (byte-array (+ (alength carry) m))]
                   (System/arraycopy carry 0 all 0 (alength carry))
                   (System/arraycopy buf 0 all (alength carry) m)
                   (String. all "UTF-8")))))

#?(:clj
   (defn- read-chunk-text
          "Read and decode one chunk of in: {:text s :bytes n :carry b
      :pend? b :eof? b}. :text is the chunk's decoded text with a
      \\r held from before prepended and a trailing \\r held out
      (:pend? — it travels to the next chunk the same way); :bytes
      is how many file bytes :text spans; :carry is the incomplete
      UTF-8 tail, carried as bytes."
          [^java.io.InputStream in ^bytes buf ^bytes carry pend?]
          (let [n (.read in buf)]
               (if (neg? n)
                   {:text nil :bytes 0 :n 0 :carry carry :pend? pend? :eof? true}
                   (let [cl (utf8-carry-len buf n)
                         m (- n cl)
                         s0 (decode-chunk carry buf m)
                         s (if pend? (str "\r" s0) s0)
                         hold? (and (pos? (.length s))
                                    (= \return (.charAt s (dec (.length s)))))]
                        {:text (if hold? (subs s 0 (dec (.length s))) s)
                         :bytes (+ (alength carry) m (if pend? 1 0) (if hold? -1 0))
                         :n n
                         :carry (byte-array (java.util.Arrays/copyOfRange buf m n))
                         :pend? hold?
                         :eof? false})))))

#?(:clj
   (defn scan-file!
         "Scan the file at path on a background thread: answer a
      host-side state atom {:lines n :chars n :bytes n
      :total-bytes n :chunks [[bytes breaks pairs units] ...]
      :done? b :stopped b}, updated as the scan goes — :lines and
      :chars are the document's counts for the part scanned so
      far, exact when :done?. The chunk table is the scan's index:
      summing it gives the [line byte char stream] position of any
      chunk's first line, the anchor a read by line starts from.
      Set :stopped to abandon the scan early."
         [path]
         (let [st (atom {:lines 1 :chars 0 :bytes 0
                         :total-bytes (file-length path)
                         :chunks [] :done? false :stopped false})
               run (fn []
                       (try
                        (with-open [in (java.io.FileInputStream. ^String path)]
                                   (let [buf (byte-array 1048576)]
                                        (loop [line 0 chars 0 stream 0 bytes 0 chunks []
                                               carry (byte-array 0) pend? false]
                                              (if (:stopped @st)
                                                  (swap! st assoc :lines (inc line) :chars chars :bytes bytes :chunks chunks)
                                                  (let [rd (read-chunk-text in buf carry pend?)
                                                        text (:text rd) cb (:bytes rd)]
                                                       (if (:eof? rd)
                               ;; end of file: flush what is held
                               ;; (a \r the file ends on is a
                               ;; whole break of its own)
                                                           (let [s (when (or pend? (pos? (alength carry))) (str (when pend? "\r") (when (pos? (alength carry)) (String. carry "UTF-8")))) {:keys [breaks pairs units]} (if s (chunk-stats s) {:breaks 0 :pairs 0 :units 0}) line (+ line breaks) chars (+ chars units (- pairs)) bytes (+ bytes (alength carry) (if pend? 1 0))] (swap! st assoc :lines (inc line) :chars chars :bytes bytes :chunks chunks :done? true))
                                                           (let [{:keys [breaks pairs units]} (chunk-stats text)
                                                                 line (+ line breaks)
                                                                 chars (+ chars units (- pairs))
                                                                 stream (+ stream units)
                                                                 bytes (+ bytes cb)
                                                                 chunks (conj chunks [cb breaks pairs units])]
                                                                (swap! st assoc :lines (inc line) :chars chars
                                                                       :bytes bytes :chunks chunks)
                                                                (recur line chars stream bytes chunks
                                                                       (:carry rd) (:pend? rd)))))))))
                        (catch #?(:cljs :default :default Throwable) t
                               (binding [*out* *err*]
                                        (println "narcis: scan of" path "stopped:" (ex-message t))))))]
              (doto (Thread. ^Runnable run)
                    (.setDaemon true)
                    (.start))
              st)))

#?(:clj
   (defn- chunk-anchor
          "The [line byte doc stream] at the start of the chunk holding
      line n, summed from the scan's chunk table — the table's end
      when the scan has not reached n yet."
          [chunks n]
          (loop [cs chunks line 0 byte 0 doc 0 stream 0]
                (if-let [[cb cbr cp cu] (first cs)]
                        (if (< (+ line cbr) n)
                            (recur (rest cs) (+ line cbr) (+ byte cb)
                                   (+ doc cu (- cp)) (+ stream cu))
                            [line byte doc stream])
                        [line byte doc stream]))))

#?(:clj
   (defn byte-offset-of-line
         "The approximate byte offset of line n, from scan's chunk
      table: exact at chunk starts, a proportional share inside
      the chunk holding the line, extrapolated past the scanned
      prefix by that prefix's average bytes per line. For display
      geometry (the load line), where what matters is that the
      scale — the file's byte length, exact from the start —
      never moves as the scan grows: a position computed this
      way settles as the scan passes it and never walks."
         [scan n]
         (loop [cs (:chunks @scan) line 0 byte 0]
               (if-let [[cb cbr _cp _cu] (first cs)]
                       (cond (< (+ line cbr) n)
                             (recur (rest cs) (+ line cbr) (+ byte cb))
                             (pos? cbr)
                             (+ byte (long (* (/ (- n line) cbr) cb)))
                             :else byte)
                       (if (pos? line)
                           (+ byte (long (* (/ byte line) (- n line))))
                           0)))))

#?(:clj
   (defn- chunk-anchor-by-char
          "The [line byte doc stream] at the start of the chunk holding
      document character offset, summed from the chunk table."
          [chunks offset]
          (loop [cs chunks line 0 byte 0 doc 0 stream 0]
                (if-let [[cb cbr cp cu] (first cs)]
                        (if (< (+ doc cu (- cp)) offset)
                            (recur (rest cs) (+ line cbr) (+ byte cb)
                                   (+ doc cu (- cp)) (+ stream cu))
                            [line byte doc stream])
                        [line byte doc stream]))))

#?(:clj
   (defn- skip-to
          "Position in at byte offset ib."
          [^java.io.FileInputStream in ib]
          (loop [left ib]
                (when (pos? left)
                      (let [n (.skip in left)]
                           (when (pos? n) (recur (- left n))))))))

#?(:clj
   (defn- byte-boundary
          "Byte-account buf's first n bytes, with line the line in
      progress at their start and prev-cr? a \\r held from before,
      up to the start of line target: {:byte-idx i :pairs p
      :units u} — the byte the line starts at and the counts of
      the bytes before it — or nil when target starts later. The
      start of a line is the byte just past the break that ends
      the line before it; for a lone \\r the break completes at
      the byte that resolves it, which starts the line and is not
      consumed."
          [^bytes buf n line target prev-cr?]
          (loop [i 0 line line pairs 0 units 0 prev-cr? prev-cr?]
                (if (== line target)
                    {:byte-idx i :pairs pairs :units units}
                    (when (< i n) (let [b (aget buf i)]
                                       (cond
                                        prev-cr?
                                        (if (== b 10)
                                            (recur (inc i) (inc line) (inc pairs) (+ units 2) false)
                                            (recur i (inc line) pairs (inc units) false))
                                        (== b 10) (recur (inc i) (inc line) pairs (inc units) false)
                                        (== b 13) (recur (inc i) line pairs units true)
                                        (pos? b) (recur (inc i) line pairs (inc units) false)
                                        :else (recur (inc i) line pairs
                                                     (+ units (cond (== (bit-and b 0xC0) 0x80) 0
                                                                    (<= 0xF0 (bit-and b 0xFF) 0xF7) 2
                                                                    :else 1))
                                                     false))))))))

#?(:clj
   (defn file-window
         "Lines [start, start+count) of the file at path, read by the
      scan's chunk table: {:lines [{:n n :start char :text s}]
      :stream-start n :stream-end n :eof? b :total-lines n-or-nil
      :total-chars n-or-nil}. The text is the document's (breaks
      stripped, as a document's lines read; :start is a line's
      document character offset; the last line is present even when
      it is the empty line a trailing break leaves). :stream-start
      and :stream-end are the window's span in stream characters —
      what a reader counts — for a caller resuming a reader where
      the window ends. :eof? says the read reached the end of the
      file, so a stretch that did not is provably short of the last
      line. Totals are the scan's, present once it has finished.
      Reading starts from the chunk table's anchor at or before
      start; whole chunks between it and the window are counted in
      bulk, and only the chunk holding line start is walked, byte
      by byte, to the line — so a window deep in a file the scan
      has not reached yet still costs one bulk pass over the bytes
      before it. The scan is never waited on."
         [path scan start nlines]
         (let [[il ib ic isc] (chunk-anchor (:chunks @scan) start)
               total-lines (when (:done? @scan) (:lines @scan))
               total-chars (when (:done? @scan) (:chars @scan))
               ln (atom il) doc (atom ic) stm (atom isc)
               sb (StringBuilder.) pend (atom false)
               out (atom []) sstart (atom nil) send (atom nil) eof (atom false)
               emit! (fn [break-units]
                         (let [t (str sb) n @ln]
                              (when (and (>= n start) (< n (+ start nlines)))
                                    (swap! out conj {:n n :start @doc :text t})
                                    (when (nil? @sstart) (reset! sstart @stm))
                                    (reset! send (+ @stm (count t) break-units)))
                              (swap! doc + (count t) 1)
                              (swap! stm + (count t) break-units)
                              (swap! ln inc)
                              (.setLength sb 0)))]
              (with-open [in (java.io.FileInputStream. ^String path)]
                         (skip-to in ib)
         ;; bulk phase: count whole chunks until the one holding
         ;; line start, land on the line inside it, then hand the
         ;; walk the file from that byte on
                         (let [buf (byte-array 1048576)
                               head (loop [carry (byte-array 0) pend? false]
                                          (let [n (.read in buf)]
                                               (if (neg? n)
                                                   (do (reset! eof true) nil)
                                                   (let [cl (utf8-carry-len buf n)
                                                         m (- n cl)
                                                         s (let [s0 (decode-chunk carry buf m)]
                                                                (if pend? (str "\r" s0) s0))
                                                         hold? (and (pos? (.length s))
                                                                    (= \return (.charAt s (dec (.length s)))))
                                                         {:keys [breaks pairs units]}
                                                         (chunk-stats (if hold?
                                                                          (subs s 0 (dec (.length s)))
                                                                          s))]
                                                        (if (< (+ @ln breaks) start)
                                                            (do (swap! ln + breaks)
                                                                (swap! doc + (- units pairs))
                                                                (swap! stm + units)
                                                                (recur (byte-array (java.util.Arrays/copyOfRange buf m n))
                                                                       hold?))
                                                            (let [bb (byte-boundary buf n @ln start pend?)]
                                                                 (if (nil? bb)
                                  ;; the boundary is the first
                                  ;; byte of the next chunk (this
                                  ;; one ends on the break): count
                                  ;; the chunk and go on
                                                                     (do (swap! ln + breaks)
                                                                         (swap! doc + (- units pairs))
                                                                         (swap! stm + units)
                                                                         (recur (byte-array (java.util.Arrays/copyOfRange buf m n))
                                                                                hold?))
                                                                     (do (swap! doc + (- (:units bb) (:pairs bb)))
                                                                         (swap! stm + (:units bb))
                                                                         (reset! ln start)
                                                                         (java.io.SequenceInputStream.
                                                                          (java.io.ByteArrayInputStream. buf (:byte-idx bb) (- n (:byte-idx bb)))
                                                                          in)))))))))]
                              (when head
                                    (let [rdr (java.io.InputStreamReader. head "UTF-8")
                                          cbuf (char-array 65536)
                                          step (fn step [c]
                                                   (cond
                                                    (= c \return) (reset! pend true)
                                                    (= c \newline) (emit! 1)
                                                    :else (.append sb c)))]
                                         (loop []
                                               (let [n (.read rdr cbuf)]
                                                    (if (neg? n)
                                                        (do (reset! eof true)
                                                            (when @pend
                                                                  (reset! pend false)
                                                                  (emit! 1))
                                                            (emit! 0))
                                                        (do (loop [i 0]
                                                                  (when (and (< i n) (< @ln (+ start nlines)))
                                                                        (let [c (aget cbuf i)]
                                                                             (if @pend
                                                                                 (do (reset! pend false)
                                                                                     (if (= c \newline)
                                                                                         (emit! 2)
                                                                                         (do (emit! 1) (step c))))
                                                                                 (step c)))
                                                                        (recur (inc i))))
                                                            (when (< @ln (+ start nlines))
                                                                  (recur))))))))))
              {:lines @out
               :stream-start (or @sstart @stm)
               :stream-end (or @send @stm)
               :eof? @eof
               :total-lines total-lines
               :total-chars total-chars})))

#?(:clj
   (defn file-tail
         "The file's last span-bytes bytes as raw text, read by
      position: {:text s :start-byte n :bytes n}. The text starts
      on a line boundary: at the file's start when the span
      reaches it, else just past the first break at or after the
      span's first byte (a break byte is never part of a
      multi-byte character, so the alignment is exact) — the
      partial line the span opens in the middle of belongs to
      the territory before it. :start-byte is where :text
      starts; :bytes is the text's span in file bytes. The tail
      of a file is the one stretch a reader wants before any
      index exists: it is read here with no scan at all, and
      what it returns cannot change while the rest of the file
      is still being read."
         [path span-bytes]
         (let [total (file-length path)
               want (max 0 (- total span-bytes))]
              (with-open [in (java.io.FileInputStream. ^String path)]
                         (skip-to in want)
                         (let [buf (byte-array 1048576)
               ;; align: when the span opens mid-file, drop
               ;; everything through the first break byte
                               start (if (zero? want)
                                         0
                                         (loop [off want]
                                               (let [n (.read in buf)]
                                                    (if (neg? n)
                                                        off
                                                        (let [i (loop [i 0]
                                                                      (if (and (< i n) (not= 10 (aget buf i)))
                                                                          (recur (inc i))
                                                                          i))]
                                                             (if (< i n)
                                                                 (+ off i 1)
                                                                 (recur (+ off n))))))))
               ;; the alignment loop consumed the stream when it
               ;; read: reopen at the aligned start and read the
               ;; text out with the chunk reader (UTF-8 carry and
               ;; a held \r travel as they do everywhere else)
                               ]
                              (with-open [in2 (java.io.FileInputStream. ^String path)]
                                         (skip-to in2 start)
                                         (loop [sb (StringBuilder.) carry (byte-array 0) pend? false bytes 0]
                                               (let [rd (read-chunk-text in2 buf carry pend?)]
                                                    (if (:eof? rd)
                                                        (let [s (str (when pend? "\r")
                                                                     (when (pos? (alength carry))
                                                                           (String. carry "UTF-8")))]
                                                             {:text (str sb s)
                                                              :start-byte start
                                                              :bytes (+ bytes (alength carry) (if pend? 1 0))})
                                                        (do (.append sb (:text rd))
                                                            (recur sb (:carry rd) (:pend? rd)
                                                                   (+ bytes (:bytes rd)))))))))))))

;; Reading by byte. While a big file loads, the page addresses it by
;; byte — the one coordinate exact from the first moment — and a
;; window read by byte needs no line count at all: seek, back up to
;; the start of the line, read. Its line numbers and character
;; offsets are exact where the scan has passed (counted from the
;; scan's chunk table, at most one chunk read), and extrapolated
;; from the scanned prefix's averages where it has not, flagged
;; :approx?. (The line route counted every break from the scan's end
;; to the wanted byte instead: 245 s for a window 30% into a 13.9 GB
;; file mid-scan.)

#?(:clj
   (defn- read-bytes-at
          "n bytes of the file at path from byte offset off (fewer at its end)."
          [path off n]
          (with-open [in (java.io.FileInputStream. ^String path)]
                     (skip-to in off)
                     (let [buf (byte-array n)]
                          (loop [got 0]
                                (let [k (if (< got n) (.read in buf got (- n got)) -1)]
                                     (if (pos? k)
                                         (recur (+ got k))
                                         (if (= got n) buf (java.util.Arrays/copyOf buf got)))))))))

#?(:clj
   (defn- scan-position-at-byte
          "[line doc stream] at byte b — a line start — counted exactly from
      the scan's chunk table when the scan has passed b, else nil."
          [path scan b]
          (when scan
                (let [{:keys [chunks]} @scan]
                     (loop [cs chunks line 0 byte 0 doc 0 stream 0]
                           (when-let [[cb cbr cp cu] (first cs)]
                                     (if (<= (+ byte cb) b)
                                         (recur (rest cs) (+ line cbr) (+ byte cb) (+ doc (- cu cp)) (+ stream cu))
               ;; b is inside this chunk: count the chunk's text
               ;; from its start to b (b follows a break, so the
               ;; prefix ends on a whole break)
                                         (let [{:keys [breaks pairs units]}
                                               (if (= b byte)
                                                   {:breaks 0 :pairs 0 :units 0}
                                                   (chunk-stats (String. ^bytes (read-bytes-at path byte (- b byte)) "UTF-8")))]
                                              [(+ line breaks) (+ doc (- units pairs)) (+ stream units)]))))))))

#?(:clj
   (defn file-window-at-byte
         "The nlines lines of the file at path ending with the line that
      holds byte offset b, read by position: {:lines [{:n :start :text
      :byte}] :start-byte n :end-byte n :stream-start n :stream-end n
      :approx? b :eof? b}. :byte is each line's first byte (exact);
      :n and :start (line number, document char offset) are exact
      when the scan has passed the window, else estimated from the
      scanned prefix, and :approx? says so. :end-byte is past the
      last line's break; :eof? says the window reaches the file's end.
      No break is counted outside the window and at most one chunk
      of the scan, so it costs the same anywhere in the file, scanned
      or not."
         [path scan b nlines]
         (let [total (file-length path)
               b (max 0 (min b (dec total)))
           ;; the end of b's line: past the first \n at or after b,
           ;; or the file's end
               line-end (loop [off b]
                              (let [chunk (read-bytes-at path off 65536)
                                    n (alength ^bytes chunk)
                                    i (loop [i 0] (if (and (< i n) (not= 10 (aget ^bytes chunk i))) (recur (inc i)) i))]
                                   (cond (< i n) (+ off i 1)
                                         (< n 65536) (+ off n)
                                         :else (recur (+ off n)))))
           ;; back from there until the block holds nlines whole lines
               [start buf] (loop [span (max 65536 (* nlines 256))]
                                 (let [start (max 0 (- line-end span))
                                       buf (read-bytes-at path start (- line-end start))
                                       breaks (loop [i 0 k 0]
                                                    (if (< i (alength ^bytes buf))
                                                        (recur (inc i) (if (= 10 (aget ^bytes buf i)) (inc k) k))
                                                        k))]
                                      (if (or (zero? start) (> breaks nlines))
                                          [start buf]
                                          (recur (* span 4)))))
               n (alength ^bytes buf)
           ;; the line starts in the block: its start when that is the
           ;; file's, else past its first break (the partial line
           ;; before it belongs to the territory before)
               starts (loop [i 0 acc (if (zero? start) [0] [])]
                            (if (< i n)
                                (recur (inc i) (if (and (= 10 (aget ^bytes buf i)) (< (inc i) n))
                                                   (conj acc (inc i)) acc))
                                acc))
               starts (vec (take-last nlines starts))
               rows (mapv (fn [k]
                              (let [s (nth starts k)
                                    e (if (< (inc k) (count starts)) (nth starts (inc k)) n)
                                    nl? (and (> e s) (= 10 (aget ^bytes buf (dec e))))
                                    cr? (and nl? (> (dec e) s) (= 13 (aget ^bytes buf (- e 2))))
                                    te (- e (if nl? 1 0) (if cr? 1 0))]
                                   {:byte (+ start s)
                                    :text (String. ^bytes buf (int s) (int (- te s)) "UTF-8")
                                    :break-units (cond cr? 2 nl? 1 :else 0)}))
                          (range (count starts)))
               b0 (:byte (first rows))
               exact (scan-position-at-byte path scan b0)
               [line0 doc0 stream0]
               (or exact
                   (let [{:keys [lines chars bytes]} (when scan @scan)
                         bytes (or bytes 0)]
                        (if (pos? bytes)
                            [(long (* (dec lines) (/ b0 bytes)))
                             (long (* chars (/ b0 bytes)))
                             (long (* chars (/ b0 bytes)))]
                            [(quot b0 100) b0 b0])))
               [lines _ stream-end]
               (reduce (fn [[acc doc stream] [k r]]
                           [(conj acc {:n (+ line0 k) :start doc :text (:text r) :byte (:byte r)})
                            (+ doc (count (:text r)) 1)
                            (+ stream (count (:text r)) (:break-units r))])
                       [[] doc0 stream0]
                       (map-indexed vector rows))]
              {:lines lines
               :start-byte b0
               :end-byte line-end
               :stream-start stream0
               :stream-end stream-end
               :approx? (nil? exact)
               :eof? (>= line-end total)})))

#?(:clj
   (defn file-line-of
         "The line holding document character offset in the file at
      path, counted from the scan's chunk table (from the file's
      start when the scan has not got there). A break character
      belongs to the line it ends; the offset one past the last
      character is the last line. Whole chunks before the offset
      are counted in bulk, as in file-window; only the chunk
      holding the offset is walked."
         [path scan offset]
         (let [[il ib ic _isc] (if scan
                                   (chunk-anchor-by-char (:chunks @scan) offset)
                                   [0 0 0 0])]
              (with-open [in (java.io.FileInputStream. ^String path)]
                         (skip-to in ib)
                         (let [buf (byte-array 1048576)
               ;; bulk phase: count whole chunks the offset is
               ;; strictly past, then hand the chunk holding it to
               ;; the walk
                               head (loop [pos ic line il carry (byte-array 0) pend? false]
                                          (let [rd (read-chunk-text in buf carry pend?)]
                                               (if (:eof? rd)
                          ;; the offset is at or past the end: the
                          ;; last line (a held \r is one break more)
                                                   [nil pos (if pend? (inc line) line)]
                                                   (let [{:keys [breaks pairs units]} (chunk-stats (:text rd))
                                                         cdoc (- units pairs)]
                                                        (if (< (+ pos cdoc) offset)
                                                            (recur (+ pos cdoc) (+ line breaks)
                                                                   (:carry rd) (:pend? rd))
                              ;; hand off: the held \r (if any) as
                              ;; a byte, then this chunk's raw
                              ;; bytes, then the rest of the file
                                                            [(java.io.SequenceInputStream.
                                                              (if pend?
                                                                  (java.io.SequenceInputStream.
                                                                   (java.io.ByteArrayInputStream. (byte-array [13]))
                                                                   (java.io.ByteArrayInputStream. buf 0 (:n rd)))
                                                                  (java.io.ByteArrayInputStream. buf 0 (:n rd)))
                                                              in)
                                                             pos line]))))) [in pos line] head] (if (nil? in)
                                                                                                    line
                                                                                                    (let [rdr (java.io.InputStreamReader. in "UTF-8")
                                                                                                          cbuf (char-array 65536)]
                                                                                                         (loop [pos pos line line pending-cr? false]
                                                                                                               (let [n (.read rdr cbuf)]
                                                                                                                    (if (neg? n)
                       ;; an offset at the very end is on the last
                       ;; line; a trailing held \r was a break, so
                       ;; one line more
                                                                                                                        (if pending-cr? (inc line) line)
                                                                                                                        (let [r (loop [i 0 pos pos line line pending-cr? pending-cr?]
                                                                                                                                      (if (< i n)
                                                                                                                                          (let [c (aget cbuf i)
                                         ;; a held \r resolves against
                                         ;; this char: half of a pair
                                         ;; when this is \n (counted by
                                         ;; the \n), a whole break
                                         ;; otherwise — and only then
                                         ;; does the line turn, before
                                         ;; this char is placed
                                                                                                                                                line (if (and pending-cr? (not= c \newline))
                                                                                                                                                         (inc line)
                                                                                                                                                         line)
                                                                                                                                                pair? (and pending-cr? (= c \newline))]
                                                                                                                                               (cond
                                       ;; the \n of a \r\n pair holds
                                       ;; no document position of
                                       ;; its own, but the pair is
                                       ;; one break: the line turns
                                       ;; here
                                                                                                                                                pair? (recur (inc i) pos (inc line) false)
                                                                                                                                                (= pos offset) [:at line]
                                                                                                                                                :else (recur (inc i) (inc pos)
                                                                                                                                                             (if (= c \newline) (inc line) line)
                                                                                                                                                             (= c \return))))
                                                                                                                                          [:more pos line pending-cr?]))]
                                                                                                                             (if (= :at (first r))
                                                                                                                                 (second r)
                                                                                                                                 (recur (nth r 1) (nth r 2) (nth r 3))))))))))))))

#?(:clj
   (defn line-of-byte
         "The line holding byte offset in the file at path: the count
      of the breaks whose last byte lies before the offset's
      byte. A break belongs to the line it ends, so a byte that
      is itself a break's last byte stands on the line the break
      ends; and an offset at or past the file's end stands on
      the file's last byte, so the end offset answers the last
      line — never the empty line a trailing break leaves.
      Counted from the scan's chunk table where it reaches and
      in bulk past it, on the raw bytes decoded as Latin-1 for
      the counting only: a break byte is never part of a
      multi-byte character, so the count is the file's own.
      The scan is never waited on."
         [path scan byte-off]
         (let [total (file-length path)]
              (if (or (nil? total) (not (pos? total)) (not (pos? byte-off)))
                  0
                  (let [limit (min byte-off (dec total))
               ;; the table's whole chunks ending at or before
               ;; the limit, and the byte counting resumes from:
               ;; a chunk's byte count is the span of its text, so
               ;; the resume byte is a text boundary — a \r held
               ;; out of a counted chunk stands at it, uncounted,
               ;; and is the first byte counted here
                        [line abyte] (loop [cs (if scan (:chunks @scan) [])
                                            line 0 b 0]
                                           (if-let [[cb cbr _cp _cu] (first cs)]
                                                   (if (<= (+ b cb) limit)
                                                       (recur (rest cs) (+ line cbr) (+ b cb))
                                                       [line b])
                                                   [line b]))]
                       (if (>= abyte limit)
                           line
                           (with-open [in (java.io.FileInputStream. ^String path)]
                                      (skip-to in abyte)
                                      (let [buf (byte-array 1048576)]
                                           (loop [line line b abyte prev-cr? false]
                                                 (let [n (.read in buf)]
                                                      (if (neg? n)
                                                          line
                                                          (let [final? (>= (+ b n) limit)
                                                                k (if final? (- limit b) n)
                             ;; a trailing \r is held out of the
                             ;; count: it is counted as the next
                             ;; piece's prefix, where what follows
                             ;; it resolves it — or by the peek at
                             ;; the limit, where it ends the count
                                                                trail? (and (pos? k) (= 13 (aget buf (dec k))))
                                                                m (if trail? (dec k) k)
                                                                s (String. (if prev-cr?
                                                                               (let [all (byte-array (inc m))]
                                                                                    (aset all 0 (byte 13))
                                                                                    (System/arraycopy buf 0 all 1 m)
                                                                                    all)
                                                                               buf)
                                                                           0 (if prev-cr? (inc m) m) "ISO-8859-1")
                                                                line (+ line (:breaks (chunk-stats s)))]
                                                               (cond
                                                                (not final?) (recur line (+ b n) trail?)
                           ;; the limit's byte resolves the held
                           ;; \r: when it is a \n the pair completes
                           ;; there — its break belongs past this
                           ;; count; anything else (or the file's
                           ;; end) leaves the \r a break of its own
                                                                trail? (let [peek (if (< k n)
                                                                                      (aget buf k)
                                                                                      (.read in))]
                                                                            (if (= 10 peek) line (inc line)))
                                                                :else line)))))))))))))

;; A find over the file reads it as normalized text a chunk at a
;; time, in one pass — the same slice walk narcis.ops runs over a
;; document, over the file's stream instead. Positions are document
;; character offsets throughout.

#?(:clj
   (defn- read-normalized-chunk
          "Read one chunk of rdr as normalized text: {:text s :eof? b
      :pending s}. A trailing \r is held in :pending (it may be half
      of a \r\n the next chunk resolves); pass it back in."
          [^java.io.Reader rdr pending buf]
          (let [n (.read rdr buf)]
               (if (neg? n)
                   {:text (normalize-breaks pending) :eof? true :pending ""}
                   (let [s (str pending (String. buf 0 n))
                         hold? (and (pos? (count s))
                                    (= \return (.charAt s (dec (count s)))))]
                        {:text (normalize-breaks (if hold? (subs s 0 (dec (count s))) s))
                         :eof? false
                         :pending (if hold? "\r" "")})))))

#?(:clj
   (defn file-find-forward
         "Start of the first match of query at or after document offset
      from in the file at path, or nil. One streaming pass."
         [path query from]
         (let [q (count query)
               buf (char-array chunk-chars)]
              (with-open [rdr (java.io.FileReader. ^String path)]
                         (loop [pos 0 carry "" pending ""]
                               (let [{:keys [text eof? pending]} (read-normalized-chunk rdr pending buf)
                                     window (str carry text)
                                     wstart (- pos (count carry))
                                     i (let [i (clojure.string/index-of window query (max 0 (- from wstart)))]
                                            (when (and i (>= (+ wstart i) from)) i))]
                                    (cond
                                     i (+ wstart i)
                                     eof? nil
                                     :else (let [keep (min (count window) (max 0 (dec q)))]
                                                (recur (+ wstart (count window)) (subs window (- (count window) keep)) pending)))))))))

#?(:clj
   (defn file-find-backward
         "Start of the last match of query beginning before document
      offset from in the file at path (the whole file when from is
      nil), or nil. One streaming pass, remembering the last match."
         [path query from]
         (let [q (count query)
               buf (char-array chunk-chars)]
              (with-open [rdr (java.io.FileReader. ^String path)]
                         (loop [pos 0 carry "" pending "" best nil]
                               (let [{:keys [text eof? pending]} (read-normalized-chunk rdr pending buf)
                                     window (str carry text)
                                     wstart (- pos (count carry))
                                     limit (if (nil? from) Long/MAX_VALUE from)
                                     best (loop [i (clojure.string/index-of window query) best best]
                                                (if (and i (< (+ wstart i) limit))
                                                    (recur (clojure.string/index-of window query (inc i)) (+ wstart i))
                                                    best))]
                                    (if eof?
                                        best
                                        (let [keep (min (count window) (max 0 (dec q)))]
                                             (recur (+ wstart (count window)) (subs window (- (count window) keep)) pending best)))))))))

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

