(ns narcis.probe-skeleton
  "The skeleton machinery, proven against the document itself: a
   scan's counts are the ingested document's counts; file-window's
   lines are the document's lines, at the document's offsets;
   file-line-of agrees with the document's line-of; the file finds
   land where a whole-text search lands. Files mix \\n and \\r\\n
   endings and end both ways (with a break, and without).
   Run: jolt-run.sh narcis.probe-skeleton"
  (:require [narcis.text :as text]
            [narcis.test-util :as tu]
            [clojure.string :as str]))

(def ^:private failures (atom 0))

(defn- check [label expected actual]
  (let [ok (= expected actual)]
    (when-not ok (swap! failures inc))
    (println (if ok "OK  " "FAIL") label
             (when-not ok
               (str " expected=" (pr-str expected) " actual=" (pr-str actual))))
    ok))

(defn- build-file!
  "2500 numbered lines; lines 100..199 end \\r\\n; a token ends line
   1500; the file ends with or without a final break."
  [path trailing-break?]
  (let [sb (StringBuilder.)]
    (dotimes [i 2500]
      (.append sb (str "line " i " the quick brown fox"))
      (when (= i 1500) (.append sb " zqxmid"))
      (.append sb (if (<= 100 i 199) "\r\n" "\n")))
    (when-not trailing-break?
      (.setLength sb (dec (.length sb))))
    (spit path (str sb))))

(defn- wait-scan [scan]
  (loop [n 0]
    (when (and (not (:done? @scan)) (< n 200))
      (Thread/sleep 50)
      (recur (inc n)))))

(defn- doc-line-text [doc ln] (text/line-at doc ln))

(defn- window-checks [path scan doc label start n]
  (let [w (text/file-window path scan start n)
        lns (vec (range start (+ start n)))]
    (check (str label "-texts")
           (mapv (fn [ln] (doc-line-text doc ln)) lns)
           (mapv :text (:lines w)))
    (check (str label "-starts")
           (mapv (fn [ln] (text/line-start-offset doc ln)) lns)
           (mapv :start (:lines w)))
    w))

(defn- checks-for [path scan]
  (let [norm (text/normalize-breaks (slurp path))]
    (tu/with-heap-domain
      (fn []
        (let [doc (:doc (text/ingest-file path))
              total (text/lines doc)
              tok (str/index-of norm "zqxmid")
              w1 (window-checks path scan doc "window" 2000 5)
              _ (window-checks path scan doc "crlf-window" 150 3)
              w3 (text/file-window path scan (- total 2) 5)
              tail-lns [(- total 2) (dec total)]]
          (check "scan-done" true (:done? @scan))
          (check "scan-lines" (text/lines doc) (:lines @scan))
          (check "scan-chars" (text/chars doc) (:chars @scan))
          (check "window-stream-span"
                 (+ (:stream-start w1)
                    (reduce + (map (fn [l] (+ (count (:text l)) 1)) (:lines w1))))
                 (:stream-end w1))
          (check "tail-window-ns" tail-lns (mapv :n (:lines w3)))
          (check "tail-window-texts"
                 (mapv (fn [ln] (doc-line-text doc ln)) tail-lns)
                 (mapv :text (:lines w3)))
          (check "line-of-mid"
                 (text/offset->line doc (text/line-start-offset doc 1500))
                 (text/file-line-of path scan (text/line-start-offset doc 1500)))
          (check "line-of-token"
                 (text/offset->line doc tok)
                 (text/file-line-of path scan tok))
          (check "line-of-eof"
                 (dec (text/lines doc))
                 (text/file-line-of path scan (text/chars doc)))
          ;; the same answers with no scan at all: the walk from
          ;; the file's start must cross the \r\n stretch and still
          ;; count every break (an indexed anchor past the stretch
          ;; used to mask a walk that turned no line on a pair)
          (check "line-of-mid-noscan"
                 (text/offset->line doc (text/line-start-offset doc 1500))
                 (text/file-line-of path nil (text/line-start-offset doc 1500)))
          (check "line-of-eof-noscan"
                 (dec (text/lines doc))
                 (text/file-line-of path nil (text/chars doc)))
          (check "find-forward" tok (text/file-find-forward path "zqxmid" 0))
          (check "find-forward-from-past" nil
                 (text/file-find-forward path "zqxmid" (inc tok)))
          (check "find-backward" tok (text/file-find-backward path "zqxmid" nil))
          (check "find-backward-before" nil
                 (text/file-find-backward path "zqxmid" tok)))))))

(defn- run [path trailing-break?]
  (build-file! path trailing-break?)
  (let [scan (text/scan-file! path)]
    (wait-scan scan)
    (checks-for path scan)))

(defn -main [& _]
  (let [dir (str (System/getProperty "java.io.tmpdir") "/probe-skeleton")]
    (.mkdirs (java.io.File. dir))
    (run (str dir "/break.txt") true)
    (run (str dir "/nobreak.txt") false)
    (println (if (zero? @failures) "PROBE-SKELETON PASS" "PROBE-SKELETON FAIL"))
    (System/exit (if (zero? @failures) 0 1))))
