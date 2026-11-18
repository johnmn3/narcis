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
  (:require [eve.alpha :as e] [eve.atom :as ea] [eve.host :as eh]
            [eve.deftype.array :as arr]
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
  (e/$ (let [app @a
             b (get-in app [:buffers "x.clj"])
             v (get-in app [:versions "hello"])]
         {:chars (text/chars (buffer/-root b))
          :lines (text/lines (buffer/-root b))
          :nundo (buffer/-nundo b)
          :hist-chars (text/chars (buffer/-doc (buffer/-undo b)))
          :theme (get-in app [:editor :theme])
          :cur (se/-cur v)
          :hist0 (arr/aget (se/-hist v) 0)})))

(defn- timed [label f]
  (let [t0 (System/nanoTime)
        r (f)]
    (println "PROBE" label "ms:" (Math/round (/ (- (System/nanoTime) t0) 1e6)))
    r))

(defn -main [dir]
  (let [base (str dir "/db")
        results (atom [])]
    (ea/persistent-atom-domain base)
    (let [a (e/atom {:id :narcis.store/app :persistent base} nil)]
      (eh/swap! a (fn [_] {:buffers {}
                           :editor {:tabs [] :active nil :rev 0 :theme nil}
                           :versions {}}))
      (timed "open-buffer swap"
             #(eh/swap! a (fn [app]
                            (assoc-in app [:buffers "x.clj"]
                                      (buffer/->BufferState
                                       (text/from-string "hello\nworld\n")
                                       0 nil nil nil "\n" 0 0 0 0 0)))))
      (timed "edit swap"
             #(eh/swap! a (fn [app]
                            (let [b (get-in app [:buffers "x.clj"])]
                              (assoc-in app [:buffers "x.clj"]
                                        (buffer/->BufferState
                                         (text/insert (buffer/-root b) 5 "!")
                                         6 nil
                                         (buffer/->History (buffer/-root b) nil)
                                         nil "\n" 1 1 0 1 0))))))
      (eh/swap! a (fn [app] (assoc-in app [:editor :theme] :dark)))
      (eh/swap! a (fn [app] (assoc-in app [:versions "hello"]
                                      (se/->Versioned "v1" nil))))
      (eh/swap! a (fn [app]
                    (let [h (arr/make-array 1)]
                      (arr/aset h 0 "v1")
                      (assoc-in app [:versions "hello"]
                                (se/->Versioned "v2" h)))))
      (swap! results conj (check "nested reads" expected (read-back a)))
      (println "PROBE plain deref:"
               (try (let [v @a]
                      (str "delivered buffers=" (count (:buffers v))))
                    (catch :default t
                      (str "THREW " (type t) ": " (ex-message t))))))
    (ea/close-atom-domain! (ea/persistent-atom-domain base))
    (ea/persistent-atom-domain base)
    (let [a2 (e/atom {:id :narcis.store/app :persistent base} nil)]
      (swap! results conj (check "after reopen" expected (read-back a2))))
    (ea/close-atom-domain! (ea/persistent-atom-domain base))
    (println "PROBE-ONEATOM" (if (every? true? @results) "PASS" "FAIL"))))
