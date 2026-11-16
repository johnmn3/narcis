(ns narcis.run-tests
  "Headless test entry: clojure/bb/jolt -m narcis.run-tests"
  (:require [narcis.rope-test :as rt]))

(defn -main [& _]
  (rt/run-all!)
  (println "ALL-TESTS PASS"))
