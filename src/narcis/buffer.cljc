(ns narcis.buffer
  "Editor/buffer state in Eve atoms — the editing model itself:
   buffer state lives in Eve atoms, an edit is a swap! on the Eve
   atom, and the value being swapped is eve.deftype data.

   Per buffer, one named Eve atom in the store's disk domain holds a
   BufferState eve.deftype at its root:

     BufferState{:root   <eve.text document>   ; the buffer's text
                 :cursor <char offset>
                 :sel    [<start> <end>] or nil
                 :undo   <History: the prior documents, newest first>
                 :redo   <History: the undone documents, newest first>
                 :nundo  <how many> :nredo <how many>
                 :eol    <the file's line ending>
                 :rev    <swaps on this buffer>
                 :trev   <changes of :root among them>
                 :gen    <the editor revision it was opened at>}

   A second named atom holds the editor's plain data:
   {:tabs [<path> ...] :active <path> :theme <choice> :rev n}.

   Every mutation is ONE swap! on the relevant atom, and the text op
   runs INSIDE the swap fn (edit!) — so the new nodes are built in the
   atom's own domain, path-copying the document's tree in Eve storage
   (Eve's swap! binds the atom's domain while f runs). There is no
   mirror state and no persist step: the swap IS the persistence, so
   state survives process restart by construction and undo — a root
   swap between the :undo/:redo lists, which cost nothing to keep
   because documents share what they did not change — works across a
   restart exactly as in-process.

   BufferState sits at the atom's root because an Eve type that holds
   Eve memory by reference (a document's nodes) lives at an atom's
   root or in another Eve type's field, never inside a plain
   collection value (Eve @84bfc7c8 stores those by value and refuses
   the reference).

   The editor requires the store's Eve arm: open-editor says so when
   handed a file store."
  (:require [clojure.string :as str]
            [eve.deftype :as edt]
            [narcis.text :as text]
            [narcis.store :as store]))

;; ---------------------------------------------------------------------------
;; the per-buffer state type

(defprotocol IBufferState
  (-root [b] "The buffer's current document.")
  (-cursor [b] "Caret char offset.")
  (-sel [b] "Selection [start end] or nil.")
  (-undo [b] "History of prior documents (newest first), or nil.")
  (-redo [b] "History of undone documents (newest first), or nil.")
  (-nundo [b] "How many documents :undo holds.")
  (-nredo [b] "How many documents :redo holds.")
  (-eol [b] "The file's line ending, written back on save.")
  (-rev [b] "How many swaps this buffer has seen.")
  (-trev [b] "How many of them changed the document.")
  (-gen [b] "The editor revision this buffer was opened at."))

(edt/deftype BufferState [root cursor sel undo redo eol rev trev gen nundo nredo]
  IBufferState
  (-root [_] root)
  (-cursor [_] cursor)
  (-sel [_] sel)
  (-undo [_] undo)
  (-redo [_] redo)
  (-nundo [_] nundo)
  (-nredo [_] nredo)
  (-eol [_] eol)
  (-rev [_] rev)
  (-trev [_] trev)
  (-gen [_] gen))

;; ---------------------------------------------------------------------------
;; history: a list of documents, the newest at its head. A step back or
;; forward makes or drops ONE cell — nothing is copied, however long the
;; history is (an array of them would be copied, and every document it
;; names counted again, on every keystroke).

(defprotocol IHistory
  (-doc [h] "The document at this step.")
  (-older [h] "The steps before it, or nil."))

(edt/deftype History [doc older]
  IHistory
  (-doc [_] doc)
  (-older [_] older))

(defn- history-docs
  "The documents of history h, oldest first (a vector of them, in place)."
  [h]
  (loop [h h acc ()]
    (if (nil? h)
      (vec acc)
      (recur (-older h) (conj acc (-doc h))))))

;; ---------------------------------------------------------------------------
;; the editor handle

(def editor-name "editor")

(defn- sanitize [s] (str/replace s #"[^a-zA-Z0-9._-]" "_"))

(defn open-editor
  "Open the editor state held in store (created empty on first use).
   The store must be on its Eve arm — see the namespace docstring."
  [store]
  (when (not= :eve (:impl store))
    (throw (ex-info (str "narcis.buffer requires the Eve atom store; "
                         "this store is on the file arm (host cannot run Eve — W2)")
                    {:impl (:impl store)})))
  {:store store
   :editor (store/open-atom store editor-name {:tabs [] :active nil :rev 0 :theme nil})
   :buffers (atom {})})

(defn- buffer-handle [ed path]
  (or (get @(:buffers ed) path)
      (let [h (store/open-atom (:store ed)
                               (str "buffer-" (sanitize path) "-" (hash path))
                               nil)]
        (swap! (:buffers ed) assoc path h)
        h)))

(defn- editor-value [ed] (store/atom-deref (:editor ed)))

(defn- bump-editor [s] (update s :rev (fnil inc 0)))

(declare snapshot)

(defn rev
  "The sync token clients see: the editor atom's revision, plus the
   buffer's own when a path is given. Every swap moves one of them."
  ([ed] (get (editor-value ed) :rev 0))
  ([ed path]
   (+ (rev ed) (or (:rev (snapshot ed path)) 0))))

;; ---------------------------------------------------------------------------
;; reads
;;
;; A buffer is read IN PLACE, inside a read region (narcis.store/
;; atom-read, Eve's $): a plain deref of the buffer's atom would hand
;; back a copy of its whole value — the whole document — for every
;; question asked of it. read is the one door: its f sees the document
;; where it is, touches what it needs, and answers plain data.

(defn- view-of
  "A buffer's state as read's f sees it."
  [path b]
  {:root (-root b)
   :cursor (-cursor b)
   :sel (-sel b)
   :undo (-nundo b)
   :redo (-nredo b)
   :eol (-eol b)
   :rev (-rev b)
   ;; names this text and no other: the buffer's opening and how many
   ;; times its document has changed since (what an analysis of the
   ;; text may be remembered under)
   :text-key [path (-gen b) (-trev b)]
   :undo-docs (-undo b)
   :redo-docs (-redo b)})

(defn read
  "(f view) with path's buffer read in place — view is {:root doc
   :cursor n :sel [a b]|nil :undo depth :redo depth :eol s :rev n
   :text-key k} and :root is the document itself, not a copy, readable
   only while f runs. f answers plain data, which read answers; nil when
   path is not open."
  [ed path f]
  (when path
    (store/atom-read (buffer-handle ed path)
                     (fn [b] (when (some? b) (f (view-of path b)))))))

(defn open?
  "True when path is an open buffer."
  [ed path]
  (true? (read ed path (fn [_] true))))

(defn snapshot
  "One read of path's buffer as plain data: {:cursor n :sel [a b]|nil
   :undo depth :redo depth :eol s :rev n :text-key k :lines n :chars n},
   or nil when it is not open."
  [ed path]
  (read ed path (fn [v] (-> (dissoc v :root :undo-docs :redo-docs)
                            (assoc :lines (text/lines (:root v))
                                   :chars (text/chars (:root v)))))))

(defn buffer
  "path's buffer as plain data (snapshot), or nil."
  [ed path]
  (snapshot ed path))

(defn tabs [ed] (:tabs (editor-value ed)))
(defn active [ed] (:active (editor-value ed)))

(defn state
  "The whole editor state as plain data (see snapshot for each entry)."
  [ed]
  (let [ev (editor-value ed)]
    {:buffers (loop [ps (:tabs ev) acc {}]
                (if-let [path (first ps)]
                  (recur (rest ps)
                         (if-let [b (snapshot ed path)]
                           (assoc acc path b)
                           acc))
                  acc))
     :tabs (:tabs ev)
     :active (:active ev)
     :theme (:theme ev)}))

(defn buffer-text
  "path's whole text (lines joined by \\n). O(chars)."
  [ed path]
  (read ed path (fn [v] (text/to-string (:root v)))))

(defn history-texts
  "The texts of path's history: {:undo [s ...] :redo [s ...]}, oldest
   first. For tests and tools: it reads every kept document whole."
  [ed path]
  (read ed path
        (fn [v]
          (let [texts (fn [h] (mapv text/to-string (history-docs h)))]
            {:undo (texts (:undo-docs v)) :redo (texts (:redo-docs v))}))))

(defn cursor [ed path] (:cursor (snapshot ed path)))
(defn selection [ed path] (:sel (snapshot ed path)))
(defn eol [ed path] (or (:eol (snapshot ed path)) "\n"))
(defn undo-depth [ed path] (or (:undo (snapshot ed path)) 0))
(defn redo-depth [ed path] (or (:redo (snapshot ed path)) 0))

(defn theme-choice
  "The editor's theme choice (a narcis.theme choice keyword, :auto
   included), or nil when none was ever chosen."
  [ed]
  (:theme (editor-value ed)))

(defn set-theme!
  "Store choice as the editor's theme choice: one swap! on the editor
   atom, so the choice is durable in the store and every client reads
   the same one from state."
  [ed choice]
  (store/atom-swap! (:editor ed) (fn [s] (bump-editor (assoc s :theme choice)))))

;; ---------------------------------------------------------------------------
;; mutations — each is one swap! on an Eve atom

(defn- with-state
  "b with the given fields replaced and its revision moved on. :undo and
   :redo are given as [history count]."
  [b {:keys [root cursor sel undo redo] :as m}]
  (->BufferState (if (contains? m :root) root (-root b))
                 (if (contains? m :cursor) cursor (-cursor b))
                 (if (contains? m :sel) sel (-sel b))
                 (if (contains? m :undo) (first undo) (-undo b))
                 (if (contains? m :redo) (first redo) (-redo b))
                 (-eol b)
                 (inc (-rev b))
                 (if (contains? m :root) (inc (-trev b)) (-trev b))
                 (-gen b)
                 (if (contains? m :undo) (second undo) (-nundo b))
                 (if (contains? m :redo) (second redo) (-nredo b))))

(defn- add-tab [s path]
  (bump-editor (-> s
                   (update :tabs (fn [ts] (if (some #(= % path) ts) ts (conj (vec ts) path))))
                   (assoc :active path))))

(defn activate! [ed path]
  (store/atom-swap! (:editor ed) (fn [s] (bump-editor (assoc s :active path)))))

(defn open-buffer!
  "Open path holding content — a string, or a document (copied into
   this buffer's atom) — with line ending eol (\"\\n\" when not given).
   Opening an already-open path just activates it. A new buffer starts
   with an empty undo history: what was opened is version zero."
  ([ed path content] (open-buffer! ed path content "\n"))
  ([ed path content eol]
   (if (open? ed path)
     (activate! ed path)
     (let [gen (rev ed)]
       (store/atom-swap! (buffer-handle ed path)
                           (fn [_] (->BufferState (if (string? content)
                                                    (text/from-string content)
                                                    (text/copy-here content))
                                                  0 nil nil nil eol 0 0 gen 0 0)))
       (store/atom-swap! (:editor ed) (fn [s] (add-tab s path)))))))

(defn open-file!
  "Open a file from disk: its text is read a chunk at a time into a
   document inside the buffer atom's swap (narcis.text/ingest-file —
   the file and its lines are never held whole), so the tree is built
   directly in the atom's domain."
  [ed path]
  (if (open? ed path)
    (activate! ed path)
    (let [gen (rev ed)]
      (store/atom-swap! (buffer-handle ed path)
                        (fn [_] (let [{:keys [doc eol]} (text/ingest-file path)]
                                  (->BufferState doc 0 nil nil nil eol 0 0 gen 0 0))))
      (store/atom-swap! (:editor ed) (fn [s] (add-tab s path))))))

(defn close-buffer! [ed path]
  (store/atom-swap! (buffer-handle ed path) (fn [_] nil))
  (swap! (:buffers ed) dissoc path)
  (store/atom-swap! (:editor ed)
                    (fn [s] (let [tabs (vec (remove #(= % path) (:tabs s)))]
                              (bump-editor
                               (-> s
                                   (assoc :tabs tabs)
                                   (assoc :active (if (= path (:active s))
                                                    (peek tabs)
                                                    (:active s)))))))))

(defn set-caret!
  "Move path's caret to offset with selection sel ([a b] or nil): one
   swap, no undo step."
  [ed path offset sel]
  (store/atom-swap! (buffer-handle ed path)
                    (fn [b] (with-state b {:cursor offset :sel sel}))))

(defn set-cursor! [ed path offset]
  (store/atom-swap! (buffer-handle ed path)
                    (fn [b] (with-state b {:cursor offset}))))

(defn set-selection! [ed path sel]
  (store/atom-swap! (buffer-handle ed path)
                    (fn [b] (with-state b {:sel sel}))))

(defn edit!
  "One undoable edit as ONE swap!: (f doc cursor sel text-key) runs
   inside the swap, in the atom's own domain, and answers {:doc new-doc
   :caret n :sel [a b]|nil} — or nil when it does not apply, and then
   nothing changes (text-key is snapshot's: the name of doc's text).
   The prior document goes onto :undo and :redo is cleared. An answer
   whose :doc is doc itself moves the caret and selection only: no new
   text, no undo step. Answers what f answered, less its :doc, or nil.

   A map f answers may carry :undo? false to change the text without
   an undo step (none of the editor's own ops do)."
  [ed path f]
  (let [out (volatile! nil)]
    (store/atom-swap!
     (buffer-handle ed path)
     (fn [b]
       (let [r (f (-root b) (-cursor b) (-sel b) [path (-gen b) (-trev b)])]
         (vreset! out (when r (assoc (dissoc r :doc) :caret (get r :caret (-cursor b)))))
         (cond
           (nil? r) b
           ;; the same document back: a move of the caret, nothing to undo
           (identical? (:doc r) (-root b))
           (with-state b {:cursor (get r :caret (-cursor b)) :sel (:sel r)})
           :else
           (with-state b {:root (:doc r)
                          :cursor (get r :caret (-cursor b))
                          :sel (:sel r)
                          :undo (if (false? (:undo? r))
                                  [(-undo b) (-nundo b)]
                                  [(->History (-root b) (-undo b)) (inc (-nundo b))])
                          :redo [nil 0]})))))
    @out))

(defn insert-text!
  "Insert s at char offset in path's buffer, as one undoable edit."
  [ed path offset s]
  (edit! ed path (fn [doc _ sel _]
                   {:doc (text/insert doc offset s)
                    :caret (+ offset (count (text/normalize-breaks s)))
                    :sel sel})))

(defn delete-range!
  "Delete chars [start end) in path's buffer, as one undoable edit."
  [ed path start end]
  (edit! ed path (fn [doc _ sel _]
                   {:doc (text/delete doc start end) :caret start :sel sel})))

(defn- step!
  "Move one document between the histories: undo? takes the newest of
   :undo and gives the current one to :redo; else the other way."
  [ed path undo?]
  (let [took (volatile! false)]
    (store/atom-swap!
     (buffer-handle ed path)
     (fn [b]
       (let [from (if undo? (-undo b) (-redo b))
             nfrom (if undo? (-nundo b) (-nredo b))
             to (if undo? (-redo b) (-undo b))
             nto (if undo? (-nredo b) (-nundo b))]
         (vreset! took (some? from))
         (if (nil? from)
           b
           (let [doc (-doc from)
                 kept [(-older from) (dec nfrom)]
                 given [(->History (-root b) to) (inc nto)]]
             (with-state b {:root doc
                            :cursor (min (-cursor b) (text/chars doc))
                            :sel (let [sel (-sel b)]
                                   (when (and sel (<= (second sel) (text/chars doc))) sel))
                            :undo (if undo? kept given)
                            :redo (if undo? given kept)}))))))
    @took))

(defn undo!
  "Step path's buffer back one document. True when a step was taken.
   The current document moves onto :redo; the caret is kept, clamped
   to the document, and the selection while it still fits."
  [ed path]
  (if (open? ed path) (step! ed path true) false))

(defn redo!
  "Step path's buffer forward one document (inverse of undo!)."
  [ed path]
  (if (open? ed path) (step! ed path false) false))
