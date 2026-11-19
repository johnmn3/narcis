(ns narcis.probe-parbuild
  "Can segment documents be built CONCURRENTLY in one Eve disk
   domain — the parallel load's core question. Eight ~1 MB
   segments are built into the domain twice: once serially,
   once on four threads (each thread builds into its own
   scratch atom in the same domain). Correctness: every
   segment's document reads back with its own char count and
   first line. Run: scripts/jolt-run.sh narcis.probe-parbuild <dir>"
  (:require [eve.alpha :as eve :refer [$]]
            [narcis.text :as text]))

(defn- segment-text [k]
  (let [sb (StringBuilder.)]
    (dotimes [i 13450]
      (.append sb (format "S%d L%07d the quick brown fox jumps over the lazy dog 0123456\n" k i)))
    (str sb)))

(defn- build-into! [scratch out s]
  (swap! scratch (fn [_] (vreset! out (text/from-string s)) nil)))

(defn -main [& [dir]]
  (let [base (str dir "/narcis/text3")
        _ (eve/persistent-atom-domain base)
        segs (mapv segment-text (range 8))
        mk-scratch (fn [k] (eve/atom {:id (keyword "narcis.probe-parbuild" (str "s" k))
                                      :persistent base} nil))
        run-set (fn [parallel?]
                  (let [outs (mapv (fn [_] (volatile! nil)) (range 8))
                        scratches (mapv mk-scratch (range 8))
                        errors (atom [])
                        t0 (System/nanoTime)]
                    (if parallel?
                      (let [threads (mapv (fn [w]
                                            (doto (Thread. ^Runnable
                                                           (fn []
                                                             (try (doseq [k [(* 2 w) (inc (* 2 w))]]
                                                                    (build-into! (nth scratches k) (nth outs k) (nth segs k)))
                                                                  (catch Throwable t (swap! errors conj t)))))
                                                  (.setDaemon true)) )
                                          (range 4))]
                        (doseq [t threads] (.start t))
                        (doseq [t threads] (.join t)))
                      (doseq [k (range 8)]
                        (build-into! (nth scratches k) (nth outs k) (nth segs k))))
                    {:ms (/ (double (- (System/nanoTime) t0)) 1e6)
                     :errors @errors
                     :checks (mapv (fn [k]
                                     ($ (let [d @(nth outs k)]
                                          [(text/chars d) (text/lines d)])))
                                   (range 8))}))]
    (println "segment bytes:" (count (nth segs 0)))
    (let [serial (run-set false)]
      (println "serial:  " (format "%.0f ms" (:ms serial)) "errors:" (count (:errors serial)))
      (println "serial checks:" (:checks serial))
      (let [par (run-set true)]
        (println "parallel:" (format "%.0f ms" (:ms par)) "errors:" (count (:errors par)))
        (doseq [e (:errors par)] (println "  ERR:" (ex-message e)))
        (println "parallel checks:" (:checks par))
        (println "speedup:" (format "%.2fx" (/ (:ms serial) (:ms par))))))
    (println "PROBE-PARBUILD DONE")))
