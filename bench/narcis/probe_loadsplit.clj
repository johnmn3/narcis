(ns narcis.probe-loadsplit
  "Where does one loader chunk's time go? Per 1 MB chunk, timed
   separately: (a) building the piece document from the chunk's
   text (what ingest does, outside the swap), and (b) the swap
   that appends it into the app atom's document in a disk
   domain (the commit). Run against a tmpfs dir and a workspace
   dir to separate the volume's fsync price from the work.
   Run: scripts/jolt-run.sh narcis.probe-loadsplit <dir>"
  (:require [eve.alpha :as eve :refer [$]]
            [narcis.text :as text]))

(def chunk-text
  ;; ~1 MB of 78-byte numbered lines, the numbered.txt shape
  (let [sb (StringBuilder.)]
    (dotimes [i 13450]
      (.append sb (format "L%07d the quick brown fox jumps over the lazy dog 0123456789 abcdef\n" i)))
    (str sb)))

(defn- timed-ms [f]
  (let [t (System/nanoTime)
        r (f)]
    [r (/ (double (- (System/nanoTime) t)) 1e6)]))

(defn -main [& [dir]]
  (let [base (str dir "/narcis/text3")
        _ (eve/persistent-atom-domain base)
        a (eve/atom {:id :narcis.probe-loadsplit/app :persistent base} nil)
        scratch (eve/atom {:id :narcis.probe-loadsplit/scratch :persistent base} nil)
        out (volatile! nil)]
    (swap! a (fn [_] {:doc (text/from-string "")}))
    (println "chunk bytes:" (count chunk-text) "dir:" dir)
    (dotimes [i 8]
      ;; building a piece constructs Eve data, so it happens
      ;; inside a swap on the scratch atom (the piece is kept);
      ;; the append into the app atom's doc is timed separately
      (let [[_ build-ms] (timed-ms #(swap! scratch
                                           (fn [_]
                                             (vreset! out (text/from-string chunk-text))
                                             nil)))
            piece @out
            [_ swap-ms] (timed-ms #(swap! a (fn [m]
                                               (assoc m :doc (text/insert (:doc m)
                                                                          (text/chars (:doc m))
                                                                          piece)))))]
        (println (format "chunk %d  build %8.1f ms   swap %8.1f ms" i build-ms swap-ms))))
    (println "final doc chars:" ($ (text/chars (:doc @a))))
    (println "PROBE-LOADSPLIT DONE")))
