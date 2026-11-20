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

(defn- file-lines-of
  "The file lines an extent's span covers: its :file-lines,
   stamped at noting and never moved by an edit. An extent
   stored before the stamp existed reads as unedited."
  [e]
  (or (:file-lines e) (:lines e)))

(defn- file-chars-of
  "The document characters an extent's span held in the file:
   its :file-chars, stamped at noting, never moved by an edit."
  [e]
  (or (:file-chars e) (:chars e)))

(defn- extent-table
  "A stored extents map as a sorted vector of {:key k
   :file-start n :extent e}: each extent's start line in both
   frames — the document frame it is stored under (k) and the
   file frame it was noted from. The frames differ only by
   the edits inside earlier extents: an extent's file start
   is its key less the line delta the assembly has already
   absorbed into the root (base, from the loading entry) and
   less the line deltas (:lines less :file-lines) of every
   extent before it in the map, because an edit that adds or
   drops lines moves every later stretch in the document and
   nothing in the file."
  [exts base]
  (loop [es (sort-by key exts) delta base acc []]
    (if-let [[s e] (first es)]
      (recur (rest es)
             (+ delta (- (:lines e) (file-lines-of e)))
             (conj acc {:key s :file-start (- s delta) :extent e}))
      acc)))

(defn extent-ranges
  "The materialized extents of a partial buffer, as sorted
   [start-line line-count] pairs in file line coordinates: the
   stretches a reader pulled into the store ahead of the
   hydration frontier by navigating to them. (The stored map
   is in document coordinates — see the extents section note;
   this is its file-frame reading, the one the scan's index,
   the segment spans and the load line's byte geometry speak.)"
  [ed path]
  (or ($ (into []
               (map (fn [{:keys [file-start extent]}]
                      [file-start (file-lines-of extent)]))
               (extent-table (get-in @(:atom (:store ed)) [:extents path] {})
                             (or (get-in @(:atom (:store ed))
                                         [:loading path :line-delta])
                                 0))))
      []))

(defn view-extent-ranges
  "extent-ranges' file-frame [start-line line-count] pairs of the
   extents a view read (:src :view): the temporary rope the load line
   draws over the built one. A view stretch merged with a built one
   counts as built."
  [ed path]
  (or ($ (into []
               (keep (fn [{:keys [file-start extent]}]
                       (when (= :view (:src extent))
                         [file-start (file-lines-of extent)])))
               (extent-table (get-in @(:atom (:store ed)) [:extents path] {})
                             (or (get-in @(:atom (:store ed))
                                         [:loading path :line-delta])
                                 0))))
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
  "Register path's partiality in the app atom and answer the run id.
   The entry also carries the assembly's frame account —
   :line-delta and :char-delta, the totals by which the root's
   document frame sits past the file's, from the edited extents
   it has absorbed (see the extents section note) — and a
   re-registering preserves them: they describe the root, which
   a new run does not change."
  [ed path loaded-chars total-bytes]
  (let [id (swap! load-ids inc)]
    (swap! (app-atom ed) update-in [:loading path]
           (fn [entry]
             (assoc (or entry {})
                    :id id :loaded-chars loaded-chars
                    :total-bytes total-bytes)))
    id))

;; ---------------------------------------------------------------------------
;; just-in-time extents: pieces of the file a view asked for, landed
;; in the same rope the background hydration is filling
;;
;; [:extents path] in the app atom: {start-line {:doc d :chars n
;; :lines n :start-char n :start-stream n :stream-chars n
;; :file-lines n :file-chars n}} — a stretch of the file read out
;; for a view (narcis.launcher's window), built into a document
;; so the assembly appends it by reference when the root reaches
;; it, instead of reading those lines again. Extents never reach
;; the file's last line: a stretch short of it always ends on a
;; break, which keeps an extent's text uniform (every line with
;; its ending). Positions are kept in both units the file is
;; counted in: document characters (:start-char, :chars) and
;; stream characters (:start-stream, :stream-chars) — they
;; differ by the \n of a \r\n pair.
;;
;; The coordinate law, once an extent can be edited (edit-extent!):
;; the map lives in DOCUMENT coordinates — the start-line keys
;; and :start-char name where the stretch sits in the document
;; being built, edits included, which is the frame the root,
;; the caret and the assembly all speak. The FILE frame is
;; kept beside it, stamped at noting and never moved by an
;; edit: :file-lines and :file-chars are the span's counts in
;; the file, and :start-stream/:stream-chars are its span in
;; the file's stream characters (the assembly's resync reads
;; only those). An edit inside an extent rewrites its
;; :doc/:chars/:lines and, in the same swap, rekeys every
;; later extent by the line delta and shifts its :start-char
;; by the char delta; notings arrive in the file frame (a read
;; of the file reports the file's own positions) and
;; assoc-extent translates them by the deltas of the extents
;; already stored. An absorbed extent leaves the map, but its
;; delta cannot leave the account: the loading entry carries
;; :line-delta and :char-delta, the totals of the edited
;; extents the root has taken in, and a noting translates by
;; those plus the deltas still in the map — so a stretch noted
;; after an edited one was absorbed still lands at its
;; document position. The two frames coincide until the first
;; edit, and the finished document is the file with exactly
;; the edits applied.

(defn extents
  "Path's just-in-time extents, as stored (see the section note)."
  [ed path]
  (or ($ (get-in @(:atom (:store ed)) [:extents path])) {}))

(defn- extent-doc-for
  "The document of file-window lines: every line with its break,
   as the file holds them. Built where documents are built: in
   the store domain's context — a swap on an atom of the domain."
  [lines]
  (text/from-string (apply str (map (fn [l] (str (:text l) "\n")) lines))))

(defn- assoc-extent
  "app with extent e at line a in [:extents path]: unchanged
   when e overlaps an existing extent; merged with any extent
   it touches end to end. The caller speaks the file frame —
   a, :start-char, :lines and :chars are the file's own counts,
   as a read of the file reports them — and the map is stored
   in the document frame (see the section note), so the noting
   translates by the deltas of the extents already stored and
   stamps e with its file span (:file-lines, :file-chars).
   Overlap and adjacency are judged in the file frame, where
   they cannot be moved by an edit. Pure map-and-rope surgery
   on prebuilt values — the caller builds e's document, so a
   retried swap redoes only this, never the build. (Building
   inside the noting swap was the segment workers'
   starvation: a segment's build is slower than the frontier's
   commit cadence, so the swap retried forever and the extent
   never landed.)"
  [app path a e]
  (let [exts (get-in app [:extents path] {})
        table (extent-table exts (or (get-in app [:loading path :line-delta]) 0))
        b (+ a (:lines e))
        overlap? (boolean
                  (some (fn [{:keys [file-start extent]}]
                          (and (< file-start b)
                               (< a (+ file-start (file-lines-of extent)))))
                        table))]
    (if overlap?
      app
      (let [prev (some (fn [{:keys [file-start extent] :as row}]
                         (when (= (+ file-start (file-lines-of extent)) a) row))
                       table)
            nrow (some (fn [{:keys [file-start] :as row}]
                         (when (= file-start b) row))
                       table)
            ;; e in the document frame: the deltas of the
            ;; extents before it — the ones the assembly has
            ;; absorbed into the root (the loading entry's
            ;; account) and the ones still in the map — move
            ;; its key and its start
            before (filter (fn [{:keys [file-start]}] (<= file-start a)) table)
            dlines (+ (or (get-in app [:loading path :line-delta]) 0)
                      (reduce + 0 (map (fn [{:keys [extent]}]
                                         (- (:lines extent) (file-lines-of extent)))
                                       before)))
            dchars (+ (or (get-in app [:loading path :char-delta]) 0)
                      (reduce + 0 (map (fn [{:keys [extent]}]
                                         (- (:chars extent) (file-chars-of extent)))
                                       before)))
            e (assoc e :file-lines (:lines e)
                       :file-chars (:chars e)
                       :start-char (+ (:start-char e) dchars))
            ;; merge with the extent ending where e starts: the
            ;; stored extent takes e in, at its own key
            [exts k e] (if prev
                         (let [pe (:extent prev)]
                           [(dissoc exts (:key prev))
                            (:key prev)
                            {:doc (text/replace (:doc pe) (:chars pe) (:chars pe) (:doc e))
                             :chars (+ (:chars pe) (:chars e))
                             :lines (+ (:lines pe) (:lines e))
                             :start-char (:start-char pe)
                             :start-stream (:start-stream pe)
                             :stream-chars (+ (:stream-chars pe) (:stream-chars e))
                             :file-lines (+ (file-lines-of pe) (:file-lines e))
                             :file-chars (+ (file-chars-of pe) (:file-chars e))
                             :src (if (= (:src pe) (:src e)) (:src e) :build)}])
                         [exts (+ a dlines) e])
            ;; and with the one starting where e ends
            exts (if nrow
                   (let [ne (:extent nrow)]
                     (assoc (dissoc exts (:key nrow)) k
                            {:doc (text/replace (:doc e) (:chars e) (:chars e) (:doc ne))
                             :chars (+ (:chars e) (:chars ne))
                             :lines (+ (:lines e) (:lines ne))
                             :start-char (:start-char e)
                             :start-stream (:start-stream e)
                             :stream-chars (+ (:stream-chars e) (:stream-chars ne))
                             :file-lines (+ (:file-lines e) (file-lines-of ne))
                             :file-chars (+ (:file-chars e) (file-chars-of ne))
                             :src (if (= (:src e) (:src ne)) (:src e) :build)}))
                   (assoc exts k e))]
        (assoc-in app [:extents path] exts)))))

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
             (let [a (:n (first lines))
                   b (inc (:n (peek lines)))
                   exts (get-in app [:extents path] {})
                   overlap? (boolean
                             (some (fn [[s x]]
                                     (and (< s b) (< a (+ s (:lines x)))))
                                   exts))]
               (if overlap?
                 app
                 ;; a view's stretch is small: build it here, in
                 ;; the swap's domain context, where the caller
                 ;; (a request thread) has none
                 (assoc-extent app path a
                               {:doc (extent-doc-for lines)
                                :chars (reduce + (map (fn [l] (inc (count (:text l)))) lines))
                                :lines (count lines)
                                :start-char (:start (first lines))
                                :start-stream stream-start
                                :stream-chars (- stream-end stream-start)
                                ;; read for a view: the temporary
                                ;; rope's, drawn as such on the
                                ;; load line
                                :src :view})))))))

