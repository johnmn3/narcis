(ns narcis.store-test
  "Store gate: put rope roots as versions, close, REOPEN BY NAME in this
   (second) process, and verify: current root reads the new text, the
   version-0 root still reads the old text, and data equality holds
   across the reopen. Run by scripts/store-gate.sh in two processes:
   phase 'write' then phase 'read'."
  (:require [narcis.store :as store]
            [narcis.rope :as r]))

(def v1-text "alpha\nbeta\ngamma\n")
(def v2-text "alpha\nINSERTED\nbeta\ngamma\n")

(defn -main [phase dir]
  (let [st (store/open-store {:dir dir :name "narcis/buffers"})]
    (println "STORE impl:" (:impl st))
    (when (:eve-error st) (println "STORE eve-error:" (:eve-error st)))
    (case phase
      "write"
      (do (store/put-buffer! st "doc" (r/from-string v1-text))
          (let [root1 (store/current-buffer st "doc")
                root2 (r/insert root1 6 "INSERTED\n")]
            (store/put-buffer! st "doc" root2))
          (println "STORE-WRITE versions:" (store/version-count st "doc")))
      "read"
      (let [cur (store/current-buffer st "doc")
            old (store/buffer-version st "doc" 0)]
        (assert (= v2-text (r/to-string cur)) "current root text mismatch")
        (assert (= v1-text (r/to-string old)) "version-0 root text mismatch")
        (assert (= 2 (store/version-count st "doc")) "version count mismatch")
        (println "STORE-READ reopen-by-name OK; current + version-0 texts verified")))
    (store/close-store st)
    (println "STORE-TEST" phase "PASS")))
