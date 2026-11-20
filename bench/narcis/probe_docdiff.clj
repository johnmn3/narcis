(ns narcis.probe-docdiff
  "Compare the documents two probe-parload stores finished
   with: counts, then the text of probe lines around the
   segment seams and the tail. Run: scripts/jolt-run.sh
   narcis.probe-docdiff <store-dir-a> <store-dir-b> <path>"
  (:require [narcis.store :as store]
            [narcis.buffer :as buffer]
            [narcis.text :as text]))

(def ^:private probe-lines
  [0 173406 173407 173408 346814 346815 346816 520221 520222 520223
   693628 693629 693630])

(defn- line-text [doc ln]
  (let [a (text/line-start-offset doc ln)
        b (if (< (inc ln) (text/lines doc))
            (text/line-start-offset doc (inc ln))
            (text/chars doc))]
    (text/slice-string doc a b)))

(defn- snapshot [ed path]
  (buffer/read ed path
               (fn [s]
                 (let [doc (:root s)]
                   {:chars (text/chars doc)
                    :lines (text/lines doc)
                    :texts (into {}
                                 (keep (fn [ln]
                                         (when (< ln (text/lines doc))
                                           [ln (line-text doc ln)])))
                                 probe-lines)}))))

(defn -main [& [dir-a dir-b path]]
  (let [ed-a (buffer/open-editor (store/open-store {:dir dir-a :name store/store-name}))
        ed-b (buffer/open-editor (store/open-store {:dir dir-b :name store/store-name}))
        a (snapshot ed-a path)
        b (snapshot ed-b path)]
    (println "a chars" (:chars a) "lines" (:lines a))
    (println "b chars" (:chars b) "lines" (:lines b))
    (doseq [ln probe-lines]
      (let [ta (get (:texts a) ln)
            tb (get (:texts b) ln)]
        (when (or ta tb)
          (println "line" ln (if (= ta tb) "SAME" "DIFF")
                   (pr-str (subs (or ta "") 0 (min 30 (count (or ta "")))))
                   (pr-str (subs (or tb "") 0 (min 30 (count (or tb "")))))))))
    (println "PROBE-DOCDIFF DONE")))
