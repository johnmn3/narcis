(ns narcis.probe-window
  (:require [narcis.store :as store]
            [narcis.buffer :as buffer]
            [narcis.launcher :as launcher] [narcis.text :as text]))

(defn -main [& _]
  (let [dir (str (System/getProperty "java.io.tmpdir") "/probe-window-" (System/nanoTime))
        path (str dir "/demo.clj")]
    (.mkdirs (java.io.File. dir))
    (spit path "(defn twice [x]\n  (* 2 x))\n")
    (let [st (store/open-store {:dir dir :name store/store-name})
          ed (buffer/open-editor st)]
      (buffer/open-file! ed path)
      (println "doc-lines:" (buffer/read ed path (fn [s] (text/lines (:root s)))))
      (println "partial?" (buffer/partial? ed path))
      (try
        (println "payload:" (@#'launcher/window-payload ed path 0 10))
        (catch Throwable t
          (println "THREW:" (ex-message t))
          (doseq [el (take 8 (.getStackTrace t))] (println "  " el))))
      (store/close-store st))))
