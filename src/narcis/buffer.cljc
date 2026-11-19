(ns narcis.buffer
  "Editor/buffer state in the store's one Eve atom — the editing
   model itself: buffer state lives in the app atom, an edit is a
   swap! on it, and the value being swapped is eve.deftype data.

   The app atom's :buffers slot holds one BufferState eve.deftype
   per open buffer, keyed by path:

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

   The :editor slot holds the editor's plain data:
   {:tabs [<path> ...] :active <path> :theme <choice> :rev n}.

   Every mutation is ONE swap! on the app atom, at the slot's key
   path, and the text op runs INSIDE the swap fn (edit!) — so the
   new nodes are built in the atom's domain, path-copying the
   document's tree in Eve storage (Eve's swap! binds the atom's
   domain while f runs). There is no mirror state and no persist
   step: the swap IS the persistence, so state survives process
   restart by construction and undo — a root swap between the
   :undo/:redo lists, which cost nothing to keep because documents
   share what they did not change — works across a restart exactly
   as in-process.

   BufferState sits nested in a plain map, which is sound because
   it is only ever built inside a swap fn (where the domain is
   bound and its parts are Eve pointers) and read inside a read
   region — never constructed on the host from a delivered value,
   and the app atom is never plainly derefed (a value held by
   reference cannot be packed for delivery). narcis.probe-oneatom
   re-derives this at the current pin.

   The editor requires the store's Eve arm: open-editor says so when
   handed a file store."
  (:require [eve.alpha :as eve :refer [$]]
            [narcis.text :as text]))

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

(eve/deftype BufferState [root cursor sel undo redo eol rev trev gen nundo nredo]
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

(eve/deftype History [doc older]
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

(defn open-editor
  "Open the editor state held in store (created empty on first use).
   The store must be on its Eve arm — see the namespace docstring."
  [store]
  (when (not= :eve (:impl store))
    (throw (ex-info (str "narcis.buffer requires the Eve atom store; "
                         "this store is on the file arm (host cannot run Eve — W2)")
                    {:impl (:impl store)})))
  (swap! (:atom store) update :editor
             (fn [v] (or v {:tabs [] :active nil :rev 0 :theme nil})))
  {:store store})

(defn- app-atom
  "The store's one atom: the whole app state lives in it, and
   every change below is a swap! on it in the normal way."
  [ed]
  (:atom (:store ed)))

(defn- editor-value [ed] ($ (get @(:atom (:store ed)) :editor)))

(defn- bump-editor [s] (update s :rev (fnil inc 0)))

(declare snapshot close-buffer!)

(defn rev
  "The sync token clients see: the editor slot's revision, plus the
   buffer's own when a path is given. Every swap moves one of them."
  ([ed] (get (editor-value ed) :rev 0))
  ([ed path]
   (+ (rev ed) (or (:rev (snapshot ed path)) 0))))

;; ---------------------------------------------------------------------------
;; reads
;;
;; A buffer is read IN PLACE, inside a read region (Eve's $): a
;; plain deref of the app atom would have to
;; pack the whole app state for delivery — every buffer's whole
;; document — for every question asked of it (and refuses, where a
;; value is held by reference). read is the one door: its f sees
;; the document where it is, touches what it needs, and answers
;; plain data.

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
    ($ (let [b (get-in @(:atom (:store ed)) [:buffers path])]
             (when (some? b) (f (view-of path b)))))))

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
   slot, so the choice is durable in the store and every client reads
   the same one from state."
  [ed choice]
  (swap! (app-atom ed) update :editor (fn [s] (bump-editor (assoc s :theme choice)))))

;; ---------------------------------------------------------------------------
;; mutations — each is one swap! on the app atom

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
  (swap! (app-atom ed) update :editor (fn [s] (bump-editor (assoc s :active path)))))

