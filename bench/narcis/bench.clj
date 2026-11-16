(ns narcis.bench
  "Scale probes for narcis.rope.
     clojure -M -m narcis.bench scale1m     ; 1e6-line build + ops at depth
     clojure -M -m narcis.bench ingest100m  ; >=1e8-line synthetic ingest
   Times are wall-clock ms for the named phase, printed as BENCH lines."
  (:require [narcis.rope :as r]))

(defn lcg [seed] (atom seed))
(defn rnd-int [rng n]
  (let [s (swap! rng (fn [x] (mod (+ (* x 1103515245) 12345) 2147483648)))]
    (if (zero? n) 0 (mod s n))))

(defn- timed [label f]
  (let [t0 (System/nanoTime)
        v (f)
        ms (/ (- (System/nanoTime) t0) 1e6)]
    (println (format "BENCH %s: %.1f ms" label ms))
    v))

(defn- ops-at-depth!
  "n random single edits + reads against rope; returns final rope."
  [rope n seed]
  (let [rng (lcg seed)]
    (loop [i 0 rope rope]
      (if (>= i n)
        rope
        (let [choice (rnd-int rng 4)]
          (recur (inc i)
                 (case choice
                   0 (r/insert rope (rnd-int rng (inc (r/chars rope))) "x")
                   1 (let [a (rnd-int rng (r/chars rope))]
                       (r/delete rope a (min (r/chars rope) (+ a 1))))
                   2 (do (r/line-at rope (rnd-int rng (r/lines rope))) rope)
                   3 (do (r/slice-string rope 0 (min 100 (r/chars rope))) rope))))))))

(defn scale-1m []
  (let [lines (map (fn [i] (str "line-" i " lorem ipsum dolor sit amet")) (range 1000000))
        rope (timed "build-1e6-lines" (fn [] (r/ingest-lines lines)))]
    (println "BENCH build-1e6:" "lines=" (r/lines rope) "chars=" (r/chars rope)
             "height=" (:h rope))
    (assert (= 1000001 (r/lines rope)))
    (assert (= "line-999999 lorem ipsum dolor sit amet" (r/line-at rope 999999)))
    (timed "ops-2000-at-1e6" (fn [] (ops-at-depth! rope 2000 99)))
    (timed "window-50-at-1e6" (fn [] (r/lines-window rope 500000 50)))
    (println "BENCH scale1m PASS")))

(defn ingest-100m []
  (binding [r/*leaf-size* 8192]
    (let [n 100000000
          lines (map (fn [i] (str "line-" i)) (range n))
          rope (timed "ingest-1e8-lines" (fn [] (r/ingest-lines lines)))]
      (println "BENCH ingest-1e8:" "lines=" (r/lines rope) "chars=" (r/chars rope)
               "height=" (:h rope))
      (assert (= (inc n) (r/lines rope)))
      (assert (= "line-99999999" (r/line-at rope 99999999)))
      (assert (= "line-50000000" (r/line-at rope 50000000)))
      (timed "ops-200-at-1e8" (fn [] (ops-at-depth! rope 200 7)))
      (timed "window-50-at-1e8" (fn [] (r/lines-window rope 50000000 50)))
      (println "BENCH ingest100m PASS"))))

(defn -main [mode]
  (case mode
    "scale1m" (scale-1m)
    "ingest100m" (ingest-100m)
    (println "usage: bench scale1m|ingest100m")))
