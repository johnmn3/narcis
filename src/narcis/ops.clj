(ns narcis.ops
  "Editor ops over narcis.buffer: the one dispatch the HTTP server
   (narcis.launcher) and the headless gate tests both drive, so what
   the browser does and what the gates measure are the same code.

   Every op takes the editor handle and a message map
   {:op kw :path p ...} and returns
   {:ok true :caret n :sel [a b]|nil :undo n :redo n :rev n} or
   {:ok false :error \"...\"}. Text edits go through narcis.edit's
   rope ops and land as ONE new root per op (one undo step) via
   narcis.buffer/edit-buffer!; caret/selection moves persist through
   buffer's setters. Nothing here knows about HTTP or the page."
  (:require [narcis.buffer :as buffer]
            [narcis.edit :as edit]
            [narcis.nav :as nav]
            [narcis.rope :as rope]
            [narcis.structural :as structural]
            [narcis.theme :as theme]
            [narcis.view :as view]
            [clojure.string :as str]))

(defn- result [ed path]
  {:ok true
   :caret (buffer/cursor ed path)
   :sel (buffer/selection ed path)
   :undo (buffer/undo-depth ed path)
   :redo (buffer/redo-depth ed path)
   :rev (buffer/rev ed)})

(defn- commit!
  "Land new-root as one undoable edit at new-caret/new-sel."
  [ed path new-root new-caret new-sel]
  (let [old-sel (buffer/selection ed path)]
    (buffer/edit-buffer! ed path new-root new-caret)
    (when (not= old-sel new-sel)
      (buffer/set-selection! ed path new-sel))
    (result ed path)))

(defn- plain-insert [ed path s]
  (let [root (buffer/buffer-root ed path)
        caret (buffer/cursor ed path)
        sel (buffer/selection ed path)]
    (if (and sel (< (first sel) (second sel)))
      (let [[a b] sel]
        (commit! ed path (rope/insert (rope/delete root a b) a s) (+ a (count s)) nil))
      (commit! ed path (rope/insert root caret s) (+ caret (count s)) nil))))

(def ^:private open-chars #{\( \[ \{ \"})
(def ^:private close-chars #{\) \] \}})

(defn- type-char [ed path ch]
  (let [sel (buffer/selection ed path)
        caret (buffer/cursor ed path)
        root (buffer/buffer-root ed path)]
    (cond
      (and sel (< (first sel) (second sel)))
      (if (contains? open-chars ch)
        (let [res (edit/insert-open-rope root caret sel ch)]
          (if res
            (commit! ed path (:rope res) (:caret res) (:sel res))
            (plain-insert ed path (str ch))))
        (plain-insert ed path (str ch)))

      (contains? open-chars ch)
      (let [res (edit/insert-open-rope root caret nil ch)]
        (if res
          (commit! ed path (:rope res) (:caret res) (:sel res))
          (plain-insert ed path (str ch))))

      (contains? close-chars ch)
      (let [res (edit/insert-close-rope root caret nil ch)]
        (if res
          (commit! ed path (:rope res) (:caret res) (:sel res))
          (plain-insert ed path (str ch))))

      :else (plain-insert ed path (str ch)))))

(defn- delete-selection-or [ed path f]
  (let [sel (buffer/selection ed path)
        root (buffer/buffer-root ed path)
        caret (buffer/cursor ed path)]
    (if (and sel (< (first sel) (second sel)))
      (commit! ed path (rope/delete root (first sel) (second sel)) (first sel) nil)
      (f root caret))))

(defn- move-caret! [ed path offset sel]
  (buffer/set-cursor! ed path offset)
  (when (not= sel (buffer/selection ed path))
    (buffer/set-selection! ed path sel))
  (result ed path))

(defn- sexp-text
  "Text + base for nav around caret: the whole buffer at or under
   view/exact-limit chars; above it, a slice of ±400 lines around the
   caret line (motion clamps to the slice — named in REPORT.md)."
  [root caret]
  (if (<= (rope/chars root) view/exact-limit)
    [(rope/to-string root) 0]
    (let [line (rope/offset->line root caret)
          from (max 0 (- line 400))
          to (min (rope/lines root) (+ line 401))
          base (rope/line-start-offset root from)
          end (rope/line-start-offset root to)]
      [(rope/slice-string root base end) base])))

(declare rope-op clamp-caret structural-op structural-ops)

(defn- find-in-text
  "Index of query in text for a find from offset `from`: direction
   :next searches forward, :prev backward; wraps once when wrap?.
   Returns [start end] or nil."
  [text query from dir wrap?]
  (let [q (count query)]
    (if (zero? q)
      nil
      (case dir
        :next (or (when-let [i (str/index-of text query (min (count text) (max 0 from)))]
                    [i (+ i q)])
                  (when wrap?
                    (when-let [i (str/index-of text query)] [i (+ i q)])))
        :prev (let [from (min (count text) (max 0 from))]
                ;; lastIndexOf's start is a match-START bound: from - q
                ;; asks for the last match ending at or before from
                ;; (negative when none can — Java answers -1).
                (or (when-let [i (str/last-index-of text query (- from q))]
                      [i (+ i q)])
                    (when wrap?
                      (when-let [i (str/last-index-of text query)] [i (+ i q)]))))))))

(defn- find-op [ed path msg dir]
  (let [root (buffer/buffer-root ed path)
        query (:query msg)]
    (cond
      (or (nil? query) (zero? (count query))) {:ok false :error "empty find query"}
      (> (rope/chars root) view/exact-limit) {:ok false :error "find needs the whole text; buffer is over the exact limit"}
      :else
      (let [text (rope/to-string root)
            sel (buffer/selection ed path)
            caret (buffer/cursor ed path)
            from (or (:from msg)
                     (case dir :next caret :prev (if sel (first sel) caret)))
            m (find-in-text text query from dir (not (false? (:wrap msg))))]
        (if m
          (assoc (move-caret! ed path (second m) m) :match m)
          {:ok false :error (str "not found: " query)})))))

(defn apply-op
  "Apply one op message to the editor. See the ns docstring for the
   result shape. Unknown ops and inapplicable structural ops answer
   {:ok false :error ...} rather than throwing, except programming
   errors, which propagate."
  [ed {:keys [op path] :as msg}]
  (let [path (or path (buffer/active ed))]
    (if (and (not (#{:open :activate :new :theme/use} op)) (nil? (buffer/buffer ed path)))
      {:ok false :error (str "no such buffer: " path)}
      (do
        ;; A message may carry the client's current :caret/:sel (the
        ;; page moves the caret locally for responsiveness and syncs
        ;; it with the next op, so an edit always lands where the user
        ;; sees the caret). Sync first, then apply.
        (when (and (contains? msg :caret) (not= op :set-caret)
                   (buffer/buffer ed path))
          (move-caret! ed path
                       (max 0 (min (:caret msg) (rope/chars (buffer/buffer-root ed path))))
                       (:sel msg)))
        (case op
        :open (do (buffer/open-file! ed (:path msg)) (result ed (:path msg)))
        :activate (do (buffer/activate! ed path) (result ed path))
        :close (do (buffer/close-buffer! ed path)
                   (assoc (result ed (buffer/active ed)) :path (buffer/active ed)))
        :new (let [p (first (filter #(nil? (buffer/buffer ed %))
                                    (map (fn [i] (if (zero? i) "untitled" (str "untitled-" (inc i))))
                                         (range 100))))]
               (buffer/open-buffer! ed p "")
               (assoc (result ed p) :path p))
        :theme/use (let [choice (keyword (:name msg))]
                     (if (theme/choice? choice)
                       (do (buffer/set-theme! ed choice)
                           {:ok true :theme (name choice)
                            :resolved (name (theme/resolve-choice choice (boolean (:system-dark msg))))
                            :rev (buffer/rev ed)})
                       {:ok false :error (str "unknown theme: " (:name msg))}))
        :save-as (let [np (:new-path msg)
                       root (buffer/buffer-root ed path)]
                   (cond
                     (or (nil? np) (str/blank? np)) {:ok false :error "save-as needs a new path"}
                     (= np path) {:ok false :error "save-as path is the current path"}
                     (some? (buffer/buffer ed np)) {:ok false :error (str "already open: " np)}
                     (> (rope/chars root) 50000000) {:ok false :error "save over 50M chars not supported in the spike"}
                     :else (do (spit np (rope/to-string root))
                               (buffer/open-buffer! ed np root)
                               (buffer/close-buffer! ed path)
                               (assoc (result ed np) :path np))))
        :find-next (find-op ed path msg :next)
        :find-prev (find-op ed path msg :prev)

        :insert-text (plain-insert ed path (:text msg))
        :type-char (type-char ed path (first (:ch msg)))
        :smart-enter (let [res (edit/smart-enter-rope (buffer/buffer-root ed path)
                                                      (buffer/cursor ed path)
                                                      (buffer/selection ed path))]
                       (if res
                         (commit! ed path (:rope res) (:caret res) (:sel res))
                         {:ok false :error "smart-enter does not apply"}))
        :indent (rope-op ed path edit/indent-rope)
        :unindent (rope-op ed path edit/unindent-rope)
        :toggle-comment (rope-op ed path edit/toggle-comment-rope)
        :backspace (delete-selection-or
                    ed path
                    (fn [root caret]
                      (let [res (edit/backspace-pair-rope root caret nil)]
                        (cond res (commit! ed path (:rope res) (:caret res) (:sel res))
                              (pos? caret) (commit! ed path (rope/delete root (dec caret) caret) (dec caret) nil)
                              :else {:ok false :error "start of buffer"}))))
        :delete-forward (delete-selection-or
                         ed path
                         (fn [root caret]
                           (if (< caret (rope/chars root))
                             (commit! ed path (rope/delete root caret (inc caret)) caret nil)
                             {:ok false :error "end of buffer"})))
        :forward-sexp (let [root (buffer/buffer-root ed path)
                            caret (buffer/cursor ed path)
                            [text base] (sexp-text root caret)]
                        (move-caret! ed path (+ base (nav/forward-sexp text (- caret base))) nil))
        :backward-sexp (let [root (buffer/buffer-root ed path)
                             caret (buffer/cursor ed path)
                             [text base] (sexp-text root caret)]
                         (move-caret! ed path (+ base (nav/backward-sexp text (- caret base))) nil))
        :undo (if (buffer/undo! ed path)
                (clamp-caret (result ed path) ed path)
                {:ok false :error "nothing to undo"})
        :redo (if (buffer/redo! ed path)
                (clamp-caret (result ed path) ed path)
                {:ok false :error "nothing to redo"})
        :set-caret (move-caret! ed path
                                (max 0 (min (:offset msg) (rope/chars (buffer/buffer-root ed path))))
                                (:sel msg))
        :rename (let [root (buffer/buffer-root ed path)]
                  (if (> (rope/chars root) view/exact-limit)
                    {:ok false :error "rename needs the whole text; buffer is over the exact limit"}
                    (let [res (edit/rename-rope root (buffer/cursor ed path)
                                                (buffer/selection ed path) (:name msg))]
                      (if res
                        (commit! ed path (:rope res) (:caret res) (:sel res))
                        {:ok false :error "no symbol to rename there"}))))
        :save (let [root (buffer/buffer-root ed path)]
                (if (> (rope/chars root) 50000000)
                  {:ok false :error "save over 50M chars not supported in the spike"}
                  (do (spit path (rope/to-string root)) (result ed path))))
        (if (contains? structural-ops op)
          (structural-op ed path (structural-ops op))
          {:ok false :error (str "unknown op: " op)}))))))

(defn- clamp-caret [res ed path]
  (let [n (rope/chars (buffer/buffer-root ed path))
        c (buffer/cursor ed path)]
    (when (> c n) (buffer/set-cursor! ed path n))
    (assoc res :caret (min c n))))

(defn- rope-op [ed path f]
  (let [res (f (buffer/buffer-root ed path) (buffer/cursor ed path) (buffer/selection ed path))]
    (if res
      (commit! ed path (:rope res) (:caret res) (:sel res))
      {:ok false :error "op does not apply"})))

;; ---------------------------------------------------------------------------
;; structural (paredit) ops — narcis.structural's snapshot fns, driven
;; over sexp-text like the nav ops: the whole buffer under the exact
;; limit, else the ±400-line window (motion clamps to the slice).

(def structural-ops
  {:slurp-forward structural/slurp-forward
   :barf-forward structural/barf-forward
   :slurp-backward structural/slurp-backward
   :barf-backward structural/barf-backward
   :raise structural/raise
   :splice structural/splice
   :wrap-round structural/wrap-round
   :wrap-square structural/wrap-square
   :wrap-curly structural/wrap-curly
   :wrap-set structural/wrap-set
   :wrap-quote structural/wrap-quote
   :copy-form structural/copy-form
   :kill-form structural/kill-form
   :kill-form-backward structural/kill-form-backward
   :forward-form structural/forward
   :backward-form structural/backward
   :up structural/up
   :down structural/down
   :forward-up structural/forward-up
   :backward-down structural/backward-down
   :list-end structural/list-end
   :list-start structural/list-start
   :expand-selection structural/expand-selection
   :shrink-selection structural/shrink-selection
   :select-forward structural/select-forward
   :select-backward structural/select-backward
   :drag-forward structural/drag-forward
   :drag-backward structural/drag-backward
   :transpose structural/transpose
   :join structural/join
   :split structural/split
   :kill-list-forward structural/kill-list-forward
   :kill-list-backward structural/kill-list-backward
   :kill-right structural/kill-right
   :kill-left structural/kill-left
   :splice-killing-forward structural/splice-killing-forward
   :splice-killing-backward structural/splice-killing-backward
   :convolute structural/convolute
   :rewrap-round structural/rewrap-round
   :rewrap-square structural/rewrap-square
   :rewrap-curly structural/rewrap-curly
   :rewrap-set structural/rewrap-set})

(defn- structural-op [ed path f]
  (let [root (buffer/buffer-root ed path)
        caret (buffer/cursor ed path)
        sel (buffer/selection ed path)
        [text base] (sexp-text root caret)
        snapshot {:text text
                  :caret (- caret base)
                  :sel-start (if sel (- (first sel) base) (- caret base))
                  :sel-end (if sel (- (second sel) base) (- caret base))}
        res (f snapshot)]
    (if (nil? res)
      {:ok false :error "does not apply here"}
      (let [;; copy-form answers {:copy} alone: no caret, no edits.
            new-caret (+ base (or (:caret res) (- caret base)))
            new-sel (when (:sel res)
                      [(+ base (first (:sel res))) (+ base (second (:sel res)))])
            edits (:edits res)
            out (if (seq edits)
                  (let [new-root (reduce (fn [r [s e rep]]
                                           (let [r (rope/delete r (+ base s) (+ base e))]
                                             (if (seq rep)
                                               (rope/insert r (+ base s) rep)
                                               r)))
                                         root
                                         (reverse edits))]
                    (commit! ed path new-root new-caret new-sel))
                  (move-caret! ed path new-caret new-sel))]
        (if (:copy res)
          (assoc out :copy (:copy res))
          out)))))
