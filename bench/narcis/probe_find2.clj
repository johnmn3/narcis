(ns narcis.probe-find2
  "Time file-find-forward to the mid markers.
   Run: jolt-run.sh narcis.probe-find2"
  (:require [narcis.text :as text]))

(defn -main [& _]
  (let [path "/home/hatch/workspace/rope-deftype-lane/bigfile-runs/20261118-133528-200mb/proj/big.txt"]
    (doseq [tok ["zqx500000" "zqx1050000"]]
      (let [t0 (System/nanoTime)
            at (text/file-find-forward path tok 0)
            ms (quot (- (System/nanoTime) t0) 1000000)]
        (println tok "at" at "in" ms "ms")))
    (System/exit 0)))
