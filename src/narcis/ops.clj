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

(declare rope-op clamp-caret)

(defn apply-op
  "Apply one op message to the editor. See the ns docstring for the
   result shape. Unknown ops and inapplicable structural ops answer
   {:ok false :error ...} rather than throwing, except programming
   errors, which propagate."
  [ed {:keys [op path] :as msg}]
  (let [path (or path (buffer/active ed))]
    (if (and (not (#{:open :activate} op)) (nil? (buffer/buffer ed path)))
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
        {:ok false :error (str "unknown op: " op)})))))

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