(defn note-extent-doc!
  "Land a prebuilt extent: e is the full extent map (see
   note-extent!), its :doc built by the caller in the store's
   domain. The noting swap then does only assoc-extent's
   surgery, however big the document is."
  [ed path a e]
  (swap! (app-atom ed) (fn [app] (assoc-extent app path a e))))

(defn covering-extent
  "The [start-line extent] of path's extents whose document
   covers the document-frame char range [a b) — a caret sits
   at its end and still counts as inside — or nil. The stored
   map is in document coordinates (see the section note), so
   a and b are the offsets an op carries."
  [ed path a b]
  (some (fn [[s e]]
          (when (and (<= (:start-char e) a)
                     (<= b (+ (:start-char e) (:chars e))))
            [s e]))
        (extents ed path)))

(defn edited-during-load?
  "True when path's partial document differs from its file: an edit
   landed in an extent during the load (its counts left its file
   span's, or the assembly already absorbed one — the loading entry's
   frame account is nonzero). Until then the file IS the document, so
   a window can be read straight from it by byte."
  [ed path]
  (true? ($ (let [app @(:atom (:store ed))
                  l (get-in app [:loading path])]
              (boolean
               (or (not (zero? (or (:line-delta l) 0)))
                   (not (zero? (or (:char-delta l) 0)))
                   (some (fn [[_ e]]
                           (or (not= (:lines e) (file-lines-of e))
                               (not= (:chars e) (file-chars-of e))))
                         (get-in app [:extents path]))))))))

