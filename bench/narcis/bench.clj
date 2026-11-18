(ns narcis.bench
  "Scale probes for the buffer's text (narcis.text over eve.text).
     -m narcis.bench scale <lines>        ; build in a heap domain + ops at depth
     -m narcis.bench open <lines> <dir>   ; a file opened into a disk-domain
                                          ; buffer atom, then typed into
   Times are wall-clock ms for the named phase, printed as BENCH lines."
  (:require [narcis.test-util :as test-util]
            [narcis.text :as text]
            [narcis.buffer :as buffer]
            [narcis.ops :as ops]
            [narcis.store :as store]
            [narcis.view :as view]
            [eve.text :as et]))

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

(defn- line-text [i] (str "(defn fn-" i " [x] (+ x " i ")) ;; comment " i))

(defn- build
  "A document of n lines, a 20,000-line piece at a time (as a file is read)."
  [n]
  (loop [i 0 b (et/text-builder)]
    (if (< i n)
      (let [k (min n (+ i 20000))]
        (recur k (et/text-builder-add b (apply str (map (fn [j] (str (line-text j) "\n")) (range i k))))))
      (et/text-builder-text b))))

(defn- ops-at-depth!
  "n random single edits + reads against doc; returns the final doc."
  [doc n seed]
  (let [rng (lcg seed)]
    (loop [i 0 doc doc]
      (if (>= i n)
        doc
        (recur (inc i)
               (case (rnd-int rng 4)
                 0 (text/insert doc (rnd-int rng (inc (text/chars doc))) "x")
                 1 (let [a (rnd-int rng (text/chars doc))]
                     (text/delete doc a (min (text/chars doc) (+ a 1))))
                 2 (do (text/line-at doc (rnd-int rng (text/lines doc))) doc)
                 3 (do (text/slice-string doc 0 (min 100 (text/chars doc))) doc)))))))

(defn scale [n]
  (test-util/with-heap-domain
    (fn []
      (let [doc (timed (str "build-" n "-lines") (fn [] (build n)))]
        (println "BENCH built:" "lines=" (text/lines doc) "chars=" (text/chars doc) "depth=" (et/text-depth doc))
        (assert (= (inc n) (text/lines doc)))
        (assert (= (line-text (dec n)) (text/line-at doc (dec n))))
        (timed (str "ops-2000-at-" n) (fn [] (ops-at-depth! doc 2000 99)))
        (timed (str "window-60-at-" n) (fn [] (text/window doc (quot n 2) 60)))
        (timed (str "payload-60-at-" n) (fn [] (view/window doc {:start (quot n 2) :count 60})))
        (println "BENCH scale PASS")))))

(defn open [n dir]
  (let [f (str dir "/bench-" n ".clj")]
    (.mkdirs (java.io.File. ^String dir))
    (with-open [w (java.io.FileWriter. ^String f)]
      (dotimes [i n] (.write w (str (line-text i) "\n"))))
    (let [st (store/open-store {:dir (str dir "/db-" (System/nanoTime)) :name store/store-name})
          ed (buffer/open-editor st)]
      (timed (str "open-file-" n "-lines") (fn [] (buffer/open-file! ed f)))
      (println "BENCH opened:" "lines=" (:lines (buffer/snapshot ed f)))
      (ops/apply-op ed {:op :set-caret :path f :offset 5})
      (timed "type-200-letters" (fn [] (dotimes [_ 200] (ops/apply-op ed {:op :type-char :path f :ch "q"}))))
      (timed "snapshot-x20" (fn [] (dotimes [_ 20] (buffer/snapshot ed f))))
      (timed "window-100-x20" (fn [] (dotimes [_ 20]
                                       (buffer/read ed f (fn [s] (view/window (:root s) {:start 0 :count 100 :caret (:cursor s) :key (:text-key s)}))))))
      (timed "type+window-x20" (fn [] (dotimes [_ 20]
                                        (ops/apply-op ed {:op :type-char :path f :ch "q"})
                                        (buffer/read ed f (fn [s] (view/window (:root s) {:start 0 :count 100 :caret (:cursor s) :key (:text-key s)}))))))
      (timed "undo-x50" (fn [] (dotimes [_ 50] (ops/apply-op ed {:op :undo :path f}))))
      (store/close-store st)
      (println "BENCH open PASS"))))

(defn -main [mode & args]
  (case mode
    "scale" (scale (Integer/parseInt (or (first args) "1000000")))
    "open" (open (Integer/parseInt (or (first args) "12000")) (or (second args) "bench-data"))
    (println "usage: bench scale <lines> | open <lines> <dir>")))
