(ns narcis.probe-find
  "Time the find path over the big-file run's store: slice-string at
   several offsets, then the real :find-next op for the end token.
   Args: <db-dir> <path>"
  (:require [narcis.store :as store]
            [narcis.buffer :as buffer]
            [narcis.ops :as ops]
            [narcis.text :as text]))

(defn -main [& [db path]]
  (let [st (store/open-store {:dir db :name store/store-name})
        ed (buffer/open-editor st)
        t (fn [label f] (let [t0 (System/nanoTime) r (f)]
                          (println label (Math/round (/ (- (System/nanoTime) t0) 1e6)) "ms ->" (pr-str r))
                          r))]
    (buffer/open-file! ed path)
    (t "chars" #(buffer/read ed path (fn [{doc :root}] (text/chars doc))))
    (doseq [pos [0 1000000 50000000 100000000 200000000]]
      (t (str "slice-string@" pos)
         #(buffer/read ed path (fn [{doc :root}] (count (text/slice-string doc pos (min (+ pos 65543) (text/chars doc))))))))
    (t "find-next zqxfinal (the real op)"
       #(ops/apply-op ed {:op :find-next :path path :query "zqxfinal"}))
    (t "find-next zqx1050000 (mid)"
       #(ops/apply-op ed {:op :find-next :path path :query "zqx1050000"}))
    (store/close-store st)
    (System/exit 0)))
