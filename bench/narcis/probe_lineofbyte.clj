(ns narcis.probe-lineofbyte
  "line-of-byte against a brute-force count: the line holding a
   byte is the breaks whose last byte lies before the byte the
   offset stands on (the file's last byte, at or past the end).
   The brute force walks the raw bytes once, recording the
   answer at every stood-on byte asked for. Every file is
   checked under three scan states — no table, a table covering
   only the first chunk, and the completed table — because
   production asks mid-load, when the table is partial or
   absent and the count runs in bulk past it; the trap file
   puts a \\r at the end of the bulk reader's first 1 MB piece
   so the held-out-\\r path across pieces executes. Files are
   made here, plus the standing small dense file."
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
        want (brute bs offsets)
        full (text/scan-file! path)]
    (loop [] (when-not (:done? @full) (Thread/sleep 50) (recur)))
    ;; a partial table: only the first chunk of the full scan —
    ;; everything past it takes the bulk path, as a mid-load
    ;; query does
    (let [partial (atom {:chunks [(first (:chunks @full))] :done? false})]
      (doseq [[label scan] [["no-table" nil]
                            ["partial-table" partial]
                            ["full-table" full]]]
        (let [bad (filter (fn [b]
                            (not= (get want b) (text/line-of-byte path scan b)))
                          offsets)]
          (println path label "offsets:" (count offsets)
                   (if (empty? bad) "ALL OK" (str "MISMATCHES: " (pr-str (vec bad)))))
          (doseq [b (take 5 bad)]
            (println "  byte" b "brute" (get want b)
                     "fn" (text/line-of-byte path scan b))))))))

(defn- trap-file [dir]
  ;; 1,048,575 x's, then \r\n, a line, then 1.1 MB of y's and a
  ;; last line: with no table the bulk reader's first piece is
  ;; exactly the x's plus the \r, so the \r is held out of the
  ;; piece and resolved as the next piece's prefix
  (let [f (io/file dir "f.txt")
        n1 1048575
        mid (.getBytes "line two\n" "UTF-8")
        n2 1100000
        tail (.getBytes "\nlast" "UTF-8")
        bs (byte-array (+ n1 2 (alength mid) n2 (alength tail)))
        yblock (let [b (byte-array 1048576)]
                 (java.util.Arrays/fill b (byte 121))
                 b)]
    (java.util.Arrays/fill bs (byte 120))
    (aset bs n1 (byte 13))
    (aset bs (inc n1) (byte 10))
    (System/arraycopy mid 0 bs (+ n1 2) (alength mid))
    (loop [off (+ n1 2 (alength mid)) left n2]
      (when (pos? left)
        (let [k (min left (alength yblock))]
          (System/arraycopy yblock 0 bs off k)
          (recur (+ off k) (- left k)))))
    (System/arraycopy tail 0 bs (+ n1 2 (alength mid) n2) (alength tail))
    (java.nio.file.Files/write (.toPath f) bs)
    (.getPath f)))

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
    (check-file (trap-file dir)
                [0 1 1048574 1048575 1048576 1048577 1048580 1048587
                 1500000 2000000 2148592 999999999])
    (check-file "/tmp/smalldense.txt"
                [0 1 25 26 100 1000000 10000000 19999999 20000000 20000006 999999999])
    (println "PROBE-LINEOFBYTE DONE")))
