(ns narcis.run-s2-tests
  "S2 headless runner (jolt/JVM/bb): the lifted jonquil corpus
   (narcis.syntax-test, narcis.upstream-syntax-test, narcis.edit-test)
   plus Narcis's rope-layer tests (narcis.edit-rope-test). Prints a
   TEST-SUMMARY line; exits non-zero on any failure or error."
  (:require [clojure.test :as t]
            [narcis.syntax-test]
            [narcis.upstream-syntax-test]
            [narcis.edit-test]
            [narcis.edit-rope-test]))

(defn -main [& _]
  (let [{:keys [test pass fail error] :as m}
        (t/run-tests 'narcis.syntax-test 'narcis.upstream-syntax-test
                     'narcis.edit-test 'narcis.edit-rope-test)]
    (println "TEST-SUMMARY" m)
    (System/exit (if (zero? (+ fail error)) 0 1))))
