(ns narcis.rope-test
  "Property tests: narcis.rope against a plain-string model.
   Deterministic LCG randomness — no test.check dependency, so the suite
   runs identically on JVM, bb and jolt. Run via bench/run-tests."
  (:require [narcis.rope :as r]
            [clojure.string :as str]))

;; --- deterministic RNG ------------------------------------------------------
(defn lcg [seed] (atom seed))
(defn rnd-int [rng n]
  (let [s (swap! rng (fn [x] (mod (+ (* x 1103515245) 12345) 2147483648)))]
    (if (zero? n) 0 (mod s n))))
(defn rnd-choice [rng xs] (nth xs (rnd-int rng (count xs))))

;; --- string model ------------------------------------------------------------
(defn model-lines
  "Line segments of s: split after every \\n, keeping the trailing
   segment (possibly empty). Matches rope's lines = (count \\n) + 1."
  [s]
  (loop [i 0 start 0 acc []]
    (if (>= i (count s))
      (conj acc (subs s start))
      (if (= (nth s i) \newline)
        (recur (inc i) (inc i) (conj acc (subs s start i)))
        (recur (inc i) start acc)))))

(defn model-line-start [s line]
  (loop [i 0 l 0]
    (cond (= l line) i
          (>= i (count s)) i
          (= (nth s i) \newline) (recur (inc i) (inc l))
          :else (recur (inc i) l))))

(def alphabet ["a" "bc" "\n" "def" "\n\n" "x y" "λ" "longer-piece\n" ""])

(defn random-text [rng max-pieces]
  (apply str (repeatedly (inc (rnd-int rng max-pieces))
                         (fn [] (rnd-choice rng alphabet)))))

;; --- the property run ---------------------------------------------------------
(defn check-equal [ctx expected actual]
  (when (not= expected actual)
    (throw (ex-info (str "MISMATCH " ctx)
                    {:context ctx :expected expected :actual actual}))))

(defn property-run!
  "Apply n random insert/delete ops to rope and model in lockstep,
   comparing full text, counts, random line reads and offset<->line
   conversions after every op. Returns the op count."
  [seed n]
  (binding [r/*leaf-size* 48]
    (let [rng (lcg seed)]
      (loop [i 0 rope (r/from-string "") model ""]
        (if (>= i n)
          n
          (let [op (rnd-int rng 3)
                [rope model]
                (cond
                  ;; insert
                  (or (= op 0) (empty? model))
                  (let [off (rnd-int rng (inc (count model)))
                        s (random-text rng 4)]
                    [(r/insert rope off s)
                     (str (subs model 0 off) s (subs model off))])
                  ;; delete
                  (= op 1)
                  (let [a (rnd-int rng (count model))
                        b (+ a (rnd-int rng (inc (- (count model) a))))]
                    [(r/delete rope a b)
                     (str (subs model 0 a) (subs model b))])
                  ;; split/concat round-trip at a random point
                  :else
                  (let [k (rnd-int rng (inc (count model)))
                        [l rr] (r/split rope k)]
                    [(r/concat l rr) model]))]
            (check-equal [:text i] model (r/to-string rope))
            (check-equal [:chars i] (count model) (r/chars rope))
            (check-equal [:lines i] (count (model-lines model)) (r/lines rope))
            ;; random line read
            (let [li (rnd-int rng (count (model-lines model)))]
              (check-equal [:line-at i li]
                           (nth (model-lines model) li)
                           (r/line-at rope li))
              (check-equal [:line-start i li]
                           (model-line-start model li)
                           (r/line-start-offset rope li)))
            ;; random offset conversion round-trip
            (let [off (rnd-int rng (inc (count model)))
                  line (r/offset->line rope off)
                  [l2 c2] (r/offset->line-col rope off)]
              (check-equal [:offset->line i off]
                           (dec (count (model-lines (subs model 0 off))))
                           line)
              (check-equal [:line-col-roundtrip i off]
                           off (r/line-col->offset rope l2 c2)))
            (recur (inc i) rope model)))))))

;; --- fixed cases ---------------------------------------------------------------
(defn fixed-cases! []
  (binding [r/*leaf-size* 48]
    (let [cases ["" "a" "a\n" "a\nb" "\n" "\n\n\n" "no newline at all"
                 "one\ntwo\nthree\n" (apply str (repeat 200 "abcdefghij\n"))]]
      (doseq [s cases]
        (let [rope (r/from-string s)]
          (check-equal [:fixed-text s] s (r/to-string rope))
          (check-equal [:fixed-lines s] (count (model-lines s)) (r/lines rope))
          (doseq [li (range (count (model-lines s)))]
            (check-equal [:fixed-line s li] (nth (model-lines s) li)
                         (r/line-at rope li))))))
    ;; versions: old root reads old text after edits
    (let [v1 (r/from-string "alpha\nbeta\ngamma\n")
          v2 (r/insert v1 6 "INSERTED\n")
          v3 (r/delete v2 0 6)]
      (check-equal [:version-v1] "alpha\nbeta\ngamma\n" (r/to-string v1))
      (check-equal [:version-v2] "alpha\nINSERTED\nbeta\ngamma\n" (r/to-string v2))
      (check-equal [:version-v3] "INSERTED\nbeta\ngamma\n" (r/to-string v3)))
    ;; slice
    (let [rope (r/from-string "0123456789")]
      (check-equal [:slice] "3456" (r/slice-string rope 3 7)))
    ;; lines-window
    (let [rope (r/from-string "l0\nl1\nl2\nl3\nl4\n")]
      (check-equal [:window] ["l1" "l2"] (r/lines-window rope 1 2)))
    ;; line-col edits
    (let [rope (r/from-string "hello\nworld\n")
          rope2 (r/insert-at-line-col rope 1 5 "!")]
      (check-equal [:line-col-insert] "hello\nworld!\n" (r/to-string rope2)))
    :ok))

(defn run-all! []
  (fixed-cases!)
  (property-run! 42 400)
  (property-run! 777 400)
  (println "ROPE-TEST PASS: fixed cases + 800 randomized ops vs string model"))