(defn read-extent
  "(f s e) over the extent of path whose lines cover [start,
   start+count) in the document frame — s its start line, e the
   extent with :doc the document itself — read in place inside a
   read region, the way read hands out the root: the document is
   readable only while f runs, and f answers plain data, which
   read-extent answers; nil when no extent covers the stretch.
   This is the door for reading an extent's text. extents hands
   the map out of its region, and an Eve document leaves a region
   as the slab handle it is (it has no plain form), unpinned and
   rooted only while the app atom still holds it — the assembly
   absorbing or merging that extent lets it go, and a read through
   the old handle then lands on reused blocks (measured:
   bench/narcis/probe_escape.clj reads another document's lines)."
  [ed path start count f]
  ($ (some (fn [[s e]]
             (when (and (<= s start)
                        (<= (+ start count) (+ s (:lines e))))
               (f s e)))
           (get-in @(:atom (:store ed)) [:extents path]))))

(defn edit-extent!
  "Apply f to the document of path's extent at document-frame
   line s, as ONE swap!: (f edoc) answers {:doc new-doc
   :caret n :sel [a b]|nil} with caret and selection in the
   extent's own coordinates, or nil when the edit does not
   apply — and then nothing changes. An answer whose :doc is
   edoc itself moves the caret only. On a change the extent's
   :doc/:chars/:lines become the new document's counts and,
   in the same swap, every later extent is rekeyed by the
   line delta and its :start-char shifted by the char delta —
   the document frame moves with the edit; the file frame
   (:file-lines, :file-chars, :start-stream, :stream-chars)
   does not — and the buffer's caret and selection become the
   answer's, in document coordinates. No undo step is taken:
   the root did not move, and the histories hold root
   documents. Answers the answer's {:caret :sel} in document
   coordinates (plus any :copy f carried), ::gone when the
   extent left the map between the caller's check and the
   swap, or the root has already taken its stretch in (the
   assembly took it), or nil."
  [ed path s f]
  (let [out (volatile! nil)]
    (swap! (app-atom ed)
           (fn [app]
             (let [e (get-in app [:extents path s])]
               (if (or (nil? e)
                       ;; the root has already taken this
                       ;; stretch in: an edit here would be
                       ;; spliced away by the assembly, so the
                       ;; extent counts as gone
                       (< s (dec (text/lines
                                  (-root (get-in app [:buffers path]))))))
                 (do (vreset! out ::gone) app)
                 (let [r (f (:doc e))]
                   (if (nil? r)
                     app
                     (let [nd (:doc r)
                           caret (+ (:start-char e) (get r :caret 0))
                           sel (when (:sel r)
                                 [(+ (:start-char e) (first (:sel r)))
                                  (+ (:start-char e) (second (:sel r)))])]
                       (vreset! out (merge (select-keys r [:copy])
                                           {:caret caret :sel sel}))
                       (if (identical? nd (:doc e))
                         (update-in app [:buffers path]
                                    (fn [b] (with-state b {:cursor caret :sel sel})))
                         (let [dchars (- (text/chars nd) (:chars e))
                               ;; an extent's :lines is its span's
                               ;; break count — the window's line
                               ;; count — which is the document's
                               ;; line count less its standing
                               ;; last line
                               dlines (- (dec (text/lines nd)) (:lines e))]
                           (-> app
                               (assoc-in [:extents path s]
                                         (assoc e :doc nd
                                                  :chars (text/chars nd)
                                                  :lines (dec (text/lines nd))))
                               (update-in [:extents path]
                                          (fn [exts]
                                            (if (and (zero? dchars) (zero? dlines))
                                              exts
                                              (into {}
                                                    (map (fn [[k x]]
                                                           (if (> k s)
                                                             [(+ k dlines)
                                                              (update x :start-char + dchars)]
                                                             [k x])))
                                                    exts))))
                               (update-in [:buffers path]
                                          (fn [b] (with-state b {:cursor caret
                                                                 :sel sel})))))))))))))
    @out))