(defn open-buffer!
  "Open path holding content — a string, or a document (copied into
   this buffer's slot) — with line ending eol (\"\\n\" when not given).
   Opening an already-open path just activates it. A new buffer starts
   with an empty undo history: what was opened is version zero."
  ([ed path content] (open-buffer! ed path content "\n"))
  ([ed path content eol]
   (if (open? ed path)
     (activate! ed path)
     (let [gen (rev ed)]
       (swap! (app-atom ed) update-in [:buffers path] (fn [_] (->BufferState (if (string? content)
                                              (text/from-string content)
                                              (text/copy-here content))
                                            0 nil nil nil eol 0 0 gen 0 0)))
       (swap! (app-atom ed) update :editor (fn [s] (add-tab s path)))))))

;; ---------------------------------------------------------------------------
;; big files: a partial document over a scanned skeleton
;;
;; A partial buffer is plain data in the app atom at [:loading
;; path]: {:id n :loaded-chars n :total-bytes n} — its document
;; accounts for :loaded-chars of the file's stream characters, and
;; the entry's presence IS the partiality (it persists, so a buffer
;; left partial by a dead process reopens partial). The id names a
;; materialization run: the loader thread runs under the id it was
;; spawned with and stops when the entry no longer carries it (the
;; buffer was closed, or a newer run claimed the path). The tail
;; state itself — an open reader — is host-side, in the loader
;; thread's hands, never in the atom. Scans are host-side too.

(defonce ^:private load-ids (atom 0))
(defonce ^:private scans (atom {}))
(defonce ^:private materializing (atom #{}))

(defn partial?
  "True while path's document is a prefix of its file (see
   open-file!): the rest of the file is a skeleton — counted and
   indexed by its scan, readable by line, not yet a document."
  [ed path]
  (true? ($ (some? (get-in @(:atom (:store ed)) [:loading path])))))

(defn materializing?
  "True while a loader thread is materializing path's document."
  [path]
  (contains? @materializing path))

(defn loading-map
  "The partial buffers, as plain data: {path {:loaded n :total n
   :active b}} — stream characters in, of the file's bytes;
   :active while a materialization is running."
  [ed]
  (or ($ (into {}
               (map (fn [[p l]] [p {:loaded (:loaded-chars l)
                                    :total (:total-bytes l)
                                    :active (contains? @materializing p)}]))
               (get @(:atom (:store ed)) :loading)))
      {}))

(defn extent-ranges
  "The materialized extents of a partial buffer, as sorted
   [start-line line-count] pairs in file line coordinates: the
   stretches a reader pulled into the store ahead of the
   hydration frontier by navigating to them."
  [ed path]
  (or ($ (into []
               (map (fn [[s e]] [s (:lines e)]))
               (sort-by key (get-in @(:atom (:store ed)) [:extents path] {}))))
      []))

(defn scanning-map
  "The scans in flight, as plain data: {path {:loaded n :total
   n}} — bytes scanned, of the file's bytes."
  []
  (into {}
        (keep (fn [[p s]]
                (let [s @s]
                  (when-not (:done? s)
                    [p {:loaded (:bytes s) :total (:total-bytes s)}]))))
        @scans))

(defn skeleton
  "What is known of path's file beyond its document: {:lines n
   :chars n :done? b} from its scan. While the scan runs, the
   counts are the scanned prefix's, extrapolated to the whole
   file by the bytes that prefix took: the file's height is
   approximately known from the scan's first chunks and exact
   when :done?, instead of growing linearly under the reader
   for as long as the scan runs (a reader parked at the bottom
   watched the scroll thumb slide up the whole scan). Nil when
   there is no scan (a whole buffer, or one not opened from a
   file)."
  [path]
  (when-let [s (get @scans path)]
    (let [s @s
          {:keys [lines chars bytes total-bytes done?]} s]
      (if (and (not done?) (pos? bytes) (pos? lines) (< bytes total-bytes))
        {:lines (long (* lines (/ total-bytes bytes)))
         :chars (long (* chars (/ total-bytes bytes)))
         :done? false}
        {:lines lines :chars chars :done? done?}))))

(defn scan-of
  "Path's scan state atom, or nil."
  [path]
  (get @scans path))

(defn- start-scan! [path]
  (or (get @scans path)
      (let [s (text/scan-file! path)]
        (swap! scans assoc path s)
        s)))

(defn- stop-scan! [path]
  (when-let [s (get @scans path)]
    (swap! s assoc :stopped true)
    (swap! scans dissoc path)))

