(ns narcis.run-all-tests
  "Every headless test, on Jolt: the lifted jonquil corpus (syntax,
   edit, structural, commands, console, eval), Narcis's own (the text
   layer against a string model and the file door, the document layer
   of the edit ops, the window payload, the ops, the app surface, the
   features of the finished window and its console). Prints a
   TEST-SUMMARY line; exits non-zero on any failure or error."
  (:require [clojure.test :as t]
            [narcis.text-test :as text-test]
            [narcis.syntax-test]
            [narcis.upstream-syntax-test]
            [narcis.edit-test]
            [narcis.edit-doc-test]
            [narcis.structural-test]
            [narcis.commands-test]
            [narcis.console-test]
            [narcis.eval-test]
            [narcis.view-test]
            [narcis.ops-test]
            [narcis.app-test]
            [narcis.features-test]))

(defn -main [& _]
  (text-test/run-all!)
  (let [{:keys [fail error] :as m}
        (t/run-tests 'narcis.syntax-test 'narcis.upstream-syntax-test
                     'narcis.edit-test 'narcis.edit-doc-test
                     'narcis.structural-test 'narcis.commands-test
                     'narcis.console-test 'narcis.eval-test
                     'narcis.view-test 'narcis.ops-test 'narcis.app-test
                     'narcis.features-test)]
    (println "TEST-SUMMARY" m)
    (System/exit (if (zero? (+ fail error)) 0 1))))
