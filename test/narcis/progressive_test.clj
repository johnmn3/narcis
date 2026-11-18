(ns narcis.progressive-test
  "Progressive file open: the head becomes the buffer at once, the
   tail arrives on a background load, edits and saves are refused
   while it does, and an interrupted load resumes on reopen."
  (:require [clojure.test :as t]
            [clojure.java.io :as io]
            [narcis.buffer :as buffer]
            [narcis.ops :as ops]
            [narcis.store :as store]
            [narcis.text :as text]))

(defn- fresh-editor []
  (let [dir (str (or (System/getenv "TMPDIR") (System/getProperty "java.io.tmpdir"))
                 "/narcis-prog-" (System/nanoTime))
        st (store/open-store {:dir dir :name store/store-name})]
    (buffer/open-editor st)))

(defn- temp-file [content]
  (let [f (io/file (str (or (System/getenv "TMPDIR") (System/getProperty "java.io.tmpdir"))
                        "/narcis-prog-file-" (System/nanoTime) ".txt"))]
    (spit f content)
    (.getCanonicalPath f)))

(defn- numbered [lines]
  (apply str (map (fn [i] (str "line-" i " lorem ipsum dolor sit amet\n")) (range lines))))

(defn- await-load [ed path]
  (loop [n 0]
    (when (and (buffer/loading? ed path) (< n 1200))
      (Thread/sleep 100)
      (recur (inc n))))
  (not (buffer/loading? ed path)))

(t/deftest progressive-open-completes
  (let [ed (fresh-editor)
        content (numbered 40000) ;; ~1.4 MB
        path (temp-file content)]
    (buffer/open-file! ed path {:head 2048})
    (t/is (buffer/open? ed path))
    (t/is (await-load ed path) "the tail finishes arriving")
    (t/is (= {} (buffer/loading-map ed)))
    (t/is (= content (buffer/buffer-text ed path))
          "the loaded document is the file, whole")
    (t/is (= 0 (buffer/undo-depth ed path))
          "the load leaves no undo steps")))

(t/deftest crlf-seams-match-the-whole-text
  (let [ed (fresh-editor)
        content (str (apply str (repeat 3000 "abc\r\n")) "tail-without-ending")
        path (temp-file content)]
    (buffer/open-file! ed path {:head 100})
    (t/is (await-load ed path))
    (t/is (= (text/normalize-breaks content) (buffer/buffer-text ed path))
          "breaks straddling the head and chunk seams land as breaks, once each")))

(t/deftest loading-gate-refuses-edits-and-saves
  (let [ed (fresh-editor)
        path "gated.clj"]
    (buffer/open-buffer! ed path "(defn f [x] x)\n")
    ;; a load in flight is plain data in the app atom; claim one
    (swap! (:atom (:store ed)) assoc-in [:loading path]
           {:id -1 :loaded-chars 5 :total-bytes 10})
    (t/is (buffer/loading? ed path))
    (let [res (ops/apply-op ed {:op :type-char :path path :ch "y"})]
      (t/is (not (:ok res)))
      (t/is (re-find #"still loading" (str (:error res)))))
    (let [res (ops/apply-op ed {:op :save :path path})]
      (t/is (not (:ok res)) "a save would write a partial document over the file"))
    (t/is (:ok (ops/apply-op ed {:op :set-caret :path path :offset 3}))
          "caret moves are not edits")
    (t/is (:ok (ops/apply-op ed {:op :find-next :path path :query "defn"}))
          "find searches what has arrived")
    (swap! (:atom (:store ed)) update :loading dissoc path)
    (t/is (:ok (ops/apply-op ed {:op :type-char :path path :ch "y"}))
          "and edits answer again once the load is done")))

(t/deftest interrupted-load-resumes-on-reopen
  (let [ed (fresh-editor)
        content (numbered 60000) ;; ~2.1 MB
        path (temp-file content)
        head-text (subs content 0 50000)]
    ;; the state a crashed process leaves: a buffer holding the
    ;; file's head and a load entry saying how far it got
    (buffer/open-buffer! ed path head-text)
    (swap! (:atom (:store ed)) assoc-in [:loading path]
           {:id -7 :loaded-chars 50000 :total-bytes (count content)})
    (buffer/open-file! ed path)
    (t/is (await-load ed path) "the resumed load finishes")
    (t/is (= content (buffer/buffer-text ed path))
          "and the document is the whole file, not the head twice")))
