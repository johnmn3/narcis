(ns narcis.progressive-test
  "Big files open as a partial document over a scanned skeleton:
   the head is the buffer at once, a scan counts and indexes the
   whole file behind it, stretches of the unhydrated rest read out
   of the file (a find runs over the file itself) and land in the
   same document as just-in-time extents, and a background
   hydration fills the document in, absorbing the extents as its
   frontier reaches them. An edit on a partial buffer is refused
   as :preparing — it needs the whole document. A hydration left
   unfinished by a dead process resumes on reopen."
  (:require [clojure.test :as t]
            [clojure.java.io :as io]
            [clojure.string :as str]
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

(defn- await-whole [ed path]
  (loop [n 0]
    (when (and (buffer/partial? ed path) (< n 1200))
      (Thread/sleep 100)
      (recur (inc n))))
  (not (buffer/partial? ed path)))

(defn- await-scan [path]
  (loop [n 0]
    (when (and (not (:done? (buffer/skeleton path))) (< n 600))
      (Thread/sleep 50)
      (recur (inc n))))
  (:done? (buffer/skeleton path)))

(t/deftest progressive-open-completes
  (let [ed (fresh-editor)
        content (str (numbered 40000) "zqxend\n") ;; ~1.4 MB
        path (temp-file content)]
    (buffer/open-file! ed path {:head 2048})
    (t/is (buffer/open? ed path))
    (t/is (buffer/partial? ed path) "the head is the buffer, the rest a skeleton")
    (t/is (await-scan path) "the scan finishes")
    (t/is (= 40002 (:lines (buffer/skeleton path))) "the skeleton has the file's height")
    (let [res (ops/apply-op ed {:op :find-next :path path :query "zqxend" :caret 0})]
      (t/is (:ok res) "a find runs over the file itself")
      (t/is (= (str/index-of content "zqxend") (first (:match res)))))
    (t/is (await-whole ed path) "the hydration finishes")
    (t/is (= {} (buffer/loading-map ed)))
    (t/is (= content (buffer/buffer-text ed path))
          "the hydrated document is the file, whole")
    (t/is (= 0 (buffer/undo-depth ed path))
          "the hydration leaves no undo steps")))

(t/deftest crlf-seams-match-the-whole-text
  (let [ed (fresh-editor)
        content (str (apply str (repeat 3000 "abc\r\n")) "tail-without-ending")
        path (temp-file content)]
    (buffer/open-file! ed path {:head 100})
    (t/is (await-whole ed path))
    (t/is (= (text/normalize-breaks content) (buffer/buffer-text ed path))
          "breaks straddling the head and chunk seams land as breaks, once each")))

(t/deftest partial-gate-refuses-edits-and-saves
  (let [ed (fresh-editor)
        content (numbered 40000)
        path (temp-file content)]
    (buffer/open-file! ed path {:head 2048})
    (t/is (buffer/partial? ed path))
    (let [res (ops/apply-op ed {:op :type-char :path path :ch "y"})]
      (t/is (not (:ok res)))
      (t/is (true? (:preparing res)) "an edit needs the whole document"))
    (let [res (ops/apply-op ed {:op :save :path path})]
      (t/is (not (:ok res)) "a save would write a partial document over the file"))
    (t/is (:ok (ops/apply-op ed {:op :set-caret :path path :offset 3}))
          "caret moves are not edits")
    (t/is (await-whole ed path))
    (t/is (:ok (ops/apply-op ed {:op :type-char :path path :ch "y"}))
          "and edits answer again once the buffer is whole")))

(t/deftest extent-lands-and-is-absorbed
  (let [ed (fresh-editor)
        content (numbered 40000)
        path (temp-file content)]
    (buffer/open-file! ed path {:head 2048})
    (t/is (await-scan path))
    ;; read a stretch far past the frontier out of the file and
    ;; land it as an extent, as the window endpoint does
    (let [w (text/file-window path (buffer/scan-of path) 30000 40)]
      (buffer/note-extent! ed path (:lines w) (:stream-start w) (:stream-end w)))
    (t/is (contains? (buffer/extents ed path) 30000)
          "the stretch is an extent in the same atom")
    (t/is (await-whole ed path) "the hydration finishes")
    (t/is (= content (buffer/buffer-text ed path))
          "the document is the file, whole — the extent absorbed, not doubled")
    (t/is (= {} (buffer/extents ed path)) "no extents remain")))

(t/deftest loading-buffer-is-read-only
  ;; the load model: while a file loads its buffer is read-only — its
  ;; view is the file, the rope is built behind it and taken over in
  ;; one switch at the end. A stretch a view pulled in as an extent is
  ;; part of that rope, not an editable island: an edit inside it is
  ;; refused like any other edit during the load. (Replaces
  ;; extent-is-editable-while-partial, which asserted the retired
  ;; edit-in-extent behaviour.)
  (let [ed (fresh-editor)
        content (numbered 40000)
        path (temp-file content)]
    (buffer/open-file! ed path {:head 2048})
    (t/is (await-scan path))
    (doseq [start [30000 35000]]
      (let [w (text/file-window path (buffer/scan-of path) start 40)]
        (buffer/note-extent! ed path (:lines w) (:stream-start w) (:stream-end w))))
    (t/is (buffer/partial? ed path))
    (t/is (contains? (buffer/extents ed path) 30000))
    (let [off (.indexOf content "line-30010")
          res (ops/apply-op ed {:op :insert-text :path path
                                :caret off :text "EDITED "})]
      (t/is (not (:ok res)) "an edit inside an extent is refused while loading")
      (t/is (:preparing res))
      (t/is (str/includes? (:error res) "read-only")))
    (t/is (await-whole ed path) "the hydration finishes")
    (t/is (= content (buffer/buffer-text ed path))
          "the finished document is the file, untouched")
    (t/is (:ok (ops/apply-op ed {:op :insert-text :path path
                                 :caret (.indexOf content "line-30010")
                                 :text "EDITED "}))
          "and the same edit answers once the buffer is whole")))

(t/deftest line-of-counts-in-doc-and-file
  (let [ed (fresh-editor)
        content (numbered 40000)
        path (temp-file content)]
    (buffer/open-file! ed path {:head 2048})
    (t/is (= 0 (buffer/line-of ed path 0)))
    (t/is (= 1 (buffer/line-of ed path (inc (str/index-of content "\n")))))
    (t/is (await-scan path))
    ;; past the frontier the line is counted in the file, by its index
    (t/is (= 39999 (buffer/line-of ed path (str/index-of content "line-39999"))))
    (t/is (await-whole ed path))
    (t/is (= 39999 (buffer/line-of ed path (str/index-of content "line-39999")))
          "and in the document once it is whole")))

(t/deftest interrupted-hydration-resumes-on-reopen
  (let [ed (fresh-editor)
        content (numbered 60000) ;; ~2.1 MB
        path (temp-file content)
        head-text (subs content 0 50000)]
    ;; the state a crashed process leaves: a buffer holding the
    ;; file's head and a partial entry saying how far it got
    (buffer/open-buffer! ed path head-text)
    (swap! (:atom (:store ed)) assoc-in [:loading path]
           {:id -7 :loaded-chars 50000 :total-bytes (count content)})
    (buffer/open-file! ed path)
    (t/is (await-whole ed path) "the resumed hydration finishes")
    (t/is (= content (buffer/buffer-text ed path))
          "and the document is the whole file, not the head twice")))
