(ns narcis.probe-editext
  "Editable extents, end to end: open the 64 MB file in a fresh
   store; while the buffer is still partial, land two mid-file
   stretches as extents (a file-window read + note-extent!, as
   the launcher does), edit inside them through the ops path —
   a plain insertion, a line-splitting insertion that rekeys
   the later extent, and an insertion in that later extent at
   its rekeyed position — and check that what is NOT covered
   keeps the :preparing refusal. Then let the load finish and
   assert the finished document is the file's text with exactly
   those three edits, in counts and in full.
   Run: scripts/jolt-run.sh narcis.probe-editext <store-dir> [file]"
  (:require [narcis.store :as store]
            [narcis.buffer :as buffer]
            [narcis.ops :as ops]
            [narcis.text :as text]))

(def ^:private file-default
  "/home/hatch/workspace/rope-deftype-lane/big64.txt")

(def ^:private failures (atom 0))

(defn- check [label ok? & [detail]]
  (println (if ok? "PASS" "FAIL") label (or detail ""))
  (when-not ok? (swap! failures inc))
  ok?)

(defn- line-start [s n]
  "The offset in string s where 0-based line n starts."
  (loop [i 0 idx 0]
    (if (= i n)
      idx
      (recur (inc i) (inc (.indexOf s "\n" idx))))))

(defn- count-breaks [s]
  (loop [n 0 idx 0]
    (let [j (.indexOf s "\n" idx)]
      (if (neg? j) n (recur (inc n) (inc j))))))

(defn- insert-str [s off ins]
  (str (subs s 0 off) ins (subs s off)))

(defn- note-window!
  "Land file lines [start, start+n) of path as an extent, the
   launcher's way: a file-window read on the scan, then
   note-extent! with its stream span."
  [ed path start n]
  (let [w (text/file-window path (buffer/scan-of path) start n)]
    (when (and (= n (count (:lines w))) (not (:eof? w)))
      (buffer/note-extent! ed path (:lines w) (:stream-start w) (:stream-end w))
      (contains? (buffer/extents ed path) start))))

