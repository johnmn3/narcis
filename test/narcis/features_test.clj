(ns narcis.features-test
    "The ops and the console the finished window drives, headless against
   a real editor on a real store: structural editing, formatting,
   completion and documentation, find at any size, a file's line
   ending kept through save, the caret a message carries — and the
   Interactions console: Run, the prompt, a bracket left open, Stop,
   the transcript in its atom."
    (:require [clojure.java.io]
              [clojure.string :as str]
              [clojure.test :as t]
              [narcis.buffer :as buffer]
              [narcis.interactions :as interactions]
              [narcis.menus]
              [narcis.ops :as ops]
              [narcis.store :as store]
              [narcis.view :as view]))

(defn- tmp [name]
       (str (or (System/getenv "TMPDIR") (System/getProperty "java.io.tmpdir"))
            "/narcis-feat-" (System/nanoTime) "-" name))

(defn- fresh-store [] (store/open-store {:dir (tmp "db") :name store/store-name}))

(defn- editor-with [path content]
       (let [ed (buffer/open-editor (fresh-store))]
            (buffer/open-buffer! ed path content)
            ed))

(defn- text [ed path] (buffer/buffer-text ed path))

(t/deftest structural-ops
           (let [p "s.clj"
                 ed (editor-with p "(foo (bar a) b c)")]
                (t/testing "slurp forward takes the next form in; one undo step"
                           (let [res (ops/apply-op ed {:op :structural :name "slurp-forward" :path p :caret 9})]
                                (t/is (:ok res))
                                (t/is (= "(foo (bar a b) c)" (text ed p)))
                                (t/is (= 1 (:undo res)))))
                (t/testing "barf forward puts it back out"
                           (t/is (:ok (ops/apply-op ed {:op :structural :name "barf-forward" :path p :caret 9})))
                           (t/is (= "(foo (bar a) b c)" (text ed p))))
                (t/testing "a move by form moves the caret and makes no undo step"
                           (let [before (buffer/undo-depth ed p)
                                 res (ops/apply-op ed {:op :structural :name "forward" :path p :caret 5})]
                                (t/is (:ok res))
                                (t/is (= 12 (:caret res)))
                                (t/is (= before (:undo res)))
                                (t/is (= "(foo (bar a) b c)" (text ed p)))))
                (t/testing "kill form answers the killed text for the clipboard"
                           (let [res (ops/apply-op ed {:op :structural :name "kill-form" :path p :caret 5})]
                                (t/is (:ok res))
                                (t/is (= "(bar a)" (:copy res)))
                                (t/is (not (str/includes? (text ed p) "bar")))))
                (t/testing "an op that does not apply says so and changes nothing"
                           (let [before (text ed p)
                                 res (ops/apply-op ed {:op :structural :name "raise" :path p :caret 0})]
                                (t/is (not (:ok res)))
                                (t/is (= before (text ed p)))))
                (t/testing "an unknown name is an error"
                           (t/is (not (:ok (ops/apply-op ed {:op :structural :name "nope" :path p})))))))

(t/deftest format-ops
           (let [p "f.clj"
                 ed (editor-with p "(defn f [x]\n(+ x\n1))\n")]
                (t/is (:ok (ops/apply-op ed {:op :format-all :path p :caret 0})))
                (t/is (= "(defn f [x]\n  (+ x\n     1))\n" (text ed p)))
                (t/is (= 1 (buffer/undo-depth ed p)) "one edit, one undo step")
                (t/is (not (:ok (ops/apply-op ed {:op :format-all :path p :caret 0})))
                      "formatted text: nothing to do")))