;; ---------------------------------------------------------------------------
;; parallel segments: worker threads build the file's stretches
;; as extents, from both ends at once, and an assembler puts
;; the pieces the root has reached onto the rope
;;
;; A segment is a run of scan chunks. A worker reads its
;; segment with the same file-window a view reads with and
;; notes it as an extent — worker-built extents are ordinary
;; extents: the load line shows them, a reopened app keeps
;; them. Even workers build from the front, odd workers from
;; the back, so the file's end is a finished document early;
;; a separate tail build takes the file's last stretch by
;; position, before any index reaches it. The assembler
;; appends every extent the root's standing line has reached,
;; by reference, in one swap per pass — and when the scan is
;; done and the root's lines are the scan's, the buffer is
;; whole. There is no sequential reader: every byte of the
;; file is read once, by the one worker that builds its
;; piece, and the document is exact regardless of the order
;; the pieces landed in. Claims are host-side bookkeeping
;; against two workers building the same segment; the
;; extents themselves are the durable record.

(def ^:private segment-chunks
  "Scan chunks per segment: sixty-four 1 MB chunks. A segment
   is the unit of work and of noting — one build, one swap —
   so it is sized to dwarf its bookkeeping: at this size a
   13 GB file is two hundred segments, not thirteen thousand
   chunk swaps."
  64)

(defonce ^:private segment-claims (atom {}))

(defn- segment-spans
  "The file's segments as [start-line line-count] pairs, from
   scan's chunk table: runs of segment-chunks chunks, starts
   and counts summed from the table's breaks. Only complete
   runs are returned, and never the run holding the file's
   last line (an extent never reaches it — the tail build
   takes that stretch, by position). A run with no breaks in
   it (one line longer than the run) is skipped: there is no
   line boundary inside it to build at."
  [scan]
  (let [{:keys [chunks done?]} @scan]
    (loop [cs chunks line 0 spans []]
      (let [group (take segment-chunks cs)]
        (if (and (= segment-chunks (count group))
                 (or (not done?) (seq (drop segment-chunks cs))))
          (let [breaks (reduce + (map second group))]
            (recur (drop segment-chunks cs) (+ line breaks)
                   (if (pos? breaks) (conj spans [line breaks]) spans)))
          spans)))))

#?(:clj
   (defn- frontier-line
     "Path's document's line count: where the assembled root
      stands. (dec of it is the line the root stands at — the
      root's breaks are its fully consumed lines.)"
     [ed path]
     (or (read ed path (fn [s] (text/lines (:root s)))) 0)))

