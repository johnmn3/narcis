(ns narcis.buffer
  "Editor/buffer state in Eve atoms — the editing model itself:
   buffer state lives in Eve atoms, an edit is a swap! on the Eve
   atom, and the value being swapped is eve.deftype data.

   Per buffer, one named Eve atom in the store's disk domain holds a
   BufferState eve.deftype at its root:

     BufferState{:root   <RopeNode>          ; the buffer's rope root
                 :cursor <char offset>
                 :sel    [<start> <end>] or nil
                 :undo   <eve array of RopeNode>  ; oldest first
                 :redo   <eve array of RopeNode>}

   A second named atom holds the editor's plain data:
   {:tabs [<path> ...] :active <path>}.

   Every mutation is ONE swap! on the relevant atom, and the rope op
   runs INSIDE the swap fn — so the new nodes are constructed in the
   atom's own domain, path-copying the deftyped tree in Eve storage
   (Eve's swap! binds the atom's domain while f runs). There is no
   mirror state and no persist step: the swap IS the persistence, so
   state survives process restart by construction and undo — a root
   swap between the :undo/:redo arrays — works across a restart
   exactly as in-process.

   BufferState sits at the atom's root because a deftype with a
   by-reference field (the rope's children arrays) cannot be nested
   inside a collection value in Eve @84bfc7c8 (freeze would pack its
   plain field view and refuse the reference) — see the rope-deftype
   lane REPORT.md. Per-buffer atoms are the shape Eve's own Dataset
   precedent (a deftype at an atom's root) generalizes to.

   The editor requires the store's Eve arm: eve.deftype rope values
   cannot live in the file arm's EDN (and on the host where the file
   arm runs today, bb, Eve itself is W2-walled). open-editor says so
   when handed a file store."
  (:require [clojure.string :as str]
            [eve.deftype :as edt]
            [eve.deftype.array :as arr]
            [narcis.rope :as r]
            [narcis.store :as store]))

;; ---------------------------------------------------------------------------
;; the per-buffer state type

(defprotocol IBufferState
  (-root [b] "The buffer's current rope root.")
  (-cursor [b] "Caret char offset.")
  (-sel [b] "Selection [start end] or nil.")
  (-undo [b] "Eve array of prior roots, oldest first, or nil.")
  (-redo [b] "Eve array of undone roots, oldest first, or nil."))

;; root is a serialized field (holds the RopeNode by Eve's value
;; encoding, Dataset-style): a ^:RopeNode reference field would name a
;; type from another namespace, and eve.deftype's generated code
;; resolves a referenced type's fns in the defining namespace — every
;; ^:Type reference in Eve's own code at this pin is same-namespace.
(edt/deftype BufferState [root cursor sel undo redo]
  IBufferState
  (-root [_] root)
  (-cursor [_] cursor)
  (-sel [_] sel)
  (-undo [_] undo)
  (-redo [_] redo))

;; ---------------------------------------------------------------------------
;; eve array helpers (undo/redo stacks)

(defn- arr-len [a] (if (nil? a) 0 (arr/alength a)))

(defn- arr-push [a v]
  (let [n (arr-len a)
        b (arr/make-array (inc n))]
    (dotimes [i n] (arr/aset b i (arr/aget a i)))
    (arr/aset b n v)
    b))

(defn- arr-pop [a]
  (let [n (arr-len a)
        b (arr/make-array (dec n))]
    (dotimes [i (dec n)] (arr/aset b i (arr/aget a i)))
    b))

(defn- arr-vec [a]
  (if (nil? a) [] (vec (arr/array-seq a))))

;; ---------------------------------------------------------------------------
;; the editor handle

(def editor-name "editor")
(def empty-state {:buffers {} :tabs [] :active nil})

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
   :editor (store/open-atom store editor-name {:tabs [] :active nil :rev 0})
   :buffers (atom {})})

(defn- buffer-handle [ed path]
  (or (get @(:buffers ed) path)
      (let [h (store/open-atom (:store ed)
                               (str "buffer-" (sanitize path) "-" (hash path))
                               nil)]
        (swap! (:buffers ed) assoc path h)
        h)))

(defn- editor-value [ed] (store/atom-deref (:editor ed)))

(defn rev
  "Editor revision: bumped by every state change (buffer swaps
   included) — the sync token narcis.ops/launcher expose to clients."
  [ed]
  (get (editor-value ed) :rev 0))

(defn- bump! [ed]
  (store/atom-swap! (:editor ed) (fn [s] (update s :rev (fnil inc 0)))))

(defn- buffer-state [ed path]
  (store/atom-deref (buffer-handle ed path)))

(defn state
  "The whole editor state as plain data (buffer entries as maps with
   rope roots for :root and root vectors for :undo/:redo) — the shape
   narcis.buffer has always exposed."
  [ed]
  (let [ev (editor-value ed)]
    {:buffers (into {}
                    (keep (fn [path]
                            (when-let [b (buffer-state ed path)]
                              [path {:root (-root b)
                                     :cursor (-cursor b)
                                     :sel (-sel b)
                                     :undo (arr-vec (-undo b))
                                     :redo (arr-vec (-redo b))}])))
                    (:tabs ev))
     :tabs (:tabs ev)
     :active (:active ev)}))

(defn buffer [ed path]
  (get-in (state ed) [:buffers path]))

(defn buffer-root [ed path] (:root (buffer ed path)))
(defn buffer-text [ed path] (r/to-string (buffer-root ed path)))
(defn cursor [ed path] (:cursor (buffer ed path)))
(defn selection [ed path] (:sel (buffer ed path)))
(defn tabs [ed] (:tabs (editor-value ed)))
(defn active [ed] (:active (editor-value ed)))
(defn undo-depth [ed path] (count (:undo (buffer ed path))))
(defn redo-depth [ed path] (count (:redo (buffer ed path))))