(defn -main [& [dir path]]
  (let [path (or path file-default)
        dir (or dir (str (System/getProperty "java.io.tmpdir")
                         "/narcis-editext-" (System/nanoTime)))
        raw (slurp path)
        st (store/open-store {:dir dir :name store/store-name})
        ed (buffer/open-editor st)
        t0 (System/nanoTime)]
    (buffer/open-file! ed path)
    ;; the scan lands first; the buffer is partial well past it
    (loop [n 0]
      (when (and (not (:done? (buffer/skeleton path))) (< n 600))
        (Thread/sleep 100)
        (recur (inc n))))
    (check "scan done" (:done? (buffer/skeleton path)))
    (check "buffer partial while editing" (buffer/partial? ed path))
    ;; two mid-file stretches land as extents: A holds the
    ;; zqx300000 token line (0-based 299999), B the zqx400000 one
    (check "extent A noted at 299990" (note-window! ed path 299990 40))
    (check "extent B noted at 399990" (note-window! ed path 399990 40))
    ;; edit 1: a plain insertion before the token, inside A.
    ;; No edit has happened yet, so document offsets are the
    ;; file's own offsets here.
    (let [off1 (.indexOf raw "zqx300000")
          res (ops/apply-op ed {:op :insert-text :path path
                                :caret off1 :text "EDITED-"})]
      (check "edit 1 (insert in A) answers ok" (:ok res) (pr-str (select-keys res [:error :preparing])))
      (check "edit 1 caret lands past the insertion" (= (:caret res) (+ off1 7))
             (str "caret " (:caret res))))
    ;; edit 2: split a line inside A — two new lines, so B must
    ;; be rekeyed in the same swap. The target is found through
    ;; A's own document, at its stored position.
    (let [a (get (buffer/extents ed path) 299990)
          _ (check "A still keyed at 299990" (some? a))
          caret2 (+ (:start-char a) (text/line-start-offset (:doc a) 20))
          res (ops/apply-op ed {:op :insert-text :path path
                                :caret caret2 :text "\nSPLIT\n"})]
      (check "edit 2 (line split in A) answers ok" (:ok res) (pr-str (select-keys res [:error :preparing]))))
    (check "B rekeyed to 399992 by the split"
           (and (contains? (buffer/extents ed path) 399992)
                (not (contains? (buffer/extents ed path) 399990)))
           (str "keys " (sort (keys (buffer/extents ed path)))))
    ;; edit 3: an insertion in B, addressed through B's rekeyed
    ;; position — the answer the frame law promises.
    (let [b (get (buffer/extents ed path) 399992)
          btext (text/slice-string (:doc b) 0 (text/chars (:doc b)))
          caret3 (+ (:start-char b) (.indexOf btext "zqx400000"))
          res (ops/apply-op ed {:op :insert-text :path path
                                :caret caret3 :text "TAIL-EDIT-"})]
      (check "edit 3 (insert in rekeyed B) answers ok" (:ok res) (pr-str (select-keys res [:error :preparing]))))
    ;; what no extent covers keeps the partial refusal: a caret
    ;; in unhydrated territory, a selection straddling A's end,
    ;; and a whole-text command
    (let [off100k (.indexOf raw "zqx100000")
          res (ops/apply-op ed {:op :insert-text :path path
                                :caret off100k :text "X"})]
      (check "uncovered caret refused :preparing" (and (:preparing res) (not (:ok res)))
             (pr-str (select-keys res [:ok :preparing]))))
    (let [a (get (buffer/extents ed path) 299990)
          end (+ (:start-char a) (:chars a))
          res (ops/apply-op ed {:op :insert-text :path path
                                :sel [(dec end) (+ end 3)] :text "X"})]
      (check "straddling selection refused :preparing" (and (:preparing res) (not (:ok res)))
             (pr-str (select-keys res [:ok :preparing]))))
    (let [res (ops/apply-op ed {:op :format-all :path path})]
      (check "whole-text op refused :preparing" (and (:preparing res) (not (:ok res)))
             (pr-str (select-keys res [:ok :preparing]))))
    (check "buffer still partial after the edits" (buffer/partial? ed path))
    ;; let the load finish
    (loop [n 0]
      (when (and (buffer/partial? ed path) (< n 800))
        (when (zero? (mod n 15))
          (println "t+" (format "%.0f s" (/ (double (- (System/nanoTime) t0)) 1e9))
                   "loaded:" (:loaded (get (buffer/loading-map ed) path))
                   "extents:" (count (buffer/extent-ranges ed path))))
        (Thread/sleep 2000)
        (recur (inc n))))
    (check "load finished whole" (not (buffer/partial? ed path))
           (str "after " (format "%.0f s" (/ (double (- (System/nanoTime) t0)) 1e9))))
    ;; the finished document is the file with exactly the edits:
    ;; the three insertions sit at their FILE offsets, applied
    ;; back to front so the offsets stay the file's
    (let [off1 (.indexOf raw "zqx300000")
          off2 (line-start raw 300010)
          off3 (.indexOf raw "zqx400000")
          expected (-> raw
                       (insert-str off3 "TAIL-EDIT-")
                       (insert-str off2 "\nSPLIT\n")
                       (insert-str off1 "EDITED-"))
          [chars lines] (buffer/read ed path
                                     (fn [s] [(text/chars (:root s))
                                              (text/lines (:root s))]))]
      (check "finished chars are the file's plus the insertions"
             (= chars (count expected)) (str chars " vs " (count expected)))
      (check "finished lines are the file's plus the split's two"
             (= lines (inc (count-breaks expected))) (str lines))
      (check "finished document is the file with exactly the edits"
             (= expected (buffer/buffer-text ed path))))
    (println "PROBE-EDITEXT" (if (zero? @failures) "PASS" "FAIL")
             @failures "failures")
    (System/exit (if (zero? @failures) 0 1))))
