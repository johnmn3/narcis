(ns narcis.ops-test
    "S3 op-dispatch gate, headless: the same narcis.ops messages the
   browser sends, driven against a real editor on a real store —
   typing with auto-pair, smart enter, paredit motion, undo — with
   the buffer text checked after every step."
    (:require [clojure.test :as t]
              [narcis.buffer :as buffer]
              [narcis.ops :as ops]
              [narcis.store :as store]))

(defn- fresh-editor []
       (let [dir (str (or (System/getenv "TMPDIR") (System/getProperty "java.io.tmpdir"))
                      "/narcis-ops-" (System/nanoTime))
             st (store/open-store {:dir dir :name store/store-name})]
            (buffer/open-editor st)))

(defn- text [ed path] (buffer/buffer-text ed path))

(t/deftest typing-session
           (let [ed (fresh-editor)
                 path "session.clj"]
                (buffer/open-buffer! ed path "")
    ;; type (defn f [x]  with auto-pair on every bracket
                (doseq [ch "(defn f [x]"]
                       (let [res (ops/apply-op ed {:op :type-char :path path :ch (str ch)})]
                            (t/is (:ok res) (str "typing " ch))))
                (t/is (= "(defn f [x])" (text ed path)))
                (t/is (= 11 (buffer/cursor ed path)))
    ;; step over the auto-paired ) by typing the closer
                (ops/apply-op ed {:op :type-char :path path :ch ")"})
                (t/is (= 12 (buffer/cursor ed path)))
                (t/is (= "(defn f [x])" (text ed path)))
    ;; smart enter inside the defn: go before the final ) and enter
                (ops/apply-op ed {:op :set-caret :path path :offset 11})
                (let [res (ops/apply-op ed {:op :smart-enter :path path})]
                     (t/is (:ok res)))
                (t/is (= "(defn f [x]\n  )" (text ed path)))
    ;; type a body form, auto-paired
                (doseq [ch "(+ x 1"]
                       (ops/apply-op ed {:op :type-char :path path :ch (str ch)}))
                (t/is (= "(defn f [x]\n  (+ x 1))" (text ed path)))
    ;; paredit motion: caret to start, forward-sexp crosses the defn
                (ops/apply-op ed {:op :set-caret :path path :offset 0})
                (let [res (ops/apply-op ed {:op :forward-sexp :path path})]
                     (t/is (= (count "(defn f [x]\n  (+ x 1))") (:caret res))))
                (let [res (ops/apply-op ed {:op :backward-sexp :path path})]
                     (t/is (= 0 (:caret res))))
    ;; undo walks back through the whole session to the empty buffer
                (loop [n 0]
                      (when (buffer/undo! ed path) (recur (inc n))))
                (t/is (= "" (text ed path)))
                (t/is (pos? (buffer/redo-depth ed path)))))

(t/deftest comment-and-indent-ops
  ;; jonquil's own semantics, as the S2 corpus pins them: indent with a
  ;; selection block-indents every spanned line by two spaces;
  ;; toggle-comment puts "; " at the spanned lines' starts.
           (let [ed (fresh-editor)
                 path "ops.clj"]
                (buffer/open-buffer! ed path "(defn g []\n(+ 1 2))")
                (ops/apply-op ed {:op :set-caret :path path :offset 11 :sel [11 18]})
                (let [res (ops/apply-op ed {:op :indent :path path})]
                     (t/is (:ok res)))
                (t/is (= "(defn g []\n  (+ 1 2))" (text ed path)))
                (ops/apply-op ed {:op :set-caret :path path :offset 14 :sel nil})
                (let [res (ops/apply-op ed {:op :toggle-comment :path path})]
                     (t/is (:ok res)))
                (t/is (= "(defn g []\n;   (+ 1 2))" (text ed path)))))

(defn -main [& _]
      (let [{:keys [fail error] :as m} (t/run-tests 'narcis.ops-test)]
           (println "TEST-SUMMARY" m)
           (System/exit (if (zero? (+ fail error)) 0 1))))
