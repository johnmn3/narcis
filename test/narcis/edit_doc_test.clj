(ns narcis.edit-doc-test
    "Narcis's own tests for the document layer of narcis.edit: every jonquil
   op driven through a document must produce exactly the text and caret
   the lifted text oracle (jonquil's apply-edits over the op result)
   produces on the same snapshot. Plus :form scoping and a
   narcis.buffer integration walk (op -> one undo step -> undo/redo)."
    (:require [clojure.test :refer [deftest is testing]]
              [narcis.buffer :as buf]
              [narcis.edit :as edit]
              [narcis.store :as store]
              [narcis.test-util :as test-util]
              [narcis.text :as text]))

(defn- snap [text caret sel]
       {:text text :caret (or sel caret) :sel-start (if sel (first sel) caret) :sel-end (if sel (second sel) caret)})

(defn- oracle
       "Text-oracle result of op-fn on (text, caret, sel): {:text :caret}."
       [text caret sel op-fn]
       (when-let [r (op-fn (snap text caret sel))] {:text (edit/apply-edits text (:edits r)) :caret (:caret r)}))

(defn- via-doc
       [s caret sel op-fn scope]
       (test-util/with-heap-domain
        (fn []
            (when-let [r (edit/run-op (text/from-string s) caret sel op-fn scope)] {:text (text/to-string (:doc r)) :caret (:caret r)}))))

(def cases
  ;; [text caret sel op-fn label]
     [["(defn f [x]" 11 nil (fn [s] (edit/smart-enter s)) "smart-enter indent"]
      ["  (foo))" 8 nil (fn [s] (edit/smart-enter s)) "smart-enter dedent"]
      ["()" 1 nil (fn [s] (edit/smart-enter s)) "smart-enter between pair"]
      ["()" 1 nil (fn [s] (edit/smart-enter s {:between-pair? false})) "smart-enter pair off"]
      ["(a b)" 1 nil (fn [s] (edit/indent s)) "indent caret"]
      ["(a\nb)" 0 [0 5] (fn [s] (edit/indent s)) "indent selection"]
      ["  (a\n  b)" 0 [0 9] (fn [s] (edit/unindent s)) "unindent selection"]
      ["(a b)\n(c d)" 0 nil (fn [s] (edit/toggle-comment s)) "comment line"]
      ["; (a b)" 0 nil (fn [s] (edit/toggle-comment s)) "uncomment line"]
      ["(foo bar)" 4 nil (fn [s] (edit/insert-open s \()) "insert-open wraps symbol"]
      ["()" 1 nil (fn [s] (edit/insert-close s \))) "insert-close steps over"]
      ["()" 1 nil (fn [s] (edit/backspace-pair s)) "backspace pair"]
      ["(defn foo [] (foo))" 6 nil (fn [s] (edit/rename s "bar")) "rename"]
      ["(a [b] c)" 3 nil (fn [s] (edit/smart-enter s)) "smart-enter nested"]])

(deftest doc-layer-matches-oracle-test
         (doseq [[text caret sel op-fn label] cases]
                (testing label
                         (let [o (oracle text caret sel op-fn)
                               r (via-doc text caret sel op-fn :all)]
                              (is (= (some? o) (some? r)) "applicability agrees")
                              (when o
                                    (is (= (:text o) (:text r)) "text agrees")
                                    (is (= (:caret o) (:caret r)) "caret agrees"))))))

(deftest apply-edits-to-doc-test
         (testing "random ascending edit sets: document result = text oracle"
                  (let [rng (atom 4242)
                        rnd (fn [n] (let [s (swap! rng (fn [x] (mod (+ (* x 1103515245) 12345) 2147483648)))] (if (zero? n) 0 (mod s n))))]
                       (dotimes [i 60]
                                (let [text (apply str (repeatedly (+ 5 (rnd 30)) #(char (+ 97 (rnd 26)))))
                                      k (inc (rnd 4))
                                      cuts (sort (repeatedly (* 2 k) #(rnd (inc (count text)))))
                                      edits (mapv (fn [[a b]] [a (max a b) (str "X" i)]) (partition 2 cuts))
                                      expected (edit/apply-edits text edits)
                                      actual (test-util/with-heap-domain
                                              (fn [] (text/to-string (edit/apply-edits-to-doc (text/from-string text) edits))))]
                                     (is (= expected actual)))))))

(deftest form-scope-test
         (testing "smart-enter inside the second top-level form leaves the first form byte-identical"
                  (let [text "(defn a [] 1)\n(defn b [x]\n  (+ x 1))"
                        caret (dec (count text))
                        out (test-util/with-heap-domain
                             (fn [] (let [doc (text/from-string text)
                                          r (edit/smart-enter-doc doc (edit/region-of doc) caret nil)]
                                         (when r (text/to-string (:doc r))))))
                        r out
                        expected (:text (oracle text caret nil (fn [s] (edit/smart-enter s))))]
                       (is (some? r))
                       (is (.startsWith out "(defn a [] 1)\n"))
                       (is (= expected out)))))

(deftest buffer-integration-test
         (testing "an edit op through narcis.buffer is ONE undo step; undo/redo walk it"
                  (let [dir (str (or (System/getenv "TMPDIR") (System/getProperty "java.io.tmpdir")) "/narcis-edit-int-" (System/nanoTime))
                        st (store/open-store {:dir dir :name store/store-name})
                        ed (buf/open-editor st)
                        path "x.clj"
                        text "(defn f [x]\n  (+ x 1))"]
                       (buf/open-buffer! ed path text)
                       (let [caret (dec (count text))
                             expected (:text (oracle text caret nil (fn [s] (edit/smart-enter s))))]
        ;; the op runs inside the buffer atom's swap: one swap, one undo step
                            (buf/edit! ed path (fn [doc _ _ _]
                                                   (edit/smart-enter-doc doc (edit/region-of doc) caret nil)))
                            (is (= expected (buf/buffer-text ed path)))
                            (is (= 1 (buf/undo-depth ed path)) "one op = one undo step")
                            (is (true? (buf/undo! ed path)))
                            (is (= text (buf/buffer-text ed path)))
                            (is (true? (buf/redo! ed path)))
                            (is (= expected (buf/buffer-text ed path))))
                       (store/close-store st))))