(defn- claim-partial!
  "Register path's partiality in the app atom and answer the run id."
  [ed path loaded-chars total-bytes]
  (let [id (swap! load-ids inc)]
    (swap! (app-atom ed) assoc-in [:loading path]
           {:id id :loaded-chars loaded-chars :total-bytes total-bytes})
    id))

;; ---------------------------------------------------------------------------
;; just-in-time extents: pieces of the file a view asked for, landed
;; in the same rope the background hydration is filling
;;
;; [:extents path] in the app atom: {start-line {:doc d :chars n
;; :lines n :start-char n :start-stream n :stream-chars n}} — a
;; stretch of the file read out for a view (narcis.launcher's
;; window), built into a document so the hydration absorbs it by
;; reference when its frontier reaches it (run-load!) instead of
;; reading those lines again. Extents never reach the file's last
;; line: a stretch short of it always ends on a break, which keeps
;; an extent's text uniform (every line with its ending). Positions
;; are kept in both units the file is counted in: document
;; characters (:start-char, :chars) and stream characters
;; (:start-stream, :stream-chars) — they differ by the \n of a
;; \r\n pair.

(defn extents
  "Path's just-in-time extents, as stored (see the section note)."
  [ed path]
  (or ($ (get-in @(:atom (:store ed)) [:extents path])) {}))

(defn- extent-doc-for
  "The document of file-window lines: every line with its break,
   as the file holds them. Built where documents are built:
   inside a swap."
  [lines]
  (text/from-string (apply str (map (fn [l] (str (:text l) "\n")) lines))))

(defn note-extent!
  "Land lines read out of path's file for a view as an extent (see
   the section note). lines are one stretch of file-window lines
   ({:n :start :text}); stream-start and stream-end are the
   stretch's span in stream characters, as file-window reports
   them. A stretch an existing extent already covers — or merely
   overlaps — is left unnoted (the view was served either way); a
   fresh stretch merges with any extent it touches end to end."
  [ed path lines stream-start stream-end]
  (when (seq lines)
    (swap! (app-atom ed)
           (fn [app]
             (let [exts (get-in app [:extents path] {})
                   a (:n (first lines))
                   b (inc (:n (peek lines)))
                   overlap? (boolean
                             (some (fn [[s e]]
                                     (and (< s b) (< a (+ s (:lines e)))))
                                   exts))]
               (if overlap?
                 app
                 (let [e {:doc (extent-doc-for lines)
                          :chars (reduce + (map (fn [l] (inc (count (:text l)))) lines))
                          :lines (count lines)
                          :start-char (:start (first lines))
                          :start-stream stream-start
                          :stream-chars (- stream-end stream-start)}
                       ;; merge with an extent ending at a
                       [exts e a] (if-let [[ps pe] (some (fn [[s x]]
                                                           (when (= (+ s (:lines x)) a) [s x]))
                                                         exts)]
                                    [(dissoc exts ps)
                                     {:doc (text/replace (:doc pe) (:chars pe) (:chars pe) (:doc e))
                                      :chars (+ (:chars pe) (:chars e))
                                      :lines (+ (:lines pe) (:lines e))
                                      :start-char (:start-char pe)
                                      :start-stream (:start-stream pe)
                                      :stream-chars (+ (:stream-chars pe) (:stream-chars e))}
                                     ps]
                                    [exts e a])
                       ;; and with one starting at b
                       exts (if-let [ne (get exts b)]
                              (assoc (dissoc exts b) a
                                     {:doc (text/replace (:doc e) (:chars e) (:chars e) (:doc ne))
                                      :chars (+ (:chars e) (:chars ne))
                                      :lines (+ (:lines e) (:lines ne))
                                      :start-char (:start-char e)
                                      :start-stream (:start-stream e)
                                      :stream-chars (+ (:stream-chars e) (:stream-chars ne))})
                              (assoc exts a e))]
                   (assoc-in app [:extents path] exts))))))))