;; ---------------------------------------------------------------------------
;; mutations — each is one swap! on an Eve atom

(defn- transfer-root
  "Inside a swap!: a string becomes a rope here (constructed in the
   atom's domain); a rope root from another domain is rebuilt here;
   an in-domain root passes by reference."
  [root-or-text]
  (cond (string? root-or-text) (r/from-string root-or-text)
        (and (r/RopeNode? root-or-text)
             (not (r/in-current-domain? root-or-text))) (r/rebuild root-or-text)
        :else root-or-text))

(defn- activate-swap [ed path]
  (store/atom-swap! (:editor ed) (fn [s] (-> s
                                             (assoc :active path)
                                             (update :rev (fnil inc 0))))))

(defn open-buffer!
  "Open path with rope root (or text string) as its content. Opening an
   already-open path just activates it. New buffers start with an empty
   undo history — the opened root is version zero."
  [ed path root-or-text]
  (if (buffer-state ed path)
    (activate-swap ed path)
    (do (store/atom-swap! (buffer-handle ed path)
                          (fn [_] (->BufferState (transfer-root root-or-text)
                                                 0 nil nil nil)))
        (store/atom-swap! (:editor ed)
                          (fn [s] (-> s
                                      (update :tabs conj path)
                                      (assoc :active path)
                                      (update :rev (fnil inc 0))))))))

(defn open-file!
  "Open a file from disk: its text is streamed into a rope inside the
   buffer atom's swap (narcis.rope/ingest-file — the file is never held
   whole), so the tree is constructed directly in the atom's domain."
  [ed path]
  (if (buffer-state ed path)
    (activate-swap ed path)
    (do (store/atom-swap! (buffer-handle ed path)
                          (fn [_] (->BufferState (r/ingest-file path)
                                                 0 nil nil nil)))
        (store/atom-swap! (:editor ed)
                          (fn [s] (-> s
                                      (update :tabs conj path)
                                      (assoc :active path)
                                      (update :rev (fnil inc 0))))))))

(defn close-buffer! [ed path]
  (store/atom-swap! (buffer-handle ed path) (fn [_] nil))
  (store/atom-swap! (:editor ed)
                    (fn [s] (let [tabs (vec (remove #(= % path) (:tabs s)))]
                              (-> s
                                  (assoc :tabs tabs)
                                  (assoc :active (if (= path (:active s))
                                                   (peek tabs)
                                                   (:active s)))
                                  (update :rev (fnil inc 0)))))))

(defn activate! [ed path]
  (activate-swap ed path))

(defn set-cursor! [ed path offset]
  (store/atom-swap! (buffer-handle ed path)
                    (fn [b] (->BufferState (-root b) offset (-sel b)
                                           (-undo b) (-redo b))))
  (bump! ed))

(defn set-selection! [ed path sel]
  (store/atom-swap! (buffer-handle ed path)
                    (fn [b] (->BufferState (-root b) (-cursor b) sel
                                           (-undo b) (-redo b))))
  (bump! ed))

(defn edit-buffer!
  "Replace path's root with new-root as one undoable edit: the prior
   root is pushed onto :undo and :redo is cleared. Cursor moves to
   new-cursor when given."
  ([ed path new-root] (edit-buffer! ed path new-root nil))
  ([ed path new-root new-cursor]
   (store/atom-swap! (buffer-handle ed path)
                     (fn [b] (->BufferState (transfer-root new-root)
                                            (if (some? new-cursor) new-cursor (-cursor b))
                                            (-sel b)
                                            (arr-push (-undo b) (-root b))
                                            nil)))
   (bump! ed)))

(defn insert-text!
  "Insert s at char offset in path's buffer, as one undoable edit.
   The rope op runs inside the swap, constructing the new path of
   deftyped nodes in the atom's own domain."
  [ed path offset s]
  (store/atom-swap! (buffer-handle ed path)
                    (fn [b] (let [nr (r/insert (-root b) offset s)]
                              (->BufferState nr
                                             (+ offset (count s))
                                             (-sel b)
                                             (arr-push (-undo b) (-root b))
                                             nil))))
  (bump! ed))

(defn delete-range!
  "Delete chars [start end) in path's buffer, as one undoable edit."
  [ed path start end]
  (store/atom-swap! (buffer-handle ed path)
                    (fn [b] (let [nr (r/delete (-root b) start end)]
                              (->BufferState nr
                                             start
                                             (-sel b)
                                             (arr-push (-undo b) (-root b))
                                             nil))))
  (bump! ed))

(defn undo!
  "Step path's buffer back one root. Returns true when a step was
   taken. The current root moves onto :redo."
  [ed path]
  (let [b (buffer-state ed path)]
    (if (or (nil? b) (zero? (arr-len (-undo b))))
      false
      (do (store/atom-swap! (buffer-handle ed path)
                            (fn [b] (let [u (-undo b)]
                                      (->BufferState (arr/aget u (dec (arr-len u)))
                                                     (-cursor b)
                                                     (-sel b)
                                                     (arr-pop u)
                                                     (arr-push (-redo b) (-root b))))))
          (bump! ed)
          true))))

(defn redo!
  "Step path's buffer forward one root (inverse of undo!)."
  [ed path]
  (let [b (buffer-state ed path)]
    (if (or (nil? b) (zero? (arr-len (-redo b))))
      false
      (do (store/atom-swap! (buffer-handle ed path)
                            (fn [b] (let [r (-redo b)]
                                      (->BufferState (arr/aget r (dec (arr-len r)))
                                                     (-cursor b)
                                                     (-sel b)
                                                     (arr-push (-undo b) (-root b))
                                                     (arr-pop r)))))
          (bump! ed)
          true))))
