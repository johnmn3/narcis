(ns narcis.probe-oneatom
  "Probe: can ONE Eve atom hold the whole app state — a plain map
   {:buffers {path BufferState} :editor {...} :versions {name
   Versioned}} — with BufferState (whose document is an eve.text
   tree) and Versioned (whose history is an Eve array) NESTED in
   the map, swapped in place, read in a $ region, and recovered
   after close/reopen? The per-atom design keeps every deftype at
   an atom root because nesting inside a collection value was
   measured to die in freeze (eve.flat.chunk/unwritable) at the
   rope spike. This probe re-derives that at the current pin for
   the one-atom shape before any caller is rewritten on it.
   Run: scripts/jolt-run.sh narcis.probe-oneatom <dir>"
  (:require [eve.alpha :as eve :refer [$]]
            [narcis.text :as text] [narcis.buffer :as buffer]
            [narcis.store-eve :as se]))

(defn- check [label expected actual]
  (println "PROBE" label "expected" (pr-str expected) "got" (pr-str actual)
           (if (= expected actual) "OK" "MISMATCH"))
  (= expected actual))

(def expected
  {:chars 13 :lines 3 :nundo 1 :hist-chars 12
   :theme :dark :cur "v2" :hist0 "v1"})

(defn- read-back [a]
  ($ (let [app @a
             b (get-in app [:buffers "x.clj"])
             v (get-in app [:versions "hello"])]
         {:chars (text/chars (buffer/-root b))
          :lines (text/lines (buffer/-root b))
          :nundo (buffer/-nundo b)
          :hist-chars (text/chars (buffer/-doc (buffer/-undo b)))
          :theme (get-in app [:editor :theme])
          :cur (se/-cur v)
          :hist0 (eve/aget (se/-hist v) 0)})))

(defn- timed [label f]
  (let [t0 (System/nanoTime)
        r (f)]
    (println "PROBE" label "ms:" (Math/round (/ (- (System/nanoTime) t0) 1e6)))
    r))

(defn -main [dir]
  (let [base (str dir "/db")
        results (atom [])]
    (eve/persistent-atom-domain base)
    (let [a (eve/atom {:id :narcis.store/app :persistent base} nil)]
      (swap! a (fn [_] {:buffers {}
                           :editor {:tabs [] :active nil :rev 0 :theme nil}
                           :versions {}}))
      (timed "open-buffer swap"
             #(swap! a (fn [app]
                            (assoc-in app [:buffers "x.clj"]
                                      (buffer/->BufferState
                                       (text/from-string "hello\nworld\n")
                                       0 nil nil nil "\n" 0 0 0 0 0)))))
      (timed "edit swap"
             #(swap! a (fn [app]
                            (let [b (get-in app [:buffers "x.clj"])]
                              (assoc-in app [:buffers "x.clj"]
                                        (buffer/->BufferState
                                         (text/insert (buffer/-root b) 5 "!")
                                         6 nil
                                         (buffer/->History (buffer/-root b) nil)
                                         nil "\n" 1 1 0 1 0))))))
      (swap! a (fn [app] (assoc-in app [:editor :theme] :dark)))
      (swap! a (fn [app] (assoc-in app [:versions "hello"]
                                      (se/->Versioned "v1" nil))))
      (swap! a (fn [app]
                    (let [h (eve/make-array 1)]
                      (eve/aset h 0 "v1")
                      (assoc-in app [:versions "hello"]
                                (se/->Versioned "v2" h)))))
      (swap! results conj (check "nested reads" expected (read-back a)))
      (println "PROBE plain deref:"
               (try (let [v @a]
                      (str "delivered buffers=" (count (:buffers v))))
                    (catch :default t
                      (str "THREW " (type t) ": " (ex-message t))))))
    (eve/close-atom-domain! (eve/persistent-atom-domain base))
    (eve/persistent-atom-domain base)
    (let [a2 (eve/atom {:id :narcis.store/app :persistent base} nil)]
      (swap! results conj (check "after reopen" expected (read-back a2))))
    (eve/close-atom-domain! (eve/persistent-atom-domain base))
    (println "PROBE-ONEATOM" (if (every? true? @results) "PASS" "FAIL"))))