#?(:clj
   (defn- run-load!
     "A materialization run: append the tail's chunks to path's
      document, one swap per chunk, until the file is in or the
      partial entry leaves the app atom. The reading is done out
      here (plain IO); the appending is done inside the swap, on
      the document the buffer holds at that moment — while a buffer
      materializes its edits are refused (narcis.ops), so nothing
      else moves its root, and a retried swap re-appends the same
      piece to the same document. A stopped run (a read failure)
      clears its entry and says so on the server's err: the buffer
      stays, holding what arrived."
     [ed path load-id tail]
     (let [tail-box (atom tail)
           absorbed-to (volatile! nil)]
       (try
         (loop []
           (let [entry ($ (get-in @(:atom (:store ed)) [:loading path]))]
             (when (and entry (= load-id (:id entry)))
               (let [{:keys [piece loaded-chars done? tail]}
                     (text/ingest-tail-read @tail-box)]
                 (reset! tail-box tail)
                 (swap! (app-atom ed)
                        (fn [app]
                          (if (and (get-in app [:buffers path])
                                   (= load-id (get-in app [:loading path :id])))
                            (let [b (get-in app [:buffers path])
                                  root (-root b)
                                  root' (if piece
                                          (text/insert root (text/chars root) piece)
                                          root)
                                  ;; absorb any extent the frontier has
                                  ;; reached: its part from the frontier
                                  ;; line on goes onto the rope by
                                  ;; reference, and the run's count jumps
                                  ;; to the extent's end in stream chars
                                  [root' loaded-chars exts]
                                  (loop [root root' loaded loaded-chars
                                         exts (get-in app [:extents path] {})]
                                    (let [[s e] (first (sort-by key exts))
                                          fl (text/lines root)]
                                      (if (or (nil? e) (> s fl))
                                        [root loaded exts]
                                        (cond
                                          ;; the chunks hydrated it already
                                          (<= (+ s (:lines e)) fl)
                                          (recur root loaded (dissoc exts s))

                                          ;; an extent whose start does not
                                          ;; sit where the file says is stale
                                          (not= (text/line-start-offset root s)
                                                (:start-char e))
                                          (recur root loaded (dissoc exts s))

                                          :else
                                          (let [edoc (:doc e)
                                                part (if (= fl s)
                                                       edoc
                                                       (text/from-string
                                                        (text/slice-string
                                                         edoc
                                                         (text/line-start-offset edoc (- fl s))
                                                         (text/chars edoc))))
                                                root (text/replace root (text/chars root)
                                                                   (text/chars root) part)
                                                end (+ (:start-stream e) (:stream-chars e))]
                                            (vreset! absorbed-to end)
                                            (recur root end (dissoc exts s)))))))]
                              (cond-> (assoc-in app [:buffers path]
                                                (with-state b {:root root'}))
                                true (assoc-in [:extents path] exts)
                                done? (update :loading dissoc path)
                                done? (update :extents dissoc path)
                                (not done?) (assoc-in [:loading path :loaded-chars] loaded-chars)))
                            app)))
                 ;; an absorption moved the run past where the reader
                 ;; stands: resync it to the extent's end
                 (when (and (not done?) @absorbed-to (> @absorbed-to loaded-chars))
                   (text/ingest-tail-close @tail-box)
                   (reset! tail-box (text/ingest-tail path @absorbed-to)))
                 (vreset! absorbed-to nil)
                 (when-not done?
                   (recur))))))
         (catch #?(:cljs :default :default Throwable) t
           (binding [*out* *err*]
             (println "narcis: load of" path "stopped:" (ex-message t)))
           (swap! (app-atom ed)
                  (fn [app]
                    (if (= load-id (get-in app [:loading path :id]))
                      (update app :loading dissoc path)
                      app))))
         (finally
           (text/ingest-tail-close @tail-box)
           (swap! materializing disj path)
           ;; the entry is gone when the run finished (or the buffer
           ;; closed under it): the skeleton has served its turn
           (when-not (partial? ed path)
             (stop-scan! path)))))))

#?(:clj
   (defn- start-load!
     "Spawn path's materialization on its own daemon thread."
     [ed path load-id tail]
     (doto (Thread. ^Runnable (fn [] (run-load! ed path load-id tail)))
       (.setDaemon true)
       (.start))))

#?(:clj
   (defn materialize!
     "Start materializing path's whole document (see the section
      note): the file's tail appended by a background loader, from
      where the document stops. Answers true when the buffer is
      partial — a run already going counts; false when it is whole
      and there was nothing to start."
     [ed path]
     (if (partial? ed path)
       (do (when-not (materializing? path)
             (let [entry ($ (get-in @(:atom (:store ed)) [:loading path]))
                   tail (text/ingest-tail path (:loaded-chars entry))
                   id (claim-partial! ed path (:loaded-chars entry) (:total-bytes entry))]
               (swap! materializing conj path)
               (start-load! ed path id tail)))
           true)
       false)))

