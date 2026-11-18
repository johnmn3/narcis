(ns narcis.probe-chunk
  "Time the chunk accounting candidates on ~734 KB of lorem-like
   text: native string counting (decode + length/replace) vs a
   lean per-byte loop with an ASCII shortcut.
   Run: jolt-run.sh narcis.probe-chunk")

(defn- native-stats
  "Counts for a decoded chunk via the host string machinery:
   {:breaks n :pairs n :units n}."
  [^String s]
  (let [l (.length s)
        ncount (- l (.length (.replace s "\n" "")))
        rcount (- l (.length (.replace s "\r" "")))
        pairs (quot (- l (.length (.replace s "\r\n" ""))) 2)]
    {:breaks (+ ncount rcount (- pairs)) :pairs pairs :units l}))

(defn- byte-account
  "Lean per-byte accounting over buf's first n bytes from bases:
   {:line n :pairs n :units n :pending-cr? b}. A trailing \\r is
   held (unit and break) until the next byte resolves it."
  [^bytes buf n line0]
  (loop [i 0 line line0 pairs 0 units 0 prev-cr? false]
    (if (< i n)
      (let [b (aget buf i)]
        (cond
          prev-cr?
          (if (== b 10)
            (recur (inc i) (inc line) (inc pairs) (+ units 2) false)
            (recur i (inc line) pairs (inc units) false))
          (== b 10) (recur (inc i) (inc line) pairs (inc units) false)
          (== b 13) (recur (inc i) line pairs units true)
          (pos? b) (recur (inc i) line pairs (inc units) false)
          :else (recur (inc i) line pairs
                       (+ units (cond (== (bit-and b 0xC0) 0x80) 0
                                      (<= 0xF0 (bit-and b 0xFF) 0xF7) 2
                                      :else 1))
                       false)))
      {:line line :pairs pairs :units units :pending-cr? prev-cr?
       :breaks (- line line0)})))

(defn -main [& _]
  (let [sb (StringBuilder.)]
    (dotimes [i 10800]
      (.append sb "line x the quick brown fox jumps over the lazy dog again and again.\n"))
    (let [s (str sb)
          bytes (.getBytes s "UTF-8")
          n (alength bytes)]
      (println "chunk bytes" n "chars" (count s))
      (dotimes [_ 2] (native-stats s) (byte-account bytes n 0))
      (let [t0 (System/nanoTime)
            r1 (native-stats s)
            t1 (System/nanoTime)
            r2 (byte-account bytes n 0)
            t2 (System/nanoTime)]
        (println "native stats:" (quot (- t1 t0) 1000000) "ms" r1)
        (println "byte loop:  " (quot (- t2 t1) 1000000) "ms" r2)))))
