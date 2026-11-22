(ns narcis.debugger-live-test
    "The debugger live, on a Jolt with jolt.debug (the nightly; the
   released Jolt has none): a Run with a breakpoint pauses in it with
   its locals, an expression evaluates in the paused frame, Continue
   finishes the program, and Break on error pauses at a throw. On a
   Jolt without jolt.debug each test takes its skipped path, as
   upstream's do."
    (:require [clojure.string :as str]
              [clojure.test :as t :refer [deftest is testing]]
              [narcis.debugger :as debugger]
              [narcis.interactions :as interactions]
              [narcis.store :as store]))

(defn- temp-dir []
       (let [f (java.io.File/createTempFile "narcis-debug-live" "")]
            (java.io.File/.delete f)
            (java.io.File/.mkdirs f)
            (java.io.File/.getPath f)))

(defn- open-con []
       (interactions/open (store/open-store {:dir (temp-dir) :name store/store-name})))

(defn- wait-for [pred what]
       (loop [n 0]
             (cond (pred) true
                   (> n 100) (throw (ex-info (str "timed out waiting for " what) {}))
                   :else (do (Thread/sleep 100) (recur (inc n))))))

(defn- transcript [con]
       (clojure.string/join (map second (:entries (interactions/read-from con 0)))))

(def ^:private program
     "(defn f [x]\n  (let [y (+ x 1)]\n    (* y 2)))\n(println (f 41))\n")

(deftest breakpoint-pauses-with-locals
         (if-not (debugger/available?)
                 (is true "skipped: this Jolt has no jolt.debug")
                 (let [con (open-con)]
                      (interactions/run! con program "test.clj" nil #{3})
                      (wait-for #(-> con interactions/debug-state :paused?) "the breakpoint pause")
                      (testing "the pause names the line and holds the frame's locals"
                               (let [d (interactions/frames! con)
                                     locals (into {} (comp (filter #(= :local (:kind %)))
                                                           (map (fn [r] [(:name r) (:text r)])))
                                                  (:rows d))]
                                    (is (true? (:paused? d)))
                                    (is (str/includes? (:title d) "Breakpoint at line 3"))
                                    (is (some #(str/includes? % "y = 42") (vals locals)))))
                      (testing "an expression evaluates in the paused frame"
                               (let [d (interactions/frames! con)
                                     r (interactions/debug-eval! con (:error-id d) 0 "(+ y 1)")]
                                    (is (= "43" (:text r)) (str r))))
                      (testing "Continue finishes the program and clears the pause"
                               (is (true? (interactions/continue! con)))
                               (wait-for #(= "idle" (interactions/status con)) "the run to finish")
                               (is (str/includes? (transcript con) "84"))
                               (is (false? (-> con interactions/debug-state :paused?)))))))

(deftest break-on-error-pauses-at-the-throw
         (if-not (debugger/available?)
                 (is true "skipped: this Jolt has no jolt.debug")
                 (let [con (open-con)]
                      (is (= {:on? true} (interactions/toggle-break! con)))
                      (interactions/run! con "(defn g [x] (/ x 0))\n(println (g 1))" "test.clj" nil)
                      (wait-for #(-> con interactions/debug-state :paused?) "the break-on-error pause")
                      (let [d (interactions/frames! con)]
                           (is (true? (:paused? d)))
                           (is (some #(str/includes? % "g") (map :text (filter #(= :frame (:kind %)) (:rows d))))))
                      (is (true? (interactions/continue! con)))
                      (wait-for #(= "idle" (interactions/status con)) "the run to finish")
                      (is (str/includes? (transcript con) "Divide by zero")))))

(defn -main [& _]
      (let [{:keys [fail error]} (t/run-tests 'narcis.debugger-live-test)]
           (System/exit (if (zero? (+ fail error)) 0 1))))