(defn line-of
  "The line holding char offset in path's buffer: counted in the
   document when the offset is in it; in the file, by its scan's
   index, when the buffer is partial and the offset is past the
   document."
  [ed path offset]
  (let [line (read ed path
                   (fn [{doc :root}]
                     (when (<= offset (text/chars doc))
                       (:line (text/line-of doc offset)))))]
    (or line
        (text/file-line-of path (scan-of path) offset))))

(defn open-file!
  "Open a file from disk: its head (about 64 KB) is read
   synchronously and becomes the buffer, open and viewable at
   once. A bigger file opens partial: a background hydration
   appends the rest to the document a chunk at a time, and
   meanwhile the unhydrated rest is a skeleton — a background
   scan counts and indexes it (see the section note), any stretch
   of it reads out of the file on demand for a view and lands in
   the same document as an extent for the hydration to absorb.
   A file that fits its head opens whole, as any small file does.

   Reopening a buffer left partial (the process died, or the app
   closed mid-way) resumes its hydration where the document
   stops — unless the file's size changed meanwhile, in which
   case the open restarts from the head."
  ([ed path] (open-file! ed path {}))
  ([ed path {:keys [head]}]
   (cond
     (and (open? ed path) (not (partial? ed path)))
     (activate! ed path)

     (open? ed path)
     (let [entry ($ (get-in @(:atom (:store ed)) [:loading path]))
           size (text/file-length path)]
       (if (and entry (= size (:total-bytes entry)))
         (do (start-scan! path)
             (materialize! ed path)
             (activate! ed path))
         (do (close-buffer! ed path)
             (open-file! ed path {:head head}))))

     :else
     (let [{:keys [pieces eol loaded-chars total-bytes done? tail]}
           (text/ingest-open path (when head {:head head}))
           gen (rev ed)]
       ;; the head's document is built inside the swap, where
       ;; documents are built; the reading was done above, in the open
       (swap! (app-atom ed) update-in [:buffers path]
              (fn [_] (->BufferState (text/from-pieces pieces) 0 nil nil nil eol 0 0 gen 0 0)))
       (swap! (app-atom ed) update :editor (fn [s] (add-tab s path)))
       (when-not done?
         (let [id (claim-partial! ed path loaded-chars total-bytes)]
           (swap! materializing conj path)
           (start-load! ed path id tail)
           (start-scan! path)))))))

(defn close-buffer! [ed path]
  (stop-scan! path)
  (swap! (app-atom ed) (fn [app] (cond-> (update app :buffers dissoc path)
                                   (contains? app :loading)
                                   (update :loading dissoc path)
                                   (contains? app :extents)
                                   (update :extents dissoc path))))
  (swap! (app-atom ed) update :editor (fn [s] (let [tabs (vec (remove #(= % path) (:tabs s)))]
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
  (swap! (app-atom ed) update-in [:buffers path] (fn [b] (with-state b {:cursor offset :sel sel}))))

(defn set-cursor! [ed path offset]
  (swap! (app-atom ed) update-in [:buffers path] (fn [b] (with-state b {:cursor offset}))))

(defn set-selection! [ed path sel]
  (swap! (app-atom ed) update-in [:buffers path] (fn [b] (with-state b {:sel sel}))))

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
    (swap! (app-atom ed) update-in [:buffers path] (fn [b]
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
    (swap! (app-atom ed) update-in [:buffers path] (fn [b]
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
