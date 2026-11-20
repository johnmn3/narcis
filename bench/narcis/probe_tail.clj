(ns narcis.probe-tail
  "Where does the tail path stand? Times text/file-tail on the
   file alone, then opens it and samples the parked band, the
   scan, and the extents while the load runs."
  (:require [narcis.store :as store]
            [narcis.buffer :as buffer]
            [narcis.text :as text]))

(defn -main [& [dir path]]
  (let [t0 (System/nanoTime)
        t (text/file-tail path (* 64 1048576))]
    (println "file-tail:" (format "%.0f ms" (/ (double (- (System/nanoTime) t0)) 1e6))
             "start-byte:" (:start-byte t) "bytes:" (:bytes t)
             "text-chars:" (count (:text t))))
  (let [st (store/open-store {:dir dir :name store/store-name})
        ed (buffer/open-editor st)]
    (buffer/open-file! ed path)
    (loop [n 0]
      (let [scan (buffer/scan-of path)]
        (println "t+" (* n 10) "s"
                 "band:" (buffer/parked-tail-band path)
                 "scan:" (when scan {:done? (:done? @scan) :bytes (:bytes @scan)})
                 "extents:" (buffer/extent-ranges ed path)
                 "partial?:" (buffer/partial? ed path)))
      (when (and (buffer/partial? ed path) (< n 12))
        (Thread/sleep 10000)
        (recur (inc n))))
    (println "PROBE-TAIL DONE")))
