(ns narcis.probe-before
  "The OLD open's cost, isolated: the whole file ingested in one go
   (what open-file! did inside its registration swap before the
   progressive open). Run in a heap domain, so this is the ingest
   alone, without the old open's whole-document disk commit."
  (:require [narcis.test-util :as tu] [narcis.text :as text]))
(defn -main [& [path]]
  (let [path (or path "/home/hatch/workspace/1brc-pair13/bench/v500k.txt")]
    (tu/with-heap-domain
      (fn []
        (let [t0 (System/currentTimeMillis)
              r (text/ingest-file path)
              dt (- (System/currentTimeMillis) t0)]
          (println "whole-ingest" path ":" dt "ms for" (text/chars (:doc r)) "chars"))))
    (System/exit 0)))
