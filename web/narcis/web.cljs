(ns narcis.web
  "The Narcis editor client (S3): jonquil's editor surface in the
   browser. The page is a viewport onto the rope in the Eve disk
   atom — it fetches window payloads (lines + paint spans) from the
   launcher's /api/window, renders only those lines, and sends every
   edit back as a narcis.ops message (/api/op). It never holds the
   buffer text: the only text here is the current window's lines,
   which are rendering state, replaced on every fetch.

   Keys follow jonquil's keymap (src/jonquil/keymap.edn) for the
   commands S2 ported: Cmd/Ctrl+/ or ; toggle-comment, Cmd/Ctrl+S
   save, Cmd/Ctrl+Y and Cmd/Ctrl+Shift+Z redo, F2 / Shift+F6 rename,
   Enter smart-enter, Tab/Shift+Tab indent/unindent (jonquil's indent
   semantics), auto-pair on brackets/quotes via the server ops.
   Paredit motion (forward/backward-sexp) is on Ctrl+Alt+arrows.
   Theme is jonquil's, as data, from /api/theme; Cmd/Ctrl+Shift+M
   cycles it (jonquil's theme-cycle chord)."
  (:require [cljs.reader :as reader]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; state — rendering state only; the buffer itself lives in the atom

(defonce state
  (atom {:path nil
         :win nil            ; last /api/window payload
         :caret 0
         :sel nil            ; [a b] or nil
         :goal-col nil
         :theme nil          ; /api/theme payload {:theme {...} :styles {...}}
         :theme-name "light"
         :tabs []
         :status ""
         :fetching false
         :want-start nil
         :sync-timer nil
         :drag-anchor nil
         :focused false}))

(def font "13px ui-monospace, SFMono-Regular, Menlo, Consolas, monospace")
(def line-h 19)
(def overscan 45)

(def theme-cycle
  ;; jonquil.theme/choice-names, minus :auto (no system probe here)
  ["light" "dark" "solarized" "navy" "gruvbox-dark" "gruvbox-light"
   "dracula" "nord" "zenburn" "monokai" "tokyo-night" "catppuccin-mocha"
   "catppuccin-latte" "one-dark" "kanagawa" "rose-pine"])

;; ---------------------------------------------------------------------------
;; DOM helpers

(defn el [tag css text]
  (let [e (.createElement js/document tag)]
    (when css (set! (.-cssText (.-style e)) css))
    (when (some? text) (set! (.-textContent e) text))
    e))

(defn by-id [id] (.getElementById js/document id))

(defn char-w []
  (let [cv (.createElement js/document "canvas")
        ctx (.getContext cv "2d")]
    (set! (.-font ctx) font)
    (/ (.-width (.measureText ctx "xxxxxxxxxx")) 10)))

(defonce metrics (atom {:char-w 8 :gutter-w 56}))

(declare render! update-status!)

(defn gutter-w [total-lines]
  (+ 20 (* (:char-w @metrics) (max 3 (count (str total-lines))))))

;; ---------------------------------------------------------------------------
;; API

(defn fetch-edn [url opts]
  (-> (js/fetch url opts)
      (.then (fn [r] (.text r)))
      (.then (fn [t] (reader/read-string t)))))

(defn get-edn [url] (fetch-edn url nil))

(defn op! [msg]
  ;; Ops on the current buffer carry the client's caret/sel (the sync
  ;; narcis.ops applies before the op). Ops that name their own :path
  ;; (:activate, :open) go as-is — another buffer's caret is not theirs.
  (let [s @state
        msg (if (contains? msg :path)
              msg
              (assoc msg :path (:path s) :caret (:caret s) :sel (:sel s)))]
    (-> (fetch-edn "/api/op" #js {:method "POST" :body (pr-str msg)})
        (.then (fn [res]
                 (when (and (:ok res) (= (:path msg) (:path @state)))
                   (swap! state assoc :caret (:caret res) :sel (:sel res)))
                 res)))))

;; ---------------------------------------------------------------------------
;; window management

(defn visible-count []
  (let [sc (by-id "scroll")]
    (max 10 (js/Math.ceil (/ (.-clientHeight sc) line-h)))))

(defn first-visible []
  (let [sc (by-id "scroll")]
    (js/Math.floor (/ (.-scrollTop sc) line-h))))

(defn caret-pos
  "[:line-idx :col :line-map] for caret in the current window, or nil."
  [win caret]
  (when win
    (first
     (keep-indexed
      (fn [i {:keys [start text] :as line}]
        (when (and (<= start caret) (<= caret (+ start (count text))))
          [i (- caret start) line]))
      (:lines win)))))

(defn fetch-window!
  "Fetch a window covering line `around`, then re-render. Never moves
   the caret (scrolling is not caret motion; ops set the caret from
   their own responses). Serialized: a fetch in flight records the
   newest want and follows up. Always returns a promise."
  [around]
  (let [s @state]
    (if (:fetching s)
      (do (swap! state assoc :want-start around)
          (js/Promise. (fn [resolve _]
                         (js/setTimeout (fn [] (resolve (:win @state))) 80))))
      (let [start (max 0 (- around overscan))
            cnt (+ (visible-count) (* 2 overscan))
            path (:path s)]
        (if path
          (do (swap! state assoc :fetching true)
              (-> (get-edn (str "/api/window?path=" (js/encodeURIComponent path)
                                "&start=" start "&count=" cnt))
                  (.then (fn [win]
                           (swap! state assoc :win win :fetching false)
                           (render!)
                           (when-let [w (:want-start @state)]
                             (swap! state assoc :want-start nil)
                             (fetch-window! w))
                           win))
                  (.catch (fn [_] (swap! state assoc :fetching false) nil))))
          (js/Promise.resolve nil))))))

(defn ensure-caret-visible! []
  (let [win (:win @state)
        pos (caret-pos win (:caret @state))]
    (when pos
      (let [line-n (:n (nth (:lines win) (first pos)))
            fv (first-visible)
            vc (visible-count)
            sc (by-id "scroll")]
        (cond
          (< line-n fv) (set! (.-scrollTop sc) (* line-n line-h))
          (>= line-n (+ fv vc)) (set! (.-scrollTop sc) (* (inc (- line-n vc)) line-h)))))))

(defn refresh-window! []
  (let [win (:win @state)
        pos (caret-pos win (:caret @state))
        around (if pos (:n (nth (:lines win) (first pos))) (or (:start-line win) 0))]
    (fetch-window! around)))

(defn after-op! [res]
  (when (:ok res)
    (ensure-caret-visible!)
    (refresh-window!)
    (update-status!)))

;; ---------------------------------------------------------------------------
;; rendering

(defn segment-style [styles kind sel? match? theme]
  (let [st (get styles kind)
        parts [(str "color:" (or (:fg st) (:text theme)))
               (when (:bold st) "font-weight:bold")
               (when (:italic st) "font-style:italic")
               (when sel? (str "background:" (:selection theme)))
               (when match? (str "background:" (:find-highlight theme)
                                 ";outline:1px solid " (:caret theme)))]]
    (str/join ";" (remove nil? parts))))

(defn line-segments
  "Elementary segments of one window line: boundaries from its paint
   spans (later spans win, like jonquil.paint/style-at), the
   selection, and the bracket-match pair."
  [{:keys [start text spans]} sel match]
  (let [len (count text)
        bounds (into #{0 len}
                     (mapcat (fn [[_ c0 c1]] [c0 c1]) spans))
        bounds (cond-> bounds
                 sel (into [(max 0 (- (first sel) start)) (min len (- (second sel) start))])
                 match (into (keep (fn [p] (let [c (- p start)]
                                             (when (and (<= 0 c) (< c len)) [c (inc c)])))
                                   match)))
        sorted (sort (filter (fn [b] (and (<= 0 b) (<= b len))) bounds))]
    (map (fn [a b]
           (let [kind (some (fn [[k c0 c1]] (when (and (<= c0 a) (<= b c1)) k))
                            (reverse spans))
                 sel? (and sel (<= (first sel) (+ start a)) (< (+ start a) (second sel)))
                 match? (and match (some (fn [p] (= (+ start a) p)) match))]
             {:a a :b b :kind kind :sel? (boolean sel?) :match? (boolean match?)
              :text (subs text a b)}))
         sorted (rest sorted))))

(defn render-lines! []
  (let [s @state
        win (:win s)
        box (by-id "win")]
    (set! (.-innerHTML box) "")
    (when win
      (let [theme (:theme (:theme s))
            styles (:styles (:theme s))
            gw (gutter-w (:total-lines win))
            caret-line (first (caret-pos win (:caret s)))]
        (set! (.-cssText (.-style box))
              (str "position:absolute;left:0;right:0;top:" (* (:start-line win) line-h) "px;"))
        (doseq [[i line] (map-indexed vector (:lines win))]
          (let [row (el "div"
                        (str "display:flex;height:" line-h "px;line-height:" line-h "px;"
                             (when (= i caret-line)
                               (str "background:" (:selection theme) "2e;")))
                        nil)
                gut (el "span"
                        (str "flex:0 0 auto;width:" gw "px;text-align:right;padding-right:12px;"
                             "box-sizing:border-box;user-select:none;color:"
                             (get-in (:theme s) [:styles "comment" :fg] (:text theme)) ";")
                        (str (inc (:n line))))
                code (el "span" "white-space:pre;" nil)]
            (doseq [seg (line-segments line (:sel s) (:match win))]
              (.appendChild code
                            (el "span"
                                (segment-style styles (:kind seg) (:sel? seg) (:match? seg) theme)
                                (:text seg))))
            (when (zero? (count (:text line)))
              (.appendChild code (el "span" "" " ")))
            (.appendChild row gut)
            (.appendChild row code)
            (.appendChild box row)))))))

(defn render-caret! []
  (let [s @state
        win (:win s)
        c (by-id "caret")
        pos (caret-pos win (:caret s))]
    (if (and pos (:focused s))
      (let [[i col line] pos
            gw (gutter-w (:total-lines win))
            x (+ gw (* col (:char-w @metrics)))
            y (* (:n line) line-h)]
        (set! (.-cssText (.-style c))
              (str "position:absolute;width:2px;height:" line-h "px;top:" y "px;left:" x
                   "px;background:" (get-in s [:theme :theme :caret])
                   ";animation:narcis-blink 1.1s steps(1) infinite;pointer-events:none;"))
        ;; park the input textarea at the caret so IME/scroll behave
        (let [ta (by-id "input")]
          (set! (.-cssText (.-style ta))
                (str "position:absolute;width:10px;height:" line-h "px;top:" y "px;left:" x
                     "px;opacity:0;border:0;padding:0;margin:0;resize:none;overflow:hidden;"
                     "background:transparent;color:transparent;outline:none;"))))
      (set! (.-cssText (.-style c)) "display:none;"))))

(defn render-tabs! []
  (let [s @state
        bar (by-id "tabs")]
    (set! (.-innerHTML bar) "")
    (doseq [p (:tabs s)]
      (let [name (last (str/split p #"/"))
            active? (= p (:path s))
            b (el "button"
                  (str "font:inherit;font-size:12px;margin-right:4px;padding:3px 10px;"
                       "border:1px solid " (get-in s [:theme :theme :selection])
                       ";border-radius:4px;cursor:pointer;"
                       (if active?
                         (str "background:" (get-in s [:theme :theme :selection])
                              ";color:" (get-in s [:theme :theme :text]))
                         (str "background:transparent;color:" (get-in s [:theme :theme :text]))))
                  name)]
        (.addEventListener b "click"
                           (fn [_]
                             (-> (op! {:op :activate :path p})
                                 (.then (fn [_]
                                          (swap! state assoc :path p)
                                          (fetch-window! (or (:start-line (:win @state)) 0))
                                          (.focus (by-id "input")))))))
        (.appendChild bar b)))))

(defn update-status! []
  (let [s @state
        win (:win s)
        pos (caret-pos win (:caret s))
        txt (if (and win pos)
              (str (:path s) "  line " (inc (:n (nth (:lines win) (first pos))))
                   ", col " (inc (second pos))
                   "  ·  " (:total-lines win) " lines, " (:total-chars win) " chars"
                   "  ·  spans " (:spans-mode win)
                   (when win (str "  ·  rev " (:rev win))))
              (str (:path s) ""))
        bar (by-id "status")]
    (when bar (set! (.-textContent bar) txt))))

(defn size-sizer! []
  (let [win (:win @state)
        sizer (by-id "sizer")]
    (when win
      (let [maxlen (reduce max 0 (map (fn [l] (count (:text l))) (:lines win)))
            w (+ (gutter-w (:total-lines win)) (* (inc maxlen) (:char-w @metrics)) 40)]
        (set! (.-cssText (.-style sizer))
              (str "position:relative;width:" w "px;height:" (* (:total-lines win) line-h) "px;"))))))

(defn render! []
  (render-tabs!)
  (render-lines!)
  (render-caret!)
  (update-status!)
  (size-sizer!))

(defn apply-theme! [payload]
  (swap! state assoc :theme payload :theme-name (name (get-in payload [:theme :name])))
  (let [t (:theme payload)
        style-el (by-id "theme-css")]
    (set! (.-textContent style-el)
          (str "body{background:" (:background t) ";color:" (:text t) ";}"
               "#hdr{border-bottom:1px solid " (:selection t) ";}"
               "@keyframes narcis-blink{0%,55%{opacity:1}56%,100%{opacity:0}}"))
    (set! (.-cssText (.-style (by-id "app")))
          (str "background:" (:background t) ";color:" (:text t) ";"))
    (render!)))

(defn cycle-theme! []
  (let [cur (:theme-name @state)
        nxt (nth theme-cycle (mod (inc (.indexOf theme-cycle cur)) (count theme-cycle)))]
    (-> (get-edn (str "/api/theme?name=" nxt))
        (.then apply-theme!))))

;; ---------------------------------------------------------------------------
;; caret motion (local; synced to the server debounced and with every op)

(defn sync-caret! []
  (when-let [t (:sync-timer @state)] (js/clearTimeout t))
  (swap! state assoc :sync-timer
         (js/setTimeout
          (fn [] (op! {:op :set-caret :offset (:caret @state) :sel (:sel @state)}))
          250)))

(defn set-caret-local! [offset sel]
  (swap! state assoc :caret offset :sel sel :goal-col nil)
  (render!)
  (sync-caret!))

(defn line-of-caret []
  (let [win (:win @state)
        pos (caret-pos win (:caret @state))]
    (when pos (:n (nth (:lines win) (first pos))))))

(defn move-lines! [n extend?]
  (let [s @state
        win (:win s)
        pos (caret-pos win (:caret s))]
    (when pos
      (let [[_ col line] pos
            goal (or (:goal-col s) col)
            target (+ (:n line) n)
            anchor (if extend? (or (first (:sel s)) (:caret s)) nil)]
        (if (and (<= (:start-line win) target)
                 (< target (+ (:start-line win) (count (:lines win)))))
          (let [tl (nth (:lines win) (- target (:start-line win)))
                off (+ (:start tl) (min goal (count (:text tl))))]
            (swap! state assoc :caret off :goal-col goal
                   :sel (when extend? [(min anchor off) (max anchor off)]))
            (ensure-caret-visible!)
            (render!)
            (sync-caret!))
          ;; target line not loaded: fetch around it, then place
          (do (swap! state assoc :goal-col goal)
              (-> (fetch-window! target)
                  (.then (fn []
                           (let [w (:win @state)
                                 tl (first (filter (fn [l] (= (:n l) target)) (:lines w)))]
                             (when tl
                               (let [off (+ (:start tl) (min goal (count (:text tl))))]
                                 (swap! state assoc :caret off
                                        :sel (when extend? [(min anchor off) (max anchor off)]))
                                 (ensure-caret-visible!)
                                 (render!)
                                 (sync-caret!)))))))))))))

(defn move-horizontal! [d extend?]
  (let [s @state
        off (max 0 (+ (:caret s) d))
        anchor (if extend? (or (first (:sel s)) (:caret s)) nil)]
    (swap! state assoc :caret off :goal-col nil
           :sel (when extend? [(min anchor off) (max anchor off)]))
    (ensure-caret-visible!)
    (render!)
    (sync-caret!)))

;; ---------------------------------------------------------------------------
;; keyboard — jonquil's keymap for the ported commands

(defn do-op! [msg]
  (-> (op! msg) (.then after-op!)))

(defn rename-ui! []
  (let [box (by-id "rename-box")
        input (by-id "rename-input")]
    (set! (.-cssText (.-style box)) "display:inline-block;")
    (set! (.-value input) "")
    (.focus input)))

(defn on-keydown [e]
  (when-not (.-isComposing e)
    (let [k (.-key e)
          cmd (or (.-metaKey e) (.-ctrlKey e))
          shift (.-shiftKey e)
          alt (.-altKey e)
          s @state]
      (cond
        ;; theme cycle: Cmd/Ctrl+Shift+M (jonquil's chord)
        (and cmd shift (or (= k "M") (= k "m")))
        (do (.preventDefault e) (cycle-theme!))

        (and cmd (or (= k "/") (= k ";"))) (do (.preventDefault e) (do-op! {:op :toggle-comment}))
        (and cmd (= k "s")) (do (.preventDefault e) (do-op! {:op :save}))
        (and cmd (= k "y")) (do (.preventDefault e) (do-op! {:op :redo}))
        (and cmd (= k "z")) (do (.preventDefault e) (do-op! {:op (if shift :redo :undo)}))
        (and (.-ctrlKey e) alt (= k "ArrowRight")) (do (.preventDefault e) (do-op! {:op :forward-sexp}))
        (and (.-ctrlKey e) alt (= k "ArrowLeft")) (do (.preventDefault e) (do-op! {:op :backward-sexp}))
        (or (= k "F2") (and (= k "F6") shift)) (do (.preventDefault e) (rename-ui!))

        (= k "Enter") (do (.preventDefault e) (do-op! {:op :smart-enter}))
        (= k "Tab") (do (.preventDefault e) (do-op! {:op (if shift :unindent :indent)}))
        (= k "Backspace") (do (.preventDefault e) (do-op! {:op :backspace}))
        (= k "Delete") (do (.preventDefault e) (do-op! {:op :delete-forward}))

        (= k "ArrowLeft") (do (.preventDefault e) (move-horizontal! -1 shift))
        (= k "ArrowRight") (do (.preventDefault e) (move-horizontal! 1 shift))
        (= k "ArrowUp") (do (.preventDefault e) (move-lines! -1 shift))
        (= k "ArrowDown") (do (.preventDefault e) (move-lines! 1 shift))
        (= k "PageUp") (do (.preventDefault e) (move-lines! (- (visible-count)) shift))
        (= k "PageDown") (do (.preventDefault e) (move-lines! (visible-count) shift))
        (= k "Home")
        (do (.preventDefault e)
            (let [win (:win s) pos (caret-pos win (:caret s))]
              (when pos
                (let [target (:start (nth (:lines win) (first pos)))
                      anchor (or (first (:sel s)) (:caret s))]
                  (set-caret-local! target
                                    (when shift [(min anchor target) (max anchor target)]))))))
        (= k "End")
        (do (.preventDefault e)
            (let [win (:win s) pos (caret-pos win (:caret s))]
              (when pos
                (let [line (nth (:lines win) (first pos))
                      target (+ (:start line) (count (:text line)))
                      anchor (or (first (:sel s)) (:caret s))]
                  (set-caret-local! target
                                    (when shift [(min anchor target) (max anchor target)]))))))

        (and (not cmd) (not alt) (= 1 (count k)))
        (do (.preventDefault e) (do-op! {:op :type-char :ch k}))

        :else nil))))

;; ---------------------------------------------------------------------------
;; mouse

(defn event-pos [e]
  (let [sc (by-id "scroll")
        rect (.getBoundingClientRect (by-id "sizer"))
        x (- (.-clientX e) (.-left rect))
        y (- (.-clientY e) (.-top rect))
        win (:win @state)
        line-n (js/Math.floor (/ y line-h))
        gw (gutter-w (:total-lines win))
        col (js/Math.max 0 (js/Math.round (/ (- x gw) (:char-w @metrics))))
        line (first (filter (fn [l] (= (:n l) line-n)) (:lines win)))]
    (when line
      (+ (:start line) (min col (count (:text line)))))))

(defn on-mousedown [e]
  (.focus (by-id "input"))
  (when-let [off (event-pos e)]
    (swap! state assoc :drag-anchor off)
    (set-caret-local! off nil)))

(defn on-mousemove [e]
  (when (:drag-anchor @state)
    (when-let [off (event-pos e)]
      (let [a (:drag-anchor @state)]
        (swap! state assoc :caret off :sel [(min a off) (max a off)])
        (render!)))))

(defn on-mouseup [_]
  (when (:drag-anchor @state)
    (swap! state assoc :drag-anchor nil)
    (sync-caret!)))

;; ---------------------------------------------------------------------------
;; init

(defn selection-text
  "The selected text, when the selection lies inside the loaded
   window (the only text the page holds); nil otherwise."
  [a b]
  (let [win (:win @state)
        lines (:lines win)]
    (when (and (seq lines)
               (<= (:start (first lines)) a)
               (<= b (+ (:start (last lines)) (count (:text (last lines))))))
      (let [sb (array)]
        (doseq [{:keys [start text]} lines]
          (let [le (+ start (count text))
                s (max a start) e (min b le)]
            (when (< s e) (.push sb (subs text (- s start) (- e start))))
            (when (and (< start b) (< a le) (< le b)) (.push sb "\n"))))
        (str/join "" sb)))))

(defn build-dom! []
  (let [app (by-id "app")]
    (set! (.-cssText (.-style app))
          (str "display:flex;flex-direction:column;height:100vh;font-family:" font ";"))
    (set! (.-innerHTML app) "")
    (let [style-el (el "style" nil nil)]
      (set! (.-id style-el) "theme-css")
      (.appendChild (.-head js/document) style-el))
    (let [hdr (el "div" "display:flex;align-items:center;gap:10px;padding:6px 10px;flex:0 0 auto;" nil)]
      (set! (.-id hdr) "hdr")
      (let [title (el "span" "font-weight:bold;font-size:14px;" "Narcis")
            tabs (el "span" "" nil)
            open-b (el "button" "font:inherit;font-size:12px;" "Open file…")
            theme-b (el "button" "font:inherit;font-size:12px;" "Theme")
            rename-b (el "button" "font:inherit;font-size:12px;" "Rename")
            rename-box (el "span" "display:none;" nil)
            rename-input (el "input" "font:inherit;font-size:12px;" nil)
            status (el "span" "font-size:12px;opacity:0.75;margin-left:auto;" "")]
        (set! (.-id tabs) "tabs")
        (set! (.-id status) "status")
        (set! (.-id rename-box) "rename-box")
        (set! (.-id rename-input) "rename-input")
        (set! (.-placeholder rename-input) "new name, Enter to rename")
        (.addEventListener open-b "click"
                           (fn [_]
                             (when-let [p (js/prompt "File path to open:")]
                               (-> (op! {:op :open :path p})
                                   (.then (fn [_]
                                            (swap! state assoc :path p)
                                            (-> (get-edn "/api/state")
                                                (.then (fn [st]
                                                         (swap! state assoc :tabs (:tabs st))
                                                         (fetch-window! 0)
                                                         (.focus (by-id "input")))))))))))
        (.addEventListener theme-b "click" (fn [_] (cycle-theme!) (.focus (by-id "input"))))
        (.addEventListener rename-b "click" (fn [_] (rename-ui!)))
        (.addEventListener rename-input "keydown"
                           (fn [e]
                             (cond
                               (= (.-key e) "Enter")
                               (let [v (.-value rename-input)]
                                 (set! (.-cssText (.-style rename-box)) "display:none;")
                                 (-> (op! {:op :rename :name v}) (.then after-op!))
                                 (.focus (by-id "input")))
                               (= (.-key e) "Escape")
                               (do (set! (.-cssText (.-style rename-box)) "display:none;")
                                   (.focus (by-id "input"))))))
        (.appendChild rename-box rename-input)
        (doseq [c [title tabs open-b theme-b rename-b rename-box status]]
          (.appendChild hdr c)))
      (.appendChild app hdr))
    (let [sc (el "div" "flex:1 1 auto;overflow:auto;position:relative;cursor:text;" nil)
          sizer (el "div" "position:relative;min-height:100%;" nil)
          win (el "div" "" nil)
          caret (el "div" "display:none;" nil)
          ta (el "textarea" "position:absolute;opacity:0;" nil)]
      (set! (.-id sc) "scroll")
      (set! (.-id sizer) "sizer")
      (set! (.-id win) "win")
      (set! (.-id caret) "caret")
      (set! (.-id ta) "input")
      (set! (.-spellcheck ta) false)
      (.appendChild sizer win)
      (.appendChild sizer caret)
      (.appendChild sizer ta)
      (.appendChild sc sizer)
      (.appendChild app sc)
      (.addEventListener sc "scroll"
                         (fn [_]
                           (let [win (:win @state)
                                 fv (first-visible)]
                             (when (and win (or (< fv (+ (:start-line win) 15))
                                                (> (+ fv (visible-count))
                                                   (+ (:start-line win) (count (:lines win)) -15))))
                               (fetch-window! fv))))
                         #js {:passive true})
      (.addEventListener sizer "mousedown" on-mousedown)
      (.addEventListener js/document "mousemove" on-mousemove)
      (.addEventListener js/document "mouseup" on-mouseup)
      (.addEventListener ta "keydown" on-keydown)
      (.addEventListener ta "focus" (fn [_] (swap! state assoc :focused true) (render-caret!)))
      (.addEventListener ta "blur" (fn [_] (swap! state assoc :focused false) (render-caret!)))
      (.addEventListener ta "paste"
                         (fn [e]
                           (.preventDefault e)
                           (let [txt (.getData (.-clipboardData e) "text")]
                             (when (seq txt) (do-op! {:op :insert-text :text txt})))))
      (.addEventListener ta "copy"
                         (fn [e]
                           (when-let [[a b] (:sel @state)]
                             (when-let [txt (selection-text a b)]
                               (.preventDefault e)
                               (.setData (.-clipboardData e) "text/plain" txt)))))
      (.addEventListener ta "cut"
                         (fn [e]
                           (when-let [[a b] (:sel @state)]
                             (when-let [txt (selection-text a b)]
                               (.preventDefault e)
                               (.setData (.-clipboardData e) "text/plain" txt)
                               (do-op! {:op :insert-text :text ""}))))))))

(defn ^:export init []
  (build-dom!)
  (reset! metrics {:char-w (char-w) :gutter-w 56})
  (-> (get-edn "/api/state")
      (.then (fn [st]
               (let [active (:active st)
                     b (get (:buffers st) active)]
                 (swap! state assoc :tabs (:tabs st) :path active
                        :caret (or (:cursor b) 0) :sel (:sel b)
                        :theme-name (or (:theme st) "light")))
               (get-edn (str "/api/theme?name=" (:theme-name @state)))))
      (.then (fn [payload]
               (apply-theme! payload)
               (when (:path @state)
                 (fetch-window! 0))
               (.focus (by-id "input"))))))
