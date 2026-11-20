(ns narcis.probe-lineofbyte
  "line-of-byte against a brute-force count: the line holding a
   byte is the breaks whose last byte lies before the byte the
   offset stands on (the file's last byte, at or past the end).
   The brute force walks the raw bytes once, recording the
   answer at every stood-on byte asked for. Files are made here,
   plus the standing small dense file."
  (:require [narcis.text :as text]
            [clojure.java.io :as io]))

(defn- brute [^bytes bs offsets]
  (let [total (alength bs)
        by-limit (group-by (fn [b] (min b (dec total))) offsets)]
    (loop [i 0 breaks 0 prev-cr? false out {}]
      (if (< i total)
        (let [v (aget bs i)
              ;; a pending \r whose successor is not \n is a
              ;; break whose last byte is behind this position
              breaks (if (and prev-cr? (not= 10 v)) (inc breaks) breaks)
              out (reduce (fn [o b] (assoc o b breaks)) out (get by-limit i))
              [breaks prev-cr?] (cond
                                   (= 10 v) [(inc breaks) false]
                                   (= 13 v) [breaks true]
                                   :else [breaks false])]
          (recur (inc i) breaks prev-cr? out))
        ;; a trailing \r's last byte is the file's last: it
        ;; never lies before a stood-on byte, never counted
        out))))

(defn- check-file [path offsets]
  (let [bs (java.nio.file.Files/readAllBytes (.toPath (io/file path)))
        scan (text/scan-file! path)]
    (loop [] (when-not (:done? @scan) (Thread/sleep 50) (recur)))
    (let [want (brute bs offsets)
          bad (filter (fn [b]
                        (not= (get want b) (text/line-of-byte path scan b)))
                      offsets)]
      (println path "offsets:" (count offsets)
               (if (empty? bad) "ALL OK" (str "MISMATCHES: " (pr-str (vec bad)))))
      (doseq [b (take 5 bad)]
        (println "  byte" b "brute" (get want b) "fn" (text/line-of-byte path scan b))))))

(defn -main [& _]
  (let [dir "/tmp/lob"
        mk (fn [name ^String s]
             (let [f (io/file dir name)]
               (.mkdirs (io/file dir))
               (java.nio.file.Files/write (.toPath f) (.getBytes s "UTF-8"))
               (.getPath f)))]
    ;; edge shapes: lone \r, \r\n pairs, a trailing \r, no
    ;; trailing break, nothing but breaks
    (check-file (mk "a.txt" "one\ntwo\r\nthree\rfour\nfive") (range 0 30))
    (check-file (mk "b.txt" "x\r\ny\r\n") (range 0 8))
    (check-file (mk "c.txt" "end\r") (range 0 6))
    (check-file (mk "d.txt" "\n\n\n") (range 0 5))
    (check-file (mk "e.txt" "no breaks at all") (range 0 18))
    (check-file "/tmp/smalldense.txt"
                [0 1 25 26 100 1000000 10000000 19999999 20000000 20000006 999999999])
    (println "PROBE-LINEOFBYTE DONE")))
