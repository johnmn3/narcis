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
    (:require [clojure.java.io :as io]
              [clojure.string :as str]
              [clojure.test :as t]
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
       (clojure.string/join (map (fn [i] (str "line-" i " lorem ipsum dolor sit amet\n")) (range lines))))

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
                (t/is (zero? (buffer/undo-depth ed path))
                      "the hydration leaves no undo steps")))

(t/deftest crlf-seams-match-the-whole-text
           (let [ed (fresh-editor)
                 content (str (clojure.string/join (repeat 3000 "abc\r\n")) "tail-without-ending")
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
    ;; past everything a view has read (buffer A holds only the head
    ;; so far) an edit has nothing to land in
                (let [res (ops/apply-op ed {:op :type-char :path path :ch "y"
                                            :caret (.indexOf content "line-30000")})]
                     (t/is (not (:ok res)))
                     (t/is (true? (:preparing res)) "an edit past every piece of A is refused"))
                (let [res (ops/apply-op ed {:op :save :path path})]
                     (t/is (not (:ok res)) "a save would write a partial document over the file"))
                (t/is (:ok (ops/apply-op ed {:op :set-caret :path path :offset 3}))
                      "caret moves are not edits")
                (t/is (await-whole ed path))
                (t/is (:ok (ops/apply-op ed {:op :type-char :path path :ch "y"}))
                      "and edits answer again once the buffer is whole")))

(t/deftest head-is-editable-while-loading
  ;; the load model: the buffer a reader sees is A — the head and every
  ;; stretch they reach — and it is editable from the first moment; the
  ;; background build (B) never takes A's pieces in early, so an edit
  ;; made before B's frontier passes it survives the passing, and the
  ;; load's one merge puts it in the finished document
           (let [ed (fresh-editor)
                 content (numbered 40000)
                 path (temp-file content)]
                (buffer/open-file! ed path {:head 2048})
                (t/is (buffer/partial? ed path))
                (t/is (:ok (ops/apply-op ed {:op :insert-text :path path :caret 0 :text "EDIT "}))
                      "an edit in the head answers while the file loads")
                (t/is (await-whole ed path) "the load finishes")
                (t/is (= (str "EDIT " content) (buffer/buffer-text ed path))
                      "the finished document is the file with the head's edit")))

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

(t/deftest extent-is-editable-while-partial
           (let [ed (fresh-editor)
                 content (numbered 40000)
                 path (temp-file content)]
                (buffer/open-file! ed path {:head 2048})
                (t/is (await-scan path))
    ;; two stretches land as extents, as the window endpoint
    ;; lands them; the buffer is partial throughout the edits
                (doseq [start [30000 35000]]
                       (let [w (text/file-window path (buffer/scan-of path) start 40)]
                            (buffer/note-extent! ed path (:lines w) (:stream-start w) (:stream-end w))))
                (t/is (buffer/partial? ed path))
                (t/is (contains? (buffer/extents ed path) 30000))
                (t/is (contains? (buffer/extents ed path) 35000))
    ;; what no single extent covers keeps the partial refusal:
    ;; a selection straddling the first extent's end, and a
    ;; caret in the gap between the extents
                (let [e (get (buffer/extents ed path) 30000)
                      end (+ (:start-char e) (:chars e))
                      res (ops/apply-op ed {:op :insert-text :path path
                                            :sel [(dec end) (+ end 3)] :text "X"})]
                     (t/is (:preparing res) "a straddle of an extent's edge is refused"))
                (let [gap (some (fn [ln]
                                    (let [off (.indexOf content (str "line-" ln))]
                                         (when (nil? (buffer/covering-extent ed path off off))
                                               off)))
                                [31000 32000 33000 34000])
                      res (when gap
                                (ops/apply-op ed {:op :insert-text :path path
                                                  :caret gap :text "X"}))]
                     (t/is (some? gap) "a gap between the extents exists to aim at")
                     (t/is (:preparing res) "a caret outside every extent is refused"))
    ;; an edit inside the first extent goes through, and a
    ;; line added there rekeys the later extent in the same swap
                (let [off (.indexOf content "line-30010")]
                     (t/is (:ok (ops/apply-op ed {:op :insert-text :path path
                                                  :caret off :text "EDITED "}))
                           "the covered edit answers ok while partial"))
                (let [e (get (buffer/extents ed path) 30000)
                      caret (+ (:start-char e) (text/line-start-offset (:doc e) 20))]
                     (t/is (:ok (ops/apply-op ed {:op :insert-text :path path
                                                  :caret caret :text "\n"}))
                           "a line split inside the extent answers ok"))
                (t/is (contains? (buffer/extents ed path) 35001)
                      "the later extent is rekeyed by the split's line delta")
                (t/is (not (contains? (buffer/extents ed path) 35000)))
                (t/is (await-whole ed path) "the hydration finishes")
                (let [off1 (.indexOf content "line-30010")
                      off2 (.indexOf content "line-30020")
                      expected (str (subs content 0 off1) "EDITED "
                                    (subs content off1 off2) "\n"
                                    (subs content off2))]
                     (t/is (= expected (buffer/buffer-text ed path))
                           "the finished document is the file with exactly the extents' edits"))))

(t/deftest line-of-counts-in-doc-and-file
           (let [ed (fresh-editor)
                 content (numbered 40000)
                 path (temp-file content)]
                (buffer/open-file! ed path {:head 2048})
                (t/is (zero? (buffer/line-of ed path 0)))
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
