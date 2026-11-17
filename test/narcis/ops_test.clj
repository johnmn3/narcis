(ns narcis.ops-test
  "S3 op-dispatch gate, headless: the same narcis.ops messages the
   browser sends, driven against a real editor on a real store —
   typing with auto-pair, smart enter, paredit motion, undo — with
   the buffer text checked after every step."
  (:require [clojure.string :as str]
            [clojure.test :as t]
            [narcis.buffer :as buffer]
            [narcis.ops :as ops]
            [narcis.store :as store]))

(defn- fresh-editor []
  (let [dir (str (or (System/getenv "TMPDIR") (System/getProperty "java.io.tmpdir"))
                 "/narcis-ops-" (System/nanoTime))
        st (store/open-store {:dir dir :name "narcis/buffers"})]
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

(t/deftest structural-ops
  ;; the paredit set through the op dispatch: slurp/barf reshape the
  ;; text as ONE undo step, wrap surrounds the form, kill-form hands
  ;; the form back as :copy, expand-selection grows by form.
  (let [ed (fresh-editor)
        path "struct.clj"]
    (buffer/open-buffer! ed path "(a (b) c)")
    ;; caret inside (b): slurp forward takes c into (b)
    (ops/apply-op ed {:op :set-caret :path path :offset 4})
    (let [res (ops/apply-op ed {:op :slurp-forward :path path})]
      (t/is (:ok res)))
    (t/is (= "(a (b c))" (text ed path)))
    ;; barf forward gives c back
    (let [res (ops/apply-op ed {:op :barf-forward :path path})]
      (t/is (:ok res)))
    (t/is (= "(a (b) c)" (text ed path)))
    ;; the slurp+barf pair was two undo steps, no more
    (buffer/undo! ed path)
    (t/is (= "(a (b c))" (text ed path)))
    (buffer/undo! ed path)
    (t/is (= "(a (b) c)" (text ed path)))
    ;; wrap: jonquil wraps the form the caret is on at the deepest
    ;; level — the atom b inside (b), not the list around it
    (ops/apply-op ed {:op :set-caret :path path :offset 4})
    (let [res (ops/apply-op ed {:op :wrap-square :path path})]
      (t/is (:ok res)))
    (t/is (= "(a ([b]) c)" (text ed path)))
    ;; kill-form removes the form at the caret (with the gap after it)
    ;; and returns the form's text as :copy
    (ops/apply-op ed {:op :set-caret :path path :offset 5})
    (let [res (ops/apply-op ed {:op :kill-form :path path})]
      (t/is (:ok res))
      (t/is (= "b" (:copy res))))
    (t/is (= "(a ([]) c)" (text ed path)))
    ;; expand-selection selects the form at the caret
    (ops/apply-op ed {:op :set-caret :path path :offset 4})
    (let [res (ops/apply-op ed {:op :expand-selection :path path})]
      (t/is (:ok res))
      (t/is (= [4 6] (:sel res))))
    ;; an inapplicable op answers not-ok, never throws
    (ops/apply-op ed {:op :set-caret :path path :offset 0})
    (let [res (ops/apply-op ed {:op :barf-forward :path path})]
      (t/is (not (:ok res))))))

(t/deftest completion-and-doc-ops
  ;; :complete answers candidates for the prefix at the caret (buffer
  ;; definitions + clojure.core via narcis.docs); :doc formats the
  ;; symbol-doc for a core var and for a buffer definition.
  (let [ed (fresh-editor)
        path "cmp.clj"
        src "(defn hello \"Says hi.\" [who] who)\n(map inc [1])\n(hel"]
    (buffer/open-buffer! ed path src)
    (ops/apply-op ed {:op :set-caret :path path :offset (count src)})
    (let [res (ops/apply-op ed {:op :complete :path path})]
      (t/is (:ok res))
      (t/is (= "hel" (:prefix res)))
      (t/is (some #(= "hello" (:symbol %)) (:candidates res))))
    (let [res (ops/apply-op ed {:op :doc :path path :symbol "map"})]
      (t/is (:ok res))
      (t/is (str/includes? (:doc res) "clojure.core/map"))
      (t/is (str/includes? (:doc res) "Returns a lazy sequence")))
    (let [res (ops/apply-op ed {:op :doc :path path :symbol "hello"})]
      (t/is (:ok res))
      (t/is (str/includes? (:doc res) "Says hi."))
      (t/is (str/includes? (:doc res) "buffer def")))))

(defn- console-until-idle [ed]
  (loop [n 0]
    (let [c (ops/apply-op ed {:op :console :since 0})]
      (if (or (= "idle" (:status c)) (> n 100))
        c
        (do (Thread/sleep 100) (recur (inc n)))))))

(defn- console-text [c]
  (str/join "\n" (map :text (:entries c))))

(t/deftest eval-session-ops
  ;; S4: Run evaluates the buffer (output lands in the console), the
  ;; REPL continues in the run's namespace, a second Run resets the
  ;; first run's definitions away, and Stop interrupts a runaway.
  (let [ed (fresh-editor)
        path "prog.clj"]
    (buffer/open-buffer! ed path
                         "(ns demo.core) (defn hello [who] (str \"Hi \" who)) (println (hello \"World\"))")
    (t/is (:ok (ops/apply-op ed {:op :run :path path})))
    (let [c (console-until-idle ed)]
      (t/is (str/includes? (console-text c) "Hi World")))
    (t/is (:ok (ops/apply-op ed {:op :repl :input "(hello \"REPL\")"})))
    (let [c (console-until-idle ed)]
      (t/is (str/includes? (console-text c) "\"Hi REPL\""))
      (t/is (= "demo.core" (:ns c))))
    ;; runaway + Stop
    (buffer/open-buffer! ed "loop.clj" "(loop [] (recur))")
    (t/is (:ok (ops/apply-op ed {:op :run :path "loop.clj"})))
    (loop [n 0]
      (when (and (not= "running" (:status (ops/apply-op ed {:op :console :since 0})))
                 (< n 50))
        (Thread/sleep 100) (recur (inc n))))
    (t/is (:ok (ops/apply-op ed {:op :stop})))
    (let [c (console-until-idle ed)]
      (t/is (str/includes? (console-text c) "Evaluation interrupted")))
    ;; the second Run reset demo.core away: hello no longer resolves
    (t/is (:ok (ops/apply-op ed {:op :repl :input "(hello \"x\")"})))
    (let [c (console-until-idle ed)]
      (t/is (some #(= :error (:kind %)) (:entries c))))))

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
