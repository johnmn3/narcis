(ns narcis.probe-domscale
  "The deciding experiment for the loader's pace (issue 2): is the
   wall Eve's shared domain / swap path, or the loader's shape?

   W threads each build K documents (text/from-string of an S-MB
   string, the segment workers' build) at once, in two arms:
     :one-domain   every thread swaps its own scratch atom, all in
                   ONE persistent domain (the loader's shape today)
     :domains      every thread swaps a scratch atom in its OWN
                   persistent domain
   Each build is rooted where it was built (the scratch's new value
   is the document), as the loader's segment builds are.

   If :domains scales with W and :one-domain does not, the wall is
   the shared domain (Eve work). If neither scales, the wall is in
   the build itself or the host (per-thread work, allocation, GC).
   If both scale, the loader's shape is what stops it.
   A third arm, :window, runs the segment workers' READ alone
   (text/file-window over the same stretch of a file, no Eve at
   all): if it does not scale either, the wall is the host's
   (Jolt/Chez threads, allocation, GC), not Eve's.

   Run: scripts/jolt-run.sh narcis.probe-domscale <dir> [mb-per-build]
        [builds-per-thread] [widths, e.g. 1,2,3]"
  (:require [eve.alpha :as eve :refer [$]]
            [clojure.string :as str]
            [narcis.text :as text]))

(defn- build-text
  "About mb megabytes of ~100-char lines, distinct per k."
  [k mb]
  (let [sb (StringBuilder.)
        target (* mb 1048576)]
    (loop [i 0]
      (when (< (.length sb) target)
        (.append sb (format "B%d L%08d the quick brown fox jumps over the lazy dog 0123456789 abcdefghij\n" k i))
        (recur (inc i))))
    (str sb)))

(defn- run-window-arm
  "W threads each reading builds stretches of lines-per-build lines
   with text/file-window (the read half of a segment build; no Eve)."
  [w builds mb path scan lines-per-build]
  (let [errors (atom [])
        per-thread-ms (atom [])
        t0 (System/nanoTime)
        threads (mapv (fn [t]
                        (doto (Thread.
                               ^Runnable
                               (fn []
                                 (let [t1 (System/nanoTime)]
                                   (try
                                     (dotimes [b builds]
                                       (let [k (+ (* t builds) b)]
                                         (text/file-window path scan (* k lines-per-build) lines-per-build)))
                                     (catch Throwable e (swap! errors conj e)))
                                   (swap! per-thread-ms conj
                                          (/ (double (- (System/nanoTime) t1)) 1e6)))))
                          (.setDaemon true)))
                      (range w))]
    (doseq [t threads] (.start t))
    (doseq [t threads] (.join t))
    (let [ms (/ (double (- (System/nanoTime) t0)) 1e6)
          total-mb (* w builds mb)]
      {:arm :window :w w :ms ms :mb total-mb
       :mb-s (/ total-mb (/ ms 1000.0))
       :per-thread-mb-s (mapv (fn [tm] (/ (* builds mb) (/ tm 1000.0))) @per-thread-ms)
       :errors (mapv ex-message @errors)
       :checks []})))

(defn- run-arm [dir arm w builds mb texts]
  (let [stamp (System/nanoTime)
        bases (mapv (fn [t]
                      (str dir "/" (name arm) "-w" w "-" stamp
                           (if (= arm :domains) (str "-d" t) "") "/narcis/text3"))
                    (range w))
        _ (doseq [b (distinct bases)] (eve/persistent-atom-domain b))
        scratches (mapv (fn [t]
                          (eve/atom {:id (keyword "narcis.probe-domscale" (str "s" t))
                                     :persistent (nth bases t)}
                                    nil))
                        (range w))
        errors (atom [])
        per-thread-ms (atom [])
        t0 (System/nanoTime)
        threads (mapv (fn [t]
                        (doto (Thread.
                               ^Runnable
                               (fn []
                                 (let [t1 (System/nanoTime)]
                                   (try
                                     (dotimes [b builds]
                                       (let [s (nth texts (mod (+ (* t builds) b) (count texts)))]
                                         (swap! (nth scratches t) (fn [_] (text/from-string s)))))
                                     (catch Throwable e (swap! errors conj e)))
                                   (swap! per-thread-ms conj
                                          (/ (double (- (System/nanoTime) t1)) 1e6)))))
                          (.setDaemon true)))
                      (range w))]
    (doseq [t threads] (.start t))
    (doseq [t threads] (.join t))
    (let [ms (/ (double (- (System/nanoTime) t0)) 1e6)
          total-mb (* w builds mb)
          checks (mapv (fn [s] ($ (let [d @s] [(text/chars d) (text/lines d)]))) scratches)]
      {:arm arm :w w :ms ms :mb total-mb
       :mb-s (/ total-mb (/ ms 1000.0))
       :per-thread-mb-s (mapv (fn [tm] (/ (* builds mb) (/ tm 1000.0))) @per-thread-ms)
       :errors (mapv ex-message @errors)
       :checks checks})))

(defn -main [& [dir mb builds widths]]
  (let [dir (or dir "/tmp/domscale")
        mb (Integer/parseInt (or mb "4"))
        builds (Integer/parseInt (or builds "2"))
        widths (mapv (fn [x] (Integer/parseInt x)) (str/split (or widths "1,2,3") #","))
        texts (mapv (fn [k] (build-text k mb)) (range (* builds (apply max widths))))]
    (println "PROBE-DOMSCALE mb/build" mb "builds/thread" builds "widths" widths
             "cores" (.availableProcessors (Runtime/getRuntime)))
    ;; the :window arm's file: the same texts, back to back, scanned
    (.mkdirs (java.io.File. ^String dir))
    (let [f (str dir "/window-src.txt")
          _ (spit f (apply str texts))
          scan (text/scan-file! f)
          _ (loop [] (when-not (:done? @scan) (Thread/sleep 100) (recur)))
          ;; every text ends on a break: its line count is its breaks
          lines-per-build (count (re-seq #"\n" (first texts)))]
      ;; warm the build path once, outside the measurement
      (run-arm dir :one-domain 1 1 mb texts)
      (doseq [w widths arm [:window :one-domain :domains]]
        (let [r (if (= arm :window)
                  (run-window-arm w builds mb f scan lines-per-build)
                  (run-arm dir arm w builds mb texts))]
          (println (format "%-11s W=%d  %7.0f ms  %6.2f MB/s total  per-thread %s  errors %d  checks %s"
                           (name arm) w (:ms r) (:mb-s r)
                           (str/join "," (map (fn [x] (format "%.2f" x)) (:per-thread-mb-s r)))
                           (count (:errors r)) (pr-str (:checks r))))
          (doseq [e (:errors r)] (println "  ERR:" e)))))
    (println "PROBE-DOMSCALE DONE")
    (System/exit 0)))
