(ns narcis.buffer
  "Editor/buffer state in the one disk atom, via narcis.store.

   The whole editor state is ONE store value (buffer name \"editor\"),
   so it survives process restart by construction — reopening the store
   by name and reading the value back is the entire recovery story:

     {:buffers {<path> {:root    <rope root>
                        :cursor  <char offset>
                        :sel     [<start> <end>] or nil
                        :undo    [<rope root> ...]   ; oldest first
                        :redo    [<rope root> ...]}}
      :tabs    [<path> ...]     ; tab order
      :active  <path> or nil}

   Undo history is prior ROPE ROOTS: undo is a root swap, not an
   inverse edit, and because roots are persistent values in the disk
   atom, the history itself is durable — undo/redo work across a
   process restart exactly as in-process.

   An editor handle is {:store store :state (atom state)}; every
   mutation persists the new state through the store before returning."
  (:require [narcis.rope :as r]
            [narcis.store :as store]))

(def editor-name "editor")
(def empty-state {:buffers {} :tabs [] :active nil})

(defn open-editor
  "Open the editor state held in store (created empty on first use)."
  [store]
  {:store store
   :state (atom (or (store/current-buffer store editor-name) empty-state))})

(defn- persist! [ed]
  (store/put-buffer! (:store ed) editor-name @(:state ed))
  ed)

(defn state [ed] @(:state ed))

(defn buffer [ed path] (get-in @(:state ed) [:buffers path]))
(defn buffer-root [ed path] (:root (buffer ed path)))
(defn buffer-text [ed path] (r/to-string (buffer-root ed path)))
(defn cursor [ed path] (:cursor (buffer ed path)))
(defn selection [ed path] (:sel (buffer ed path)))
(defn tabs [ed] (:tabs @(:state ed)))
(defn active [ed] (:active @(:state ed)))
(defn undo-depth [ed path] (count (:undo (buffer ed path))))
(defn redo-depth [ed path] (count (:redo (buffer ed path))))

(defn open-buffer!
  "Open path with rope root (or text string) as its content. Opening an
   already-open path just activates it. New buffers start with an empty
   undo history — the opened root is version zero."
  [ed path root-or-text]
  (if (buffer ed path)
    (do (swap! (:state ed) assoc :active path) (persist! ed))
    (let [root (if (string? root-or-text) (r/from-string root-or-text) root-or-text)]
      (swap! (:state ed)
             (fn [s] (-> s
                         (assoc-in [:buffers path]
                                   {:root root :cursor 0 :sel nil :undo [] :redo []})
                         (update :tabs conj path)
                         (assoc :active path))))
      (persist! ed))))

(defn open-file!
  "Open a file from disk: its text is streamed into a rope
   (narcis.rope/ingest-file — the file is never held whole)."
  [ed path]
  (open-buffer! ed path (r/ingest-file path)))

(defn close-buffer! [ed path]
  (swap! (:state ed)
         (fn [s] (let [tabs (vec (remove #(= % path) (:tabs s)))]
                   (-> s
                       (update :buffers dissoc path)
                       (assoc :tabs tabs)
                       (assoc :active (if (= path (:active s))
                                        (peek tabs)
                                        (:active s)))))))
  (persist! ed))

(defn activate! [ed path]
  (swap! (:state ed) assoc :active path)
  (persist! ed))

(defn set-cursor! [ed path offset]
  (swap! (:state ed) assoc-in [:buffers path :cursor] offset)
  (persist! ed))

(defn set-selection! [ed path sel]
  (swap! (:state ed) assoc-in [:buffers path :sel] sel)
  (persist! ed))

(defn edit-buffer!
  "Replace path's root with new-root as one undoable edit: the prior
   root is pushed onto :undo and :redo is cleared. Cursor moves to
   new-cursor when given."
  ([ed path new-root] (edit-buffer! ed path new-root nil))
  ([ed path new-root new-cursor]
   (swap! (:state ed)
          (fn [s] (let [b (get-in s [:buffers path])]
                    (assoc-in s [:buffers path]
                              (cond-> (assoc b :root new-root
                                               :undo (conj (:undo b) (:root b))
                                               :redo [])
                                (some? new-cursor) (assoc :cursor new-cursor))))))
   (persist! ed)))

(defn insert-text!
  "Insert s at char offset in path's buffer, as one undoable edit."
  [ed path offset s]
  (edit-buffer! ed path (r/insert (buffer-root ed path) offset s) (+ offset (count s))))

(defn delete-range!
  "Delete chars [start end) in path's buffer, as one undoable edit."
  [ed path start end]
  (edit-buffer! ed path (r/delete (buffer-root ed path) start end) start))

(defn undo!
  "Step path's buffer back one root. Returns true when a step was
   taken. The current root moves onto :redo."
  [ed path]
  (let [b (buffer ed path)]
    (if (empty? (:undo b))
      false
      (do (swap! (:state ed)
                 (fn [s] (let [b (get-in s [:buffers path])]
                           (assoc-in s [:buffers path]
                                     (assoc b :root (peek (:undo b))
                                              :undo (pop (:undo b))
                                              :redo (conj (:redo b) (:root b)))))))
          (persist! ed)
          true))))

(defn redo!
  "Step path's buffer forward one root (inverse of undo!)."
  [ed path]
  (let [b (buffer ed path)]
    (if (empty? (:redo b))
      false
      (do (swap! (:state ed)
                 (fn [s] (let [b (get-in s [:buffers path])]
                           (assoc-in s [:buffers path]
                                     (assoc b :root (peek (:redo b))
                                              :redo (pop (:redo b))
                                              :undo (conj (:undo b) (:root b)))))))
          (persist! ed)
          true))))
