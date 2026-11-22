(ns narcis.debugger-wiring-test
    "The debugger's wiring into the console, on this Jolt: without
   jolt.debug (the released Jolt has none) every debugger answer is
   its not-available one and a Run with breakpoints still runs; the
   frames of a real error are the note that names jolt.debug."
    (:require [clojure.string]
              [clojure.test :as t :refer [deftest is testing]]
              [narcis.interactions :as interactions]
              [narcis.store :as store]))

(defn- temp-dir []
       (let [f (java.io.File/createTempFile "narcis-debug" "")]
            (.delete f)
            (.mkdirs f)
            (.getPath f)))

(defn- until-idle [con]
       (loop [n 0]
             (when (and (< n 100) (not= "idle" (interactions/status con)))
                   (Thread/sleep 100)
                   (recur (inc n)))))

(defn- transcript [con]
       (apply str (map second (:entries (interactions/read-from con 0)))))

(deftest debugger-on-a-jolt-without-jolt-debug
         (let [st (store/open-store {:dir (temp-dir) :name store/store-name})
               con (interactions/open st)]
              (testing "the state says the debugger is not available"
                       (is (false? (:available (interactions/debug-state con))))
                       (is (false? (:break-on? (interactions/debug-state con))))
                       (is (false? (:paused? (interactions/debug-state con)))))
              (testing "arming and Break on error answer the reason, changing nothing"
                       (is (contains? (interactions/arm-breakpoints! con true) :error))
                       (is (contains? (interactions/toggle-break! con) :error))
                       (is (false? (:break-on? (interactions/debug-state con)))))
              (testing "a Run with a breakpoint still runs, and says why it cannot pause"
                       (interactions/run! con "(defn f [x] (+ x 1))\n(println (f 41))" "test.clj" nil #{2})
                       (until-idle con)
                       (let [s (transcript con)]
                            (is (clojure.string/includes? s "42"))
                            (is (clojure.string/includes? s "Breakpoints are off"))))
              (testing "an error's frames are the jolt.debug note"
                       (interactions/run! con "(defn g [] (/ 1 0))\n(g)" "test.clj" nil)
                       (until-idle con)
                       (let [d (interactions/frames! con)]
                            (is (false? (:paused? d)))
                            (is (= :note (:kind (first (:rows d)))))
                            (is (clojure.string/includes? (:text (first (:rows d))) "jolt.debug"))))))

(defn -main [& _]
      (let [{:keys [fail error]} (t/run-tests 'narcis.debugger-wiring-test)]
           (System/exit (if (zero? (+ fail error)) 0 1))))