#?(:clj
   (defn- segment-scratch
     "Worker n's scratch atom in the store's domain: the swap on
      it is the domain context its segment documents are built
      in (a document is Eve data — it is built inside a swap, on
      an atom of the domain it will live in). One per worker, a
      fixed small set of ids reused across runs; the value is
      whatever the worker built last and means nothing."
     [ed n]
     (eve/atom {:id (keyword (str "narcis.store/seg-" n))
                :persistent (:base (:store ed))}
               nil)))

#?(:clj
   (defn- uncovered-ranges
     "The [start count] sub-ranges of [s, s+cnt) that are
      neither covered by exts (sorted [start count] pairs) nor
      before line nxc: the pieces of a segment a worker can
      still build. A segment the root has reached into, or a
      view already pulled a stretch of, is built in the pieces
      that remain — nothing is read twice."
     [exts nxc s cnt]
     (loop [rs [[(max s nxc) (+ s cnt)]] es exts]
       (if-let [[xs xc] (first es)]
         (recur (into []
                      (mapcat (fn [[a b]]
                                (cond (<= (+ xs xc) a) [[a b]]
                                      (>= xs b) [[a b]]
                                      :else (concat (when (< a xs) [[a xs]])
                                                    (when (> b (+ xs xc)) [[(+ xs xc) b]])))))
                      rs)
                (rest es))
         (into [] (comp (filter (fn [[a b]] (> b a)))
                        (map (fn [[a b]] [a (- b a)])))
               rs)))))

