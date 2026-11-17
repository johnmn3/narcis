(ns narcis.view-test
  "S3 rendering-correctness gate, headless: the span data of a window
   payload must be exactly jonquil.syntax's analysis of the same text
   (tokenize + compute-brackets, composed the way jonquil.paint
   composes them), and a window of a 1e6-line rope must stay bounded."
  (:require [clojure.test :as t]
            [narcis.paint :as paint]
            [narcis.rope :as rope]
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

(t/deftest span-data-matches-syntax-analysis
  (let [root (rope/from-string fixture)
        win (view/window root {:start 0 :count 100 :caret 0})
        expected (expected-plan fixture)
        plan (paint/paint-plan fixture)]
    (t/is (= :exact (:spans-mode win)))
    (t/is (= (rope/lines root) (:total-lines win)))
    ;; the lift chain itself: narcis.paint's plan is the syntax-derived one
    (t/is (= expected plan))
    ;; and the payload the page renders is exactly that plan, rebased
    (t/is (= (split-at-lines fixture plan)
             (vec (sort (payload-spans-absolute win)))))))

(t/deftest span-data-window-slice
  (let [root (rope/from-string fixture)
        win (view/window root {:start 2 :count 3 :caret 0})
        cs (rope/line-start-offset root 2)
        ce (rope/line-start-offset root 5)
        expected (into []
                       (keep (fn [[k s e]]
                               (let [a (max s cs) b (min e ce)]
                                 (when (< a b) [k a b]))))
                       (paint/paint-plan fixture))]
    (t/is (= [2 3 4] (mapv :n (:lines win))))
    (t/is (= (split-at-lines fixture expected)
             (vec (sort (payload-spans-absolute win)))))))

(t/deftest bracket-match-in-payload
  (let [root (rope/from-string fixture)
        ;; offset of the (defn add ... opening paren is 15; its match is
        ;; the close of (+ x y)) line's second paren
        open (.indexOf fixture "(defn")
        win (view/window root {:start 0 :count 100 :caret open})
        [a b] (:match win)]
    (t/is (some? (:match win)))
    (t/is (= open a))
    (t/is (= (get (:matches (syntax/compute-brackets fixture)) open) b))))

(t/deftest viewport-payload-bounded
  (let [lines (map (fn [i] (str "(defn f" i " [x] (+ x " i "))")) (range 1000000))
        root (rope/from-lines lines)
        win (view/window root {:start 500000 :count 60 :caret nil})
        payload (pr-str win)]
    (t/is (= (rope/lines root) (:total-lines win)))
    (t/is (<= 1000000 (:total-lines win)))
    (t/is (= :window (:spans-mode win)))
    (t/is (= 60 (count (:lines win))))
    (t/is (= 500000 (:n (first (:lines win)))))
    ;; named bound: a 60-line window of ~30-char lines stays under 32 KiB
    (t/is (< (count payload) 32768) (str "payload bytes: " (count payload)))))

(defn -main [& _]
  (let [{:keys [fail error] :as m} (t/run-tests 'narcis.view-test)]
    (println "TEST-SUMMARY" m)
    (System/exit (if (zero? (+ fail error)) 0 1))))
