(ns narcis.probe-progressive
  "Progressive open, proven: the head of a file becomes the buffer at
   once, the tail arrives on the loader thread, and the document it
   builds is byte-for-byte the document the whole-file ingest builds
   — \r\n seams straddling the head and chunk boundaries included.
   Run: jolt-run.sh narcis.probe-progressive <file> [head-chars]"
  (:require [narcis.store :as store]
            [narcis.buffer :as buffer]
            [narcis.text :as text]
            [clojure.java.io :as io]))

(defn- doc-text [ed path]
  (buffer/read ed path (fn [{doc :root}] (text/slice-string doc 0 (text/chars doc)))))

(defn -main [& [path head]]
  (let [path (.getCanonicalPath (io/file (or path "/home/hatch/workspace/1brc-pair13/bench/v500k.txt")))
        head (if head (Integer/parseInt head) 4096)
        dir (str (System/getProperty "java.io.tmpdir") "/probe-progressive-" (System/nanoTime))
        st (store/open-store {:dir dir :name store/store-name})
        ed (buffer/open-editor st)
        t0 (System/currentTimeMillis)
        _ (buffer/open-file! ed path {:head head})
        head-ms (- (System/currentTimeMillis) t0)
        was-loading (buffer/loading? ed path)
        head-chars (buffer/read ed path (fn [{doc :root}] (text/chars doc)))]
    (println "open returned in" head-ms "ms; loading?" was-loading
             "; head chars" head-chars "of file" (text/file-length path))
    (loop [n 0]
      (when (and (buffer/loading? ed path) (< n 300))
        (Thread/sleep 1000)
        (when (zero? (mod n 5))
          (println "  t+" n "s chars" (buffer/read ed path (fn [{doc :root}] (text/chars doc)))
                   "loading" (buffer/loading-map ed)))
        (recur (inc n))))
    (let [loaded (not (buffer/loading? ed path))
          mine (doc-text ed path)
          theirs (text/normalize-breaks (slurp path))
          undo (buffer/read ed path (fn [v] (:undo v)))]
      (println "load completed:" loaded
               "; chars mine/theirs" (count mine) "/" (count theirs)
               "; text equal:" (= mine theirs)
               "; undo depth:" undo)
      (store/close-store st)
      (System/exit (if (and loaded (= mine theirs) (zero? undo)) 0 1)))))