(t/deftest completion-and-doc
           (let [p "c.clj"
                 ed (editor-with p "(defn my-helper [x] x)\n(ma")]
                (let [res (ops/apply-op ed {:op :complete :path p :caret 26})]
                     (t/is (:ok res))
                     (t/is (= "ma" (:prefix res)))
                     (t/is (= [24 26] [(:start res) (:end res)]))
                     (t/is (some #(= "map" (:symbol %)) (:candidates res)))
                     (t/is (some #(= "macroexpand" (:symbol %)) (:candidates res)))
                     (t/is (every? #(str/starts-with? (:symbol %) "ma") (:candidates res))))
                (t/testing "a name the buffer defines is a candidate"
                           (buffer/insert-text! ed p 26 "\n(my-")
                           (let [res (ops/apply-op ed {:op :complete :path p :caret 31})]
                                (t/is (some #(= "my-helper" (:symbol %)) (:candidates res)))))
                (t/testing "documentation of a named symbol, and of the one at the caret"
                           (let [res (ops/apply-op ed {:op :doc :path p :symbol "map"})]
                                (t/is (:ok res))
                                (t/is (str/includes? (:doc res) "map"))
                                (t/is (str/includes? (:doc res) "lazy sequence")))
                           (let [res (ops/apply-op ed {:op :doc :path p :caret 3})]
                                (t/is (= "defn" (:symbol res)))))
                (t/testing "completion accepted the way the page does: the word is the selection the insert replaces"
                           (let [res (ops/apply-op ed {:op :complete :path p :caret 31})]
                                (t/is (= [28 31] [(:start res) (:end res)]))
                                (ops/apply-op ed {:op :insert-text :path p :text "my-helper"
                                                  :caret (:end res) :sel [(:start res) (:end res)]}))
                           (t/is (str/ends-with? (text ed p) "\n(my-helper")))))

(t/deftest find-at-any-size
  ;; a buffer over the whole-text analysis limit: find reads it in slices
           (let [p "big.clj"
                 n 12000
                 content (apply str (map (fn [i] (str "(defn fn-" i " [x] (+ x " i ")) ;; comment " i "\n")) (range n)))
                 ed (editor-with p content)]
                (t/is (> (count content) view/exact-limit))
                (let [res (ops/apply-op ed {:op :find-next :path p :query "fn-11999 "})]
                     (t/is (:ok res))
                     (t/is (= (str/index-of content "fn-11999 ") (first (:match res)))))
                (t/testing "wrapping forward from the end, and backward from the start"
                           (let [res (ops/apply-op ed {:op :find-next :path p :query "fn-0 "})]
                                (t/is (= 6 (first (:match res)))))
                           (ops/apply-op ed {:op :set-caret :path p :offset 0})
                           (let [res (ops/apply-op ed {:op :find-prev :path p :query "comment 11999"})]
                                (t/is (= (str/last-index-of content "comment 11999") (first (:match res))))))
                (t/testing "a query that spans a line break"
                           (let [res (ops/apply-op ed {:op :find-next :path p :query "comment 7\n(defn fn-8 " :from 0})]
                                (t/is (:ok res))))
                (t/is (not (:ok (ops/apply-op ed {:op :find-next :path p :query "no such text"}))))
                (t/testing "the window of a large buffer is served in window mode, typing still lands"
                           (let [win (buffer/read ed p (fn [s] (view/window (:root s) {:start 6000 :count 50 :key (:text-key s)})))]
                                (t/is (= :window (:spans-mode win)))
                                (t/is (= 50 (count (:lines win))))
                                (t/is (some (fn [[k _ _]] (= "special-form" k)) (:spans (first (:lines win))))))
                           (ops/apply-op ed {:op :type-char :path p :ch "z" :caret 0})
                           (t/is (str/starts-with? (text ed p) "z(defn fn-0 ")))))

(t/deftest line-ending-kept-through-save
           (let [f (tmp "crlf.clj")
                 content "(ns a)\r\n(def x 1)\r\n"
                 _ (spit f content)
                 ed (buffer/open-editor (fresh-store))]
                (ops/apply-op ed {:op :open :path f})
                (t/is (= "\r\n" (buffer/eol ed f)))
                (t/is (= "(ns a)\n(def x 1)\n" (text ed f)) "a line break is one character in the buffer")
                (ops/apply-op ed {:op :insert-text :path f :text "(def y 2)\n" :caret 17})
                (t/is (:ok (ops/apply-op ed {:op :save :path f})))
                (t/is (= "(ns a)\r\n(def x 1)\r\n(def y 2)\r\n" (slurp f)))
                (t/testing "save as: a document of its own in its own atom, the old tab closed"
                           (let [g (tmp "copy.clj")
                                 res (ops/apply-op ed {:op :save-as :path f :new-path g})]
                                (t/is (:ok res))
                                (t/is (= (slurp f) (slurp g)))
                                (t/is (= [g] (buffer/tabs ed)))
                                (ops/apply-op ed {:op :type-char :path g :ch "q" :caret 0})
                                (t/is (str/starts-with? (text ed g) "q(ns a)"))))))

(t/deftest message-caret-and-one-swap
           (let [p "m.clj"
                 ed (editor-with p "abc")]
                (t/testing "an op acts at the caret its message carries, in its own swap"
                           (let [rev0 (:rev (buffer/snapshot ed p))
                                 res (ops/apply-op ed {:op :type-char :path p :ch "X" :caret 2})]
                                (t/is (= "abXc" (text ed p)))
                                (t/is (= 3 (:caret res)))
                                (t/is (= (inc rev0) (:rev (buffer/snapshot ed p))) "one swap on the buffer atom")))
                (t/testing "a selection the message carries is replaced"
                           (ops/apply-op ed {:op :insert-text :path p :text "__" :caret 1 :sel [0 2]})
                           (t/is (= "__Xc" (text ed p))))
                (t/testing "pasted CRLF text is its lines; the caret lands after them"
                           (let [res (ops/apply-op ed {:op :insert-text :path p :text "1\r\n2\r\n" :caret 0})]
                                (t/is (= "1\n2\n__Xc" (text ed p)))
                                (t/is (= 4 (:caret res)))))
                (t/testing "undo and redo walk the documents; the undone ones are still there"
                           (t/is (:ok (ops/apply-op ed {:op :undo :path p})))
                           (t/is (= "__Xc" (text ed p)))
                           (t/is (:ok (ops/apply-op ed {:op :redo :path p})))
                           (t/is (= "1\n2\n__Xc" (text ed p)))
                           (t/is (= {:undo ["abc" "abXc" "__Xc"] :redo []} (buffer/history-texts ed p))))))

;; ---------------------------------------------------------------------------
;; the Interactions console

(defn- settle
       "Wait (up to ms) until the console is idle; flush; answer the transcript's text."
       [con ms]
       (let [deadline (+ (System/currentTimeMillis) ms)]
            (loop []
                  (if (and (not= "idle" (interactions/status con)) (< (System/currentTimeMillis) deadline))
                      (do (Thread/sleep 20) (recur))
                      (do (Thread/sleep 60)
                          (apply str (map second (:entries (interactions/read-from con 0)))))))))

(t/deftest console-run-prompt-stop
           (let [st (fresh-store)
                 con (interactions/open st)]
                (t/testing "Run: the header line, what the program prints, its last value"
                           (interactions/run! con "(println \"Hello, Narcis!\")\n(defn twice [x] (* 2 x))\n(+ 1 2)" "demo.clj" nil)
                           (let [out (settle con 10000)]
                                (t/is (str/includes? out "; --- Running demo.clj ---\n"))
                                (t/is (str/includes? out "Hello, Narcis!\n"))
                                (t/is (str/ends-with? out "=> 3\n"))))
                (t/testing "the prompt evaluates in the program's world, and is shown as typed"
                           (t/is (= {:action "eval"} (interactions/input! con "(twice 21)" false)))
                           (let [out (settle con 10000)]
                                (t/is (str/ends-with? out "user=> (twice 21)\n=> 42\n"))))
                (t/testing "Run resets: a definition gone from the text is gone from the program"
                           (interactions/run! con "(defn thrice [x] (* 3 x))\n(thrice 2)" "demo.clj" nil)
                           (t/is (str/ends-with? (settle con 10000) "=> 6\n"))
                           (interactions/input! con "(twice 1)" false)
                           (t/is (str/includes? (settle con 10000) "Unable to resolve symbol: twice"))
                           (interactions/run! con "(defn twice [x] (* 2 x))\n(twice 21)" "demo.clj" nil)
                           (settle con 10000)
                           (interactions/input! con "(twice 21)" false)
                           (t/is (str/ends-with? (settle con 10000) "user=> (twice 21)\n=> 42\n")))
                (t/testing "kinds: each entry says what it is"
                           (let [kinds (set (map first (:entries (interactions/read-from con 0))))]
                                (t/is (every? kinds ["system" "out" "result" "prompt" "input"]))))
                (t/testing "an open bracket keeps the input going; blank input does nothing"
                           (t/is (= {:action "newline"} (interactions/input! con "(map inc" false)))
                           (t/is (= {:action "newline"} (interactions/input! con "(+ 1 2)" true)) "Shift+Return")
                           (t/is (= {:action "noop"} (interactions/input! con "   " false))))
                (t/testing "an error reads as an error and leaves the console ready"
                           (interactions/input! con "(/ 1 0)" false)
                           (let [out (settle con 10000)]
                                (t/is (str/includes? out "Error ["))
                                (t/is (= "idle" (interactions/status con)))))
                (t/testing "a bracket problem is said before anything runs"
                           (interactions/run! con "(println \"never\")\n(defn broken [x" "broken.clj" nil)
                           (let [out (settle con 10000)]
                                (t/is (str/includes? out "is never closed"))
                                (t/is (not (str/includes? out "never\n")))))
                (t/testing "Stop ends a loop that never blocks; the console evaluates again after"
                           (interactions/input! con "(loop [] (recur))" false)
                           (Thread/sleep 300)
                           (t/is (= "running" (interactions/status con)))
                           (t/is (= {:action "busy"} (interactions/input! con "(+ 1 1)" false)))
                           (t/is (true? (interactions/stop! con)))
                           (let [out (settle con 10000)]
                                (t/is (str/ends-with? out "; interrupted\n"))
                                (t/is (= "idle" (interactions/status con))))
                           (interactions/input! con "(+ 40 2)" false)
                           (t/is (str/ends-with? (settle con 10000) "=> 42\n")))
                (t/testing "the prompt's history"
                           (t/is (= "(+ 40 2)" (interactions/history! con :prev "")))
                           (t/is (= "(loop [] (recur))" (interactions/history! con :prev "")))
                           (t/is (= "(+ 40 2)" (interactions/history! con :next ""))))
                (t/testing "the transcript is in the store: another console over it reads the same"
                           (let [here (:entries (interactions/read-from con 0))
                                 con2 (interactions/open st)]
                                (t/is (= here (:entries (interactions/read-from con2 0))))))
                (t/testing "positions do not move when the transcript is cleared"
                           (let [end (:next (interactions/read-from con 0))]
                                (interactions/clear! con)
                                (let [r (interactions/read-from con 0)]
                                     (t/is (= [] (:entries r)))
                                     (t/is (= end (:start r) (:from r) (:next r))))
                                (interactions/input! con "(str \"a\" \"b\")" false)
                                (settle con 10000)
                                (let [r (interactions/read-from con end)]
                                     (t/is (= end (:from r)))
                                     (t/is (str/ends-with? (apply str (map second (:entries r))) "=> \"ab\"\n")))))))

(t/deftest menus-are-jonquils-narrowed
           (let [menus (narcis.menus/menus (slurp (clojure.java.io/resource "narcis/menus.edn")))
                 labels (mapv :label menus)
                 file (first menus)]
                (t/is (= ["File" "Edit" "View" "Search" "Run" "Help"] labels))
                (t/is (= [["New Tab" "Open…"] ["Save" "Save As…"] ["Close Tab"]]
                         (mapv (fn [sec] (mapv :label sec)) (:sections file))))
                (t/testing "every command named is one Narcis runs; submenus survive"
                           (let [items (fn items [x] (if (:sections x) (mapcat items (apply concat (:sections x))) [x]))
                                 all (mapcat items menus)]
                                (t/is (every? (fn [i] (contains? narcis.menus/supported (keyword (:command i)))) all))
                                (t/is (some #(= "paredit/slurp-forward" (:command %)) all))
                                (t/is (some #(= "run/run" (:command %)) all))))))

(defn -main [& _]
      (let [{:keys [fail error] :as m} (t/run-tests 'narcis.features-test)]
           (println "TEST-SUMMARY" m)
           (System/exit (if (zero? (+ fail error)) 0 1))))
