(ns narcis.app-test
  "App-surface op gate (S3 follow-on): the ops the menus, palette and
   find bar drive — theme selection persisted in the editor atom,
   new tab, save-as, find next/previous — driven headlessly against a
   real editor on a real store, the same narcis.ops messages the
   browser sends."
  (:require [clojure.test :as t]
            [narcis.buffer :as buffer]
            [narcis.ops :as ops]
            [narcis.store :as store])
  (:import [java.io File]))

(defn- fresh-store []
  (let [dir (str (or (System/getenv "TMPDIR") (System/getProperty "java.io.tmpdir"))
                 "/narcis-app-" (System/nanoTime))]
    (store/open-store {:dir dir :name store/store-name})))

(defn- text [ed path] (buffer/buffer-text ed path))

(t/deftest theme-selection-persists
  (let [st (fresh-store)
        ed (buffer/open-editor st)]
    (t/is (nil? (buffer/theme-choice ed)))
    (let [res (ops/apply-op ed {:op :theme/use :name "dracula"})]
      (t/is (:ok res))
      (t/is (= "dracula" (:theme res)))
      (t/is (= "dracula" (:resolved res))))
    (t/is (= :dracula (buffer/theme-choice ed)))
    ;; :auto resolves through the client's system-dark report
    (let [res (ops/apply-op ed {:op :theme/use :name "auto" :system-dark true})]
      (t/is (= "auto" (:theme res)))
      (t/is (= "dark" (:resolved res))))
    (let [res (ops/apply-op ed {:op :theme/use :name "auto" :system-dark false})]
      (t/is (= "light" (:resolved res))))
    ;; an unknown name is an error and changes nothing
    (let [res (ops/apply-op ed {:op :theme/use :name "nope"})]
      (t/is (not (:ok res))))
    (t/is (= :auto (buffer/theme-choice ed)))
    ;; the choice is in the store: a fresh editor over it reads it back
    (let [ed2 (buffer/open-editor st)]
      (t/is (= :auto (buffer/theme-choice ed2))))))

(t/deftest new-and-save-as
  (let [ed (buffer/open-editor (fresh-store))
        r1 (ops/apply-op ed {:op :new})]
    (t/is (:ok r1))
    (t/is (= "untitled" (:path r1)))
    (t/is (= "untitled" (buffer/active ed)))
    (ops/apply-op ed {:op :insert-text :path "untitled" :text "(defn a [] 1)"})
    (let [r2 (ops/apply-op ed {:op :new})]
      (t/is (= "untitled-2" (:path r2))))
    ;; save-as: file written, tab re-pointed, old tab closed
    (let [target (str (or (System/getenv "TMPDIR") (System/getProperty "java.io.tmpdir"))
                      "/narcis-save-as-" (System/nanoTime) ".clj")
          res (ops/apply-op ed {:op :save-as :path "untitled" :new-path target})]
      (t/is (:ok res) (pr-str res))
      (t/is (= target (:path res)))
      (t/is (= "(defn a [] 1)" (slurp target)))
      (t/is (nil? (buffer/buffer ed "untitled")))
      (t/is (= "(defn a [] 1)" (text ed target)))
      (t/is (= target (buffer/active ed)))
      ;; save-as onto an open path and onto itself are errors
      (t/is (not (:ok (ops/apply-op ed {:op :save-as :path target :new-path "untitled-2"}))))
      (t/is (not (:ok (ops/apply-op ed {:op :save-as :path target :new-path target})))))))

(t/deftest find-next-prev
  (let [ed (buffer/open-editor (fresh-store))
        path "find.clj"]
    (buffer/open-buffer! ed path "foo bar foo baz foo")
    ;; caret at 0: next finds the first foo and selects it
    (let [res (ops/apply-op ed {:op :find-next :path path :query "foo"})]
      (t/is (:ok res))
      (t/is (= [0 3] (:match res)))
      (t/is (= [0 3] (buffer/selection ed path)))
      (t/is (= 3 (:caret res))))
    ;; next from there finds the second, then the third, then wraps
    (t/is (= [8 11] (:match (ops/apply-op ed {:op :find-next :path path :query "foo"}))))
    (t/is (= [16 19] (:match (ops/apply-op ed {:op :find-next :path path :query "foo"}))))
    (t/is (= [0 3] (:match (ops/apply-op ed {:op :find-next :path path :query "foo"}))))
    ;; prev from the first match wraps to the last
    (t/is (= [16 19] (:match (ops/apply-op ed {:op :find-prev :path path :query "foo"}))))
    (t/is (= [8 11] (:match (ops/apply-op ed {:op :find-prev :path path :query "foo"}))))
    ;; misses and empty queries are errors, not throws
    (t/is (not (:ok (ops/apply-op ed {:op :find-next :path path :query "quux"}))))
    (t/is (not (:ok (ops/apply-op ed {:op :find-next :path path :query ""}))))))

(defn -main [& _]
  (let [{:keys [fail error] :as m} (t/run-tests 'narcis.app-test)]
    (println "TEST-SUMMARY" m)
    (System/exit (if (zero? (+ fail error)) 0 1))))
