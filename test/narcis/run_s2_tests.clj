(ns narcis.run-s2-tests
    "S2 headless runner (jolt/JVM/bb): the lifted jonquil corpus
   (narcis.syntax-test, narcis.upstream-syntax-test, narcis.edit-test)
   plus Narcis's document-layer tests (narcis.edit-doc-test). Prints a
   TEST-SUMMARY line; exits non-zero on any failure or error."
    (:require [clojure.test :as t]
              [narcis.edit-doc-test]
              [narcis.edit-test]
              [narcis.syntax-test]
              [narcis.upstream-syntax-test]))

(defn -main [& _]
      (let [{:keys [test pass fail error] :as m}
            (t/run-tests 'narcis.syntax-test 'narcis.upstream-syntax-test
                         'narcis.edit-test 'narcis.edit-doc-test)]
           (println "TEST-SUMMARY" m)
           (System/exit (if (zero? (+ fail error)) 0 1))))
