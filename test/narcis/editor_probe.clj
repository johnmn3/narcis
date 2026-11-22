(ns narcis.editor-probe
    "Editor-gate probe: open the store + editor in THIS process and
   print the fixture buffer's facts as PROBE lines — the gate runs it
   after killing the server, proving the session's edits and undo
   history live in the Eve disk atom, not in the server's memory.
   Usage: -m narcis.editor-probe <store-dir> <path>"
    (:require [narcis.buffer :as buffer]
              [narcis.store :as store]
              [narcis.text :as text]))

(defn -main [dir path]
      (let [st (store/open-store {:dir dir :name store/store-name})
            ed (buffer/open-editor st)]
           (println "PROBE impl:" (:impl st))
           (println "PROBE lines:" (:lines (buffer/snapshot ed path)))
           (println "PROBE line0:" (buffer/read ed path (fn [v] (text/line-at (:root v) 0))))
           (println "PROBE undo:" (buffer/undo-depth ed path))
           (println "PROBE redo:" (buffer/redo-depth ed path))
           (println "PROBE cursor:" (buffer/cursor ed path))
           (println "PROBE rev:" (buffer/rev ed))
           (store/close-store st)))
