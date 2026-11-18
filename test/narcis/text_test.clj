(ns narcis.text-test
  "narcis.text against a plain-string model: seeded random edits, and
   after every one the document's length, line count, whole text, each
   line read (by number, by offset, as a window) and a slice must be the
   string's own. Then the file door: a file read in through the builder
   and written back is the same bytes, for every line ending.

   (eve.text's own suites — the 30 design gates, balance under edits,
   the builder — run in its library, at this Eve pin; this is the layer
   Narcis reads it through.)"
  (:require [clojure.string :as str]
            [narcis.test-util :as test-util]
            [narcis.text :as text]))

(defn- lcg [seed] (atom seed))
(defn- rnd [rng n]
  (let [s (swap! rng (fn [x] (mod (+ (* x 1103515245) 12345) 2147483648)))]
    (if (zero? n) 0 (mod s n))))

(defn- check [label expected actual]
  (when (not= expected actual)
    (throw (ex-info (str "TEXT-TEST MISMATCH " label) {:expected expected :actual actual}))))

(defn- model-lines [s] (str/split s #"\n" -1))

(defn- model-starts [ls]
  (vec (reductions + 0 (map (fn [l] (inc (count l))) ls))))

(defn- agree!
  "doc reads as the string s does."
  [label doc s rng]
  (let [ls (model-lines s)
        starts (model-starts ls)
        n (count ls)]
    (check (str label " chars") (count s) (text/chars doc))
    (check (str label " lines") n (text/lines doc))
    (check (str label " to-string") s (text/to-string doc))
    (dotimes [_ 6]
      (let [i (rnd rng n)
            off (rnd rng (inc (count s)))
            line (loop [k 0] (if (and (< (inc k) n) (<= (nth starts (inc k)) off)) (recur (inc k)) k))]
        (check (str label " line-at " i) (nth ls i) (text/line-at doc i))
        (check (str label " line-start " i) (nth starts i) (text/line-start-offset doc i))
        (check (str label " offset->line " off) line (text/offset->line doc off))
        (check (str label " offset->line-col " off) [line (- off (nth starts line))] (text/offset->line-col doc off))
        (check (str label " line-col->offset " i)
               (+ (nth starts i) (min 3 (count (nth ls i))))
               (text/line-col->offset doc i 3))
        (let [k (inc (rnd rng 40))
              want (mapv (fn [j] {:n j :start (nth starts j) :text (nth ls j)})
                         (range i (min n (+ i k))))]
          (check (str label " window " i "+" k) want (text/window doc i k))
          (check (str label " lines-window") (mapv :text want) (text/lines-window doc i k)))
        (let [a (rnd rng (inc (count s)))
              b (min (count s) (+ a (rnd rng 200)))]
          (check (str label " slice") (subs s a b) (text/slice-string doc a b)))))
    (check (str label " line past the end") "" (text/line-at doc n))
    (check (str label " start past the end") (count s) (text/line-start-offset doc n))))

(def ^:private inserts
  ["x" "(" ")" " " "\n" "ab\ncd" "\n\n" "λ" "(defn f [x]\n  (+ x 1))\n" "" ";; note"])

(defn- model-edits!
  "The same walk with the model edited independently of the document."
  [seed steps start]
  (let [rng (lcg seed)]
    (loop [i 0 doc (text/from-string start) s start]
      (agree! (str "seed " seed " step " i) doc s rng)
      (when (< i steps)
        (let [a (rnd rng (inc (count s)))
              b (min (count s) (+ a (if (zero? (rnd rng 3)) (rnd rng 60) 0)))
              ins (nth inserts (rnd rng (count inserts)))
              op (rnd rng 3)]
          (case op
            0 (recur (inc i) (text/insert doc a ins) (str (subs s 0 a) ins (subs s a)))
            1 (recur (inc i) (text/delete doc a b) (str (subs s 0 a) (subs s b)))
            2 (recur (inc i) (text/replace doc a b ins) (str (subs s 0 a) ins (subs s b)))))))))

(defn- tmp [name]
  (str (or (System/getenv "TMPDIR") (System/getProperty "java.io.tmpdir"))
       "/narcis-text-" (System/nanoTime) "-" name))

(defn- file-door! []
  (doseq [[label content eol]
          [["lf" "(ns a)\n(defn f [x]\n  x)\n" "\n"]
           ["crlf" "(ns a)\r\n(defn f [x]\r\n  x)\r\n" "\r\n"]
           ["cr" "one\rtwo\rthree" "\r"]
           ["no trailing break" "alpha\nbeta" "\n"]
           ["empty" "" "\n"]
           ["one line" "just this" "\n"]
           ["blank lines" "\n\n\na\n\n" "\n"]]]
    (let [f (tmp "in") g (tmp "out")]
      (spit f content)
      (let [{:keys [doc eol-read]} (let [r (text/ingest-file f)] {:doc (:doc r) :eol-read (:eol r)})]
        (check (str "file " label " eol") eol eol-read)
        (check (str "file " label " text") (text/normalize-breaks content) (text/to-string doc))
        (text/spit-file! g doc eol-read)
        (check (str "file " label " round trip") content (slurp g)))))
  ;; a file larger than one read: lines intact across the chunk boundary
  (let [f (tmp "big") g (tmp "big-out")
        content (apply str (map (fn [i] (str "(defn fn-" i " [x] (+ x " i ")) ;; comment " i "\r\n")) (range 40000)))]
    (spit f content)
    (let [{:keys [doc eol]} (text/ingest-file f)]
      (check "big file eol" "\r\n" eol)
      (check "big file lines" 40001 (text/lines doc))
      (check "big file a line" "(defn fn-39999 [x] (+ x 39999)) ;; comment 39999" (text/line-at doc 39999))
      (text/spit-file! g doc eol)
      (check "big file round trip" true (= content (slurp g)))
      (let [copy (text/copy-here doc)]
        (check "copy lines" (text/lines doc) (text/lines copy))
        (check "copy text" true (= (text/to-string doc) (text/to-string copy)))))))

(defn run-all! []
  (test-util/with-heap-domain
    (fn []
      (doseq [[seed steps start]
              [[1 120 ""]
               [2 120 "a"]
               [3 150 "(ns demo)\n\n(defn f [x]\n  (inc x))\n"]
               [4 150 (apply str (map (fn [i] (str "line " i "\n")) (range 40)))]
               [5 120 (apply str (map (fn [i] (str "(def v" i " " i ")\n")) (range 700)))]]]
        (model-edits! seed steps start))
      (println "TEXT-TEST PASS: 5 seeded walks (660 edits) vs the string model")
      (file-door!)
      (println "TEXT-TEST PASS: file door (7 endings + a 40,000-line CRLF file) byte-exact"))))

(defn -main [& _]
  (run-all!)
  (println "ALL-TESTS PASS"))
