(ns narcis.run-tests
    "Headless test entry: clojure/jolt -m narcis.run-tests"
    (:require [narcis.text-test :as tt]))

(defn -main [& _]
      (tt/run-all!)
      (println "ALL-TESTS PASS"))
