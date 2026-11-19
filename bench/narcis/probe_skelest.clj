(ns narcis.probe-skelest
  "buffer/skeleton's extrapolation, against a two-density file
   (100k short lines, then 100k long ones): while the scan runs
   the reported total is the scanned prefix's count scaled by
   its bytes — an overestimate while the scan is in the dense
   head, converging to the true count as the scan completes.
   Run: scripts/jolt-run.sh narcis.probe-skelest <dir>"
  (:require [narcis.store :as store]
            [narcis.buffer :as buffer]
            [clojure.java.io :as io]))

(defn -main [& [dir]]
  (let [path (str dir "/two-density.txt")
        sb (StringBuilder.)]
    (dotimes [i 100000] (.append sb "short line\n"))
    (dotimes [i 100000] (.append sb (str "a much longer line of text here, with plenty of characters in it to take up real space in the file ok\n")))
    (spit path (str sb))
    (println "file bytes:" (.length (io/file path)) "true lines: 200001")
    (let [st (store/open-store {:dir (str dir "/db") :name store/store-name})
          ed (buffer/open-editor st)]
      (buffer/open-file! ed path)
      (loop [n 0]
        (let [sk (buffer/skeleton path)]
          (println "poll" n "skeleton:" sk)
          (when (and (not (:done? sk)) (< n 40))
            (Thread/sleep 200)
            (recur (inc n)))))
      (println "final:" (buffer/skeleton path))
      (println "PROBE-SKELEST DONE"))))