#?(:clj
   (defn- run-segment-worker!
     "One worker of a materialization run: claim a segment,
      build the pieces of it nothing has covered, note each as
      an extent; repeat. Even workers take the lowest unbuilt
      segment, odd workers the highest — the file fills in
      from both ends at once, so the end a reader scrolls to
      is a finished document early and stays one. A segment is
      claimed by one worker at a time (segment-claims names
      its owner); a build that slips is retried on a later
      pass, by whichever worker comes next. The run ends for
      the worker when the partial entry leaves or changes id.
      There is no other reader: if every worker stops, the
      load stops with it — the assembler cannot invent the
      pieces it is missing."
     [ed path load-id n]
     (let [scratch (segment-scratch ed n)]
       (loop []
         (let [run? (try
                      (let [entry ($ (get-in @(:atom (:store ed)) [:loading path]))]
                        (and entry (= load-id (:id entry))))
                      (catch #?(:cljs :default :default Throwable) _ false))]
           (when run?
             (try
               (let [scan (scan-of path)
                     spans (when scan (segment-spans scan))
                     nxc (dec (frontier-line ed path))
                     claims (get @segment-claims path {})
                     exts (extent-ranges ed path)
                     ranges (into []
                                  (mapcat (fn [[s cnt]]
                                            (let [owner (get claims s)]
                                              (if (and owner (not= owner n))
                                                []
                                                (uncovered-ranges exts nxc s cnt)))))
                                  spans)
                     pick (if (even? n) (first ranges) (last ranges))]
                 (if pick
                   (let [span-start (loop [ss spans]
                                      (let [[s cnt] (first ss)]
                                        (if (and (<= s (first pick))
                                                 (< (first pick) (+ s cnt)))
                                          s
                                          (recur (rest ss)))))]
                     (swap! segment-claims assoc-in [path span-start] n)
                     (let [w (text/file-window path scan (first pick) (second pick))
                           lines (:lines w)]
                       (when (and (= load-id ($ (get-in @(:atom (:store ed)) [:loading path :id])))
                                  (= (clojure.core/count lines) (second pick))
                                  (not (:eof? w)))
                         (note-extent-doc!
                          ed path (first pick)
                          {:doc (swap! scratch (fn [_] (extent-doc-for lines)))
                           :chars (reduce + (map (fn [l] (inc (count (:text l)))) lines))
                           :lines (count lines)
                           :start-char (:start (first lines))
                           :start-stream (:stream-start w)
                           :stream-chars (- (:stream-end w) (:stream-start w))
                           :src :build}))))
                   (Thread/sleep 250)))
               (catch #?(:cljs :default :default Throwable) t
                 (binding [*out* *err*]
                   (println "narcis: segment worker" n "for" path
                            "slipped:" (ex-message t)))
                 (Thread/sleep 250)))
             (recur)))))))

#?(:clj
   (defn- segment-worker-count
     "How many segment workers a run spawns: the machine's
      processors less one, held to [1, 3] — the measured shape
      (four builders saturated the box the parallel-build probe
      ran on; more threads than that is contention, not speed).
      NARCIS_SEGMENT_WORKERS overrides it — the A/B measurement
      knob. There is no zero: the workers are the only readers
      a load has."
     []
     (if-let [v (System/getenv "NARCIS_SEGMENT_WORKERS")]
       (max 1 (Integer/parseInt v))
       (try (max 1 (min 3 (dec (.availableProcessors (java.lang.Runtime/getRuntime)))))
            (catch #?(:cljs :default :default Throwable) _ 3)))))

#?(:clj
   (defn- start-segment-workers!
     [ed path load-id]
     (dotimes [n (segment-worker-count)]
       (doto (Thread. ^Runnable (fn [] (run-segment-worker! ed path load-id n)))
         (.setDaemon true)
         (.start)))))

(defonce ^:private parked-tails (atom {}))

(defn parked-tail-band
  "Path's parked tail as a byte band [start-byte total-bytes],
   for the load line: the file's end, built once and unmoving
   while the rest of the file loads. Nil when no tail is
   parked for path."
  [path]
  (when-let [t (get @parked-tails path)]
    [(:start-byte t) (text/file-length path)]))

#?(:clj
   (defn- run-tail-build!
     "Build path's tail — its last segment's worth of bytes —
      by position (text/file-tail), before any index reaches
      it, and park it host-side. The assembler notes it as the
      final extent once the scan knows the file's totals. The
      end a reader scrolls to is then a finished document from
      the load's first seconds, and what it holds cannot
      change: it is the file's own end, read once."
     [ed path load-id]
     (try
       (let [t (text/file-tail path (* segment-chunks 1048576))]
         (when (and (pos? (count (:text t)))
                    (= load-id ($ (get-in @(:atom (:store ed)) [:loading path :id]))))
           (let [doc (swap! (segment-scratch ed "tail")
                            (fn [_] (text/from-string (:text t))))]
             (swap! parked-tails assoc path
                    {:doc doc
                     :chars (text/chars doc)
                     :breaks (dec (text/lines doc))
                     :stream-chars (:units (text/chunk-stats (:text t)))
                     :start-byte (:start-byte t)}))))
       (catch #?(:cljs :default :default Throwable) t
         (binding [*out* *err*]
           (println "narcis: tail build for" path "stopped:" (ex-message t)))))))

(defn- scan-stream-total
  "The file's whole length in stream characters, from a
   finished scan's chunk table (the units column summed)."
  [scan]
  (reduce + 0 (map (fn [[_ _ _ u]] u) (:chunks @scan))))

#?(:clj
   (defn- note-parked-tail!
     "When path's scan has finished and a tail is parked, note
      it as the extent at the file's last lines: its start
      falls out of the scan's totals less its own counts. The
      tail's span is the file's last stretch, and no segment
      span reaches it — so the tail must land even where a
      view's or a worker's extent already covers part of it:
      it is noted in the gaps between the extents the map
      holds, each gap's document sliced out of the parked
      one by reference, its stream span read off a
      file-window over the same lines. (Noting it whole, as
      one extent, lost it whole whenever anything overlapped
      — and with it the only builder of its uncovered
      stretches: the load stalled with the territory between
      the last segment and the covering extent unbuilt.)"
     [ed path]
     (let [scan (scan-of path)]
       (when (and scan (:done? @scan) (get @parked-tails path)
                  (partial? ed path))
         (let [t (get @parked-tails path)
               start (- (dec (:lines @scan)) (:breaks t))
               total-stream (scan-stream-total scan)
               tchar (- (:chars @scan) (:chars t))
               gaps (uncovered-ranges (extent-ranges ed path)
                                      (dec (frontier-line ed path))
                                      start (:breaks t))]
           (doseq [[g cnt] gaps]
             (let [l0 (- g start)
                   head-off (text/line-start-offset (:doc t) l0)
                   ;; the slicing is rope surgery — it builds
                   ;; Eve data, so it runs inside a swap on a
                   ;; scratch atom, the domain context its
                   ;; document was built in. The scratch is NOT
                   ;; the tail's own: the parked document stays
                   ;; rooted on the tail scratch for the whole
                   ;; noting, because every later gap still
                   ;; reads it — slicing on the tail scratch
                   ;; replaces that root with the first gap's
                   ;; slice, leaving the parked document rooted
                   ;; nowhere while the noting continues, and
                   ;; the domain's deferred frees may then
                   ;; reuse its blocks under the reads (the
                   ;; assembly died exactly there: a node slot
                   ;; reread as a foreign tag)
                   doc (swap! (segment-scratch ed "tail-slice")
                              (fn [_]
                                (let [d1 (if (zero? l0)
                                           (:doc t)
                                           (text/replace (:doc t) 0 head-off ""))]
                                  ;; a gap that runs to the
                                  ;; tail's end keeps the doc's
                                  ;; last, partial line; a gap
                                  ;; that stops inside the tail
                                  ;; is trimmed at its end
                                  ;; line's start
                                  (if (= (+ g cnt) (+ start (:breaks t)))
                                    d1
                                    (text/replace d1 (text/line-start-offset d1 cnt)
                                                  (text/chars d1) "")))))
                   w (text/file-window path scan g cnt)]
               (when (= cnt (count (:lines w)))
                 (note-extent-doc! ed path g
                                   {:doc doc
                                    :chars (text/chars doc)
                                    :lines cnt
                                    :src :build
                                    :start-char (+ tchar head-off)
                                    :start-stream (:stream-start w)
                                    ;; a gap running to the
                                    ;; tail's end owns the
                                    ;; file's last, partial
                                    ;; line too — a window's
                                    ;; stream end stops at its
                                    ;; last break, so the end
                                    ;; gap's span runs to the
                                    ;; file's stream total
                                    :stream-chars (if (= (+ g cnt)
                                                         (+ start (:breaks t)))
                                                    (- total-stream
                                                       (:stream-start w))
                                                    (- (:stream-end w)
                                                       (:stream-start w)))}))))
           (swap! parked-tails dissoc path))))))

#?(:clj
   (defn- assemble!
     "One assembly pass over path's extents: append to the
      root every extent the root's standing line has reached —
      whole when the root stands at its start, its remainder
      when the root stands inside it — and drop the ones the
      root has passed. All of it is one swap, however many
      pieces are ready, and every append is by reference. When
      the scan is done, no extents remain, and the root has
      consumed the file to its last stream character, the
      buffer is whole: the loading and extents entries go in
      the same swap. (The test is in the file's stream
      characters, not the root's lines: an edited extent's
      document legitimately holds more or fewer lines than
      the file's span did, so line counts no longer meet at
      the end — the stream position, which edits never move,
      is what says the file is all in.) A pass with nothing
      to do does not swap at all."
     [ed path load-id]
     (let [scan (scan-of path)
           exts (extents ed path)
           doc-lines (frontier-line ed path)
           loaded0 (or ($ (get-in @(:atom (:store ed)) [:loading path :loaded-chars])) 0)
           total-stream (when (and scan (:done? @scan))
                          (scan-stream-total scan))
           work? (or (and (seq exts)
                          (<= (apply min (keys exts)) (dec doc-lines)))
                     (and total-stream (empty? exts)
                          (= loaded0 total-stream)))]
       (when work?
         (swap! (app-atom ed)
                (fn [app]
                  (if (and (get-in app [:buffers path])
                           (= load-id (get-in app [:loading path :id])))
                    (let [b (get-in app [:buffers path])
                          [root loaded exts dlines dchars]
                          (loop [root (-root b)
                                 loaded (get-in app [:loading path :loaded-chars])
                                 exts (get-in app [:extents path] {})
                                 dlines 0 dchars 0]
                            ;; nxc is the line the root stands
                            ;; at: text/lines counts the
                            ;; in-progress tail line too, so the
                            ;; fully consumed lines are
                            ;; (dec (text/lines root)) and line
                            ;; nxc is present at most in part.
                            ;; Splicing from the raw line count
                            ;; skipped the seam line whole
                            ;; whenever the root stood at its
                            ;; start (the parallel loader's
                            ;; first document diff).
                            (let [[s e] (first (sort-by key exts))
                                  nxc (dec (text/lines root))]
                              (if (or (nil? e) (> s nxc))
                                [root loaded exts dlines dchars]
                                (cond
                                  ;; the root has it already
                                  (<= (+ s (:lines e)) nxc)
                                  (recur root loaded (dissoc exts s) dlines dchars)

                                  ;; an extent whose start does
                                  ;; not sit where the file says
                                  ;; is stale
                                  (not= (text/line-start-offset root s)
                                        (:start-char e))
                                  (recur root loaded (dissoc exts s) dlines dchars)

                                  :else
                                  (let [edoc (:doc e)
                                        ;; the extent's part from
                                        ;; the standing line on, as
                                        ;; a rope: replacing the
                                        ;; head away keeps the rest
                                        ;; by reference (a
                                        ;; slice-string round trip
                                        ;; rebuilt it, at rebuild
                                        ;; prices, inside the swap)
                                        part (if (= nxc s)
                                               edoc
                                               (text/replace edoc 0
                                                             (text/line-start-offset edoc (- nxc s))
                                                             ""))
                                        ;; replace whatever of
                                        ;; line nxc the root holds
                                        ;; (a partial line, or
                                        ;; nothing) with the
                                        ;; extent's whole line and
                                        ;; rest
                                        root (text/replace root
                                                           (text/line-start-offset root nxc)
                                                           (text/chars root) part)]
                                    ;; the absorbed extent's
                                    ;; delta joins the entry's
                                    ;; frame account: the root
                                    ;; now carries its edit, and
                                    ;; later notings translate
                                    ;; past it
                                    (recur root
                                           (+ (:start-stream e) (:stream-chars e))
                                           (dissoc exts s)
                                           (+ dlines (- (:lines e) (file-lines-of e)))
                                           (+ dchars (- (:chars e) (file-chars-of e)))))))))]
                      (if (and scan (:done? @scan)
                               (empty? exts)
                               (= loaded total-stream))
                        (-> app
                            (assoc-in [:buffers path]
                                      (with-state b {:root root}))
                            (update :loading dissoc path)
                            (update :extents dissoc path))
                        (-> app
                            (assoc-in [:buffers path]
                                      (with-state b {:root root}))
                            (assoc-in [:extents path] exts)
                            (assoc-in [:loading path :loaded-chars] loaded)
                            (assoc-in [:loading path :line-delta]
                                      (+ (or (get-in app [:loading path :line-delta]) 0)
                                         dlines))
                            (assoc-in [:loading path :char-delta]
                                      (+ (or (get-in app [:loading path :char-delta]) 0)
                                         dchars)))))
                    app)))))))

#?(:clj
   (defn- run-assembler!
     "Path's assembly thread: note the parked tail when the
      scan lands, and keep appending the extents the root has
      reached until the buffer is whole or the run's entry
      leaves. It reads nothing — the workers bring the file;
      this thread only puts finished pieces onto the rope.
      Its finally owns the run's cleanup: the materializing
      mark, the claims, the parked tail, and the scan (kept
      while the buffer stays partial, so a resume reuses it)."
     [ed path load-id]
     (try
       (loop []
         (let [entry ($ (get-in @(:atom (:store ed)) [:loading path]))]
           (when (and entry (= load-id (:id entry)))
             (note-parked-tail! ed path)
             (assemble! ed path load-id)
             (Thread/sleep 100)
             (recur))))
       (catch #?(:cljs :default :default Throwable) t
         (binding [*out* *err*]
           (println "narcis: assembly of" path "stopped:" (ex-message t))))
       (finally
         (swap! materializing disj path)
         (swap! segment-claims dissoc path)
         (swap! parked-tails dissoc path)
         (when-not (partial? ed path)
           (stop-scan! path))))))

#?(:clj
   (defn- start-load!
     "Spawn path's materialization: segment workers building
      the file in from both ends, a tail build that takes the
      file's end by position at once, and the assembler that
      puts finished pieces onto the rope. There is no
      sequential reader: every byte of the file is read once,
      by the one worker that builds its piece."
     [ed path load-id]
     (start-segment-workers! ed path load-id)
     (doto (Thread. ^Runnable (fn [] (run-tail-build! ed path load-id)))
       (.setDaemon true)
       (.start))
     (doto (Thread. ^Runnable (fn [] (run-assembler! ed path load-id)))
       (.setDaemon true)
       (.start))))

