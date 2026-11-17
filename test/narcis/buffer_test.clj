(ns narcis.buffer-test
  "Buffer gates, run by scripts/buffer-gate.sh in separate processes:
     walk  — in-process: N edits, undo to each prior version with text
             equality at each step, redo likewise back to the tip.
     write — open two buffers, edit, set cursor/selection, undo once;
             state is persisted in the store.
     read  — reopen the store by name in a NEW process: editor state
             must be identical (texts, tabs, active, cursor, selection,
             undo/redo depths), and undo/redo must keep walking the
             persistent roots across the restart."
  (:require [narcis.store :as store]
            [narcis.buffer :as buf]))

(def path-a "doc-a.clj")
(def path-b "doc-b.clj")
(def a-v0 "(defn f [x]\n  (+ x 1))\n")
(def a-v1 "(defn f [x]\n  (+ x 2))\n")   ; one edit from a-v0
(def a-v2 "(defn f [x]\n  (+ x 2))\n;; note\n") ; second edit
(def b-v0 "(ns b)\n")

(defn- check [label expected actual]
  (when (not= expected actual)
    (throw (ex-info (str "BUFFER-TEST MISMATCH " label)
                    {:expected expected :actual actual})))
  (println "BUFFER-CHECK" label "OK"))

(defn walk! []
  (let [st (store/open-store {:dir (str (or (System/getenv "TMPDIR") (System/getProperty "java.io.tmpdir")) "/narcis-walk-" (System/nanoTime)) :name store/store-name})
        ed (buf/open-editor st)]
    (buf/open-buffer! ed path-a a-v0)
    (check "walk v0" a-v0 (buf/buffer-text ed path-a))
    ;; edit 1: change 1 -> 2 (offset of "1" in "(+ x 1)")
    (buf/delete-range! ed path-a 19 20)
    (buf/insert-text! ed path-a 19 "2")
    ;; NOTE: the two rope ops above are two undo steps; rebuild cleanly:
    (check "walk after edits" a-v1 (buf/buffer-text ed path-a))
    (buf/insert-text! ed path-a (:chars (buf/snapshot ed path-a)) ";; note\n")
    (check "walk v2" a-v2 (buf/buffer-text ed path-a))
    (check "walk undo-depth" 3 (buf/undo-depth ed path-a))
    ;; undo step by step: v2 -> (append undone) -> a-v1 -> mid-edit -> a-v0
    (buf/undo! ed path-a) (check "walk undo1" a-v1 (buf/buffer-text ed path-a))
    (buf/undo! ed path-a) (check "walk undo2" "(defn f [x]\n  (+ x ))\n" (buf/buffer-text ed path-a))
    (buf/undo! ed path-a) (check "walk undo3" a-v0 (buf/buffer-text ed path-a))
    (check "walk undo-at-bottom" false (buf/undo! ed path-a))
    (check "walk redo-depth" 3 (buf/redo-depth ed path-a))
    (buf/redo! ed path-a) (buf/redo! ed path-a)
    (check "walk redo2" a-v1 (buf/buffer-text ed path-a))
    (buf/redo! ed path-a) (check "walk redo3" a-v2 (buf/buffer-text ed path-a))
    (check "walk redo-at-tip" false (buf/redo! ed path-a))
    (store/close-store st)
    (println "BUFFER-TEST walk PASS")))

(defn write! [dir]
  (let [st (store/open-store {:dir dir :name store/store-name})]
    (println "STORE impl:" (:impl st))
    (let [ed (buf/open-editor st)]
      (buf/open-buffer! ed path-a a-v0)
      (buf/open-buffer! ed path-b b-v0)
      (buf/activate! ed path-a)
      (buf/delete-range! ed path-a 19 20)
      (buf/insert-text! ed path-a 19 "2")
      (buf/insert-text! ed path-a (:chars (buf/snapshot ed path-a)) ";; note\n")
      (buf/set-cursor! ed path-a 14)
      (buf/set-selection! ed path-a [17 20])
      (buf/undo! ed path-a) ;; drop the append; undo history keeps the rest
      (check "write text-a" a-v1 (buf/buffer-text ed path-a))
      (check "write undo-depth" 2 (buf/undo-depth ed path-a))
      (check "write redo-depth" 1 (buf/redo-depth ed path-a))
      (store/close-store st)
      (println "BUFFER-TEST write PASS"))))

(defn read! [dir]
  (let [st (store/open-store {:dir dir :name store/store-name})]
    (println "STORE impl:" (:impl st))
    (let [ed (buf/open-editor st)]
      (check "read tabs" [path-a path-b] (buf/tabs ed))
      (check "read active" path-a (buf/active ed))
      (check "read text-a" a-v1 (buf/buffer-text ed path-a))
      (check "read text-b" b-v0 (buf/buffer-text ed path-b))
      (check "read cursor" 14 (buf/cursor ed path-a))
      (check "read selection" [17 20] (buf/selection ed path-a))
      (check "read undo-depth" 2 (buf/undo-depth ed path-a))
      (check "read redo-depth" 1 (buf/redo-depth ed path-a))
      ;; undo/redo keep walking roots across the restart
      (buf/redo! ed path-a) (check "read redo-after-restart" a-v2 (buf/buffer-text ed path-a))
      (buf/undo! ed path-a) (check "read undo1" a-v1 (buf/buffer-text ed path-a))
      (buf/undo! ed path-a) (check "read undo2" "(defn f [x]\n  (+ x ))\n" (buf/buffer-text ed path-a))
      (buf/undo! ed path-a) (check "read undo-to-v0" a-v0 (buf/buffer-text ed path-a))
      (store/close-store st)
      (println "BUFFER-TEST read PASS"))))

(defn -main [phase & [dir]]
  (case phase
    "walk" (walk!)
    "write" (write! dir)
    "read" (read! dir)))
