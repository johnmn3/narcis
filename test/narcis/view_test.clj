(ns narcis.view-test
  "S3 rendering-correctness gate, headless: the span data of a window
   payload must be exactly jonquil.syntax's analysis of the same text
   (tokenize + compute-brackets, composed the way jonquil.paint
   composes them), and a window of a 1e6-line document must stay bounded."
  (:require [clojure.test :as t]
            [narcis.paint :as paint]
            [narcis.text :as text]
            [eve.text]
            [clojure.string]
            [narcis.syntax :as syntax]
            [narcis.view :as view]))

(def fixture
  "(ns demo.core)\n;; a comment\n(defn add [x y]\n  (+ x y)) ; trailing\n(def s \"multi\nline string\")\n(def k :some/keyword)\n(def n 42.5e3)\n(defn broken [x]\n  (inc x)\n")

(defn- expected-plan
  "The paint plan derived straight from narcis.syntax (the jonquil
   lift), without going through narcis.paint or narcis.view."
  [text]
  (let [tokens (into []
                     (keep (fn [[ty s e]] (when (contains? paint/token-styles ty) [ty s e])))
                     (syntax/tokenize text))
        brackets (mapv (fn [[s e depth kind]]
                         [(if (= kind :unmatched) :unmatched [:rainbow (mod depth 7)]) s e])
                       (:bracket-tokens (syntax/compute-brackets text)))]
    (into tokens brackets)))

(defn- payload-spans-absolute
  "Reconstruct [kind start end] absolute spans from a window payload,
   using only the payload's own line starts and columns."
  [win]
  (into []
        (mapcat (fn [{:keys [start spans]}]
                  (map (fn [[k c0 c1]] [k (+ start c0) (+ start c1)]) spans)))
        (:lines win)))

(defn- split-at-lines
  "Clip plan spans to each line's TEXT range of text (a line's text
   excludes its newline): the payload is per-line, so a multi-line
   token (a string) arrives as one span per line it touches, ending
   at the line's last char. Returns [kind s e] spans with kind
   serialized, sorted — the order-insensitive normal form both sides
   are compared in (within a line the payload keeps plan order, which
   is what overlap resolution reads; across lines order is moot)."
  [text spans]
  (let [line-ranges (loop [i 0 ls 0 acc []]
                      (if (>= i (count text))
                        (conj acc [ls (count text)])
                        (if (= (nth text i) \newline)
                          (recur (inc i) (inc i) (conj acc [ls i]))
                          (recur (inc i) ls acc))))]
    (vec
     (sort
      (mapcat (fn [[ls le]]
                (keep (fn [[k s e]]
                        (let [a (max s ls) b (min e le)]
                          (when (< a b) [(view/serialize-key k) a b])))
                      spans))
              line-ranges)))))

(defn- window-of
  "[window-payload total-lines] of a document of s, built in a heap domain."
  [s opts]
  (text/with-heap-domain
    (fn [] (let [doc (text/from-string s)]
             [(view/window doc opts) (text/lines doc)]))))

(t/deftest span-data-matches-syntax-analysis
  (let [[win total] (window-of fixture {:start 0 :count 100 :caret 0})
        expected (expected-plan fixture)
        plan (paint/paint-plan fixture)]
    (t/is (= :exact (:spans-mode win)))
    (t/is (= total (:total-lines win)))
    ;; the lift chain itself: narcis.paint's plan is the syntax-derived one
    (t/is (= expected plan))
    ;; and the payload the page renders is exactly that plan, rebased
    (t/is (= (split-at-lines fixture plan)
             (vec (sort (payload-spans-absolute win)))))))

(t/deftest span-data-window-slice
  (let [[win _] (window-of fixture {:start 2 :count 3 :caret 0})
        starts (reductions + 0 (map (fn [l] (inc (count l))) (clojure.string/split fixture #"\n" -1)))
        cs (nth starts 2)
        ce (nth starts 5)
        expected (into []
                       (keep (fn [[k s e]]
                               (let [a (max s cs) b (min e ce)]
                                 (when (< a b) [k a b]))))
                       (paint/paint-plan fixture))]
    (t/is (= [2 3 4] (mapv :n (:lines win))))
    (t/is (= (split-at-lines fixture expected)
             (vec (sort (payload-spans-absolute win)))))))

(t/deftest bracket-match-in-payload
  (let [;; offset of the (defn add ... opening paren is 15; its match is
        ;; the close of (+ x y)) line's second paren
        open (.indexOf fixture "(defn")
        [win _] (window-of fixture {:start 0 :count 100 :caret open})
        [a b] (:match win)]
    (t/is (some? (:match win)))
    (t/is (= open a))
    (t/is (= (get (:matches (syntax/compute-brackets fixture)) open) b))))

(t/deftest viewport-payload-bounded
  (let [n (if (System/getenv "NARCIS_VIEW_LINES") (Integer/parseInt (System/getenv "NARCIS_VIEW_LINES")) 1000000)
        [win total]
        (text/with-heap-domain
          (fn []
            ;; built as a file is: a chunk of text at a time
            (let [doc (loop [i 0 b (eve.text/text-builder)]
                        (if (< i n)
                          (let [k (min n (+ i 20000))]
                            (recur k (eve.text/text-builder-add
                                      b (apply str (map (fn [j] (str "(defn f" j " [x] (+ x " j "))\n")) (range i k))))))
                          (eve.text/text-builder-text b)))]
              [(view/window doc {:start (quot n 2) :count 60 :caret nil}) (text/lines doc)])))
        payload (pr-str win)]
    (t/is (= total (:total-lines win)))
    (t/is (<= n (:total-lines win)))
    (t/is (= :window (:spans-mode win)))
    (t/is (= 60 (count (:lines win))))
    (t/is (= (quot n 2) (:n (first (:lines win)))))
    ;; named bound: a 60-line window of ~30-char lines stays under 32 KiB
    (t/is (< (count payload) 32768) (str "payload bytes: " (count payload)))))

(defn -main [& _]
  (let [{:keys [fail error] :as m} (t/run-tests 'narcis.view-test)]
    (println "TEST-SUMMARY" m)
    (System/exit (if (zero? (+ fail error)) 0 1))))