#?(:clj
   (defn materialize!
     "Start materializing path's whole document (see the section
      note): segment workers build the file in from both ends
      and the assembler puts the pieces onto the rope, until
      the document is the file. Answers true when the buffer is
      partial — a run already going counts; false when it is
      whole and there was nothing to start."
     [ed path]
     (if (partial? ed path)
       (do (when-not (materializing? path)
             (let [entry ($ (get-in @(:atom (:store ed)) [:loading path]))
                   id (claim-partial! ed path (:loaded-chars entry) (:total-bytes entry))]
               (swap! materializing conj path)
               (start-load! ed path id)))
           true)
       false)))

#?(:clj
   (defn resume-loads!
     "At boot: every buffer the store holds partial — a process that
      died or was closed mid-load — goes on loading in the same mode,
      where it stood: its scan restarts and its materialization
      resumes (the rope and the extents already built are kept), when
      its file is unchanged in size. A file whose size changed is left
      for its next open, which restarts it from the head. Answers the
      paths resumed. (Before this a restored partial tab sat with no
      load and no scan until its file was opened again by hand.)"
     [ed]
     (let [entries (or ($ (into {} (map (fn [[p l]] [p (:total-bytes l)]))
                               (get @(:atom (:store ed)) :loading)))
                       {})]
       (into []
             (keep (fn [[p total]]
                     (when (and (string? p)
                                (not (materializing? p))
                                (.exists (java.io.File. ^String p))
                                (= total (text/file-length p)))
                       (start-scan! p)
                       (materialize! ed p)
                       p)))
             entries))))

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
           (start-load! ed path id)
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
