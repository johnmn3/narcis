(ns narcis.probe-parload
  "Time a full materialization of a big file through the real
   open path, and verify the finished document: char and line
   counts and spot lines. Run with NARCIS_SEGMENT_WORKERS=0 and
   unset for the single-threaded / parallel A/B — the finished
   counts must be identical, only the time may differ.
   Run: scripts/jolt-run.sh narcis.probe-parload <store-dir> <file>"
  (:require [narcis.store :as store]
            [narcis.buffer :as buffer]
            [narcis.text :as text]))

(defn -main [& [dir path]]
  (let [st (store/open-store {:dir dir :name store/store-name})
        ed (buffer/open-editor st)
        t0 (System/nanoTime)]
    (buffer/open-file! ed path)
    (loop [n 0]
      (when (and (buffer/partial? ed path) (< n 1200))
        (when (zero? (mod n 15))
          (println "t+" (format "%.0f s" (/ (double (- (System/nanoTime) t0)) 1e9))
                   "loaded:" (:loaded (get (buffer/loading-map ed) path))
                   "extents:" (count (buffer/extent-ranges ed path))))
        (Thread/sleep 2000)
        (recur (inc n))))
    (let [ms (/ (double (- (System/nanoTime) t0)) 1e6)
          whole (not (buffer/partial? ed path))
          [chars lines] (buffer/read ed path (fn [s] [(text/chars (:root s)) (text/lines (:root s))]))
          spots (buffer/read ed path
                             (fn [s]
                               (mapv (fn [ln]
                                       (let [o (text/line-start-offset (:root s) ln)]
                                         (text/slice-string (:root s) o (min (text/chars (:root s)) (+ o 30)))))
                                     [0 1000000 2167595])))]
      (println "whole:" whole "in" (format "%.0f ms" ms))
      (println "chars:" chars "lines:" lines)
      (println "spots:" spots)
      (println "PROBE-PARLOAD DONE"))))
