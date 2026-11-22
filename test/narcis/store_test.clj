(ns narcis.store-test
    "Store gate: put values as versions of a named buffer, close, REOPEN
   BY NAME in this (second) process, and verify: the current version
   reads the new text, version 0 still reads the old text, and the
   count holds across the reopen. Run by scripts/store-gate.sh in two
   processes: phase 'write' then phase 'read'. (A buffer's document
   lives in the app atom's :buffers slot — narcis.buffer, and the
   buffer gate walks its history across a restart; these versions
   are the store's plain values.)"
    (:require [narcis.store :as store]))

(def v1-text "alpha\nbeta\ngamma\n")
(def v2-text "alpha\nINSERTED\nbeta\ngamma\n")

(defn -main [phase dir]
      (let [st (store/open-store {:dir dir :name store/store-name})]
           (println "STORE impl:" (:impl st))
           (when (:eve-error st) (println "STORE eve-error:" (:eve-error st)))
           (case phase
                 "write"
                 (do (store/put-buffer! st "doc" v1-text)
                     (store/put-buffer! st "doc" v2-text)
                     (println "STORE-WRITE versions:" (store/version-count st "doc")))
                 "read"
                 (let [cur (store/current-buffer st "doc")
                       old (store/buffer-version st "doc" 0)]
                      (assert (= v2-text cur) "current text mismatch")
                      (assert (= v1-text old) "version-0 text mismatch")
                      (assert (= 2 (store/version-count st "doc")) "version count mismatch")
                      (println "STORE-READ reopen-by-name OK; current + version-0 texts verified")))
           (store/close-store st)
           (println "STORE-TEST" phase "PASS")))
