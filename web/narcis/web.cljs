(ns narcis.web
  "The Narcis editor client: jonquil's editor surface in the browser.
   The page is a viewport onto the rope in the Eve disk atom — it
   fetches window payloads (lines + paint spans) from the launcher's
   /api/window, renders only those lines, and sends every edit back
   as a narcis.ops message (/api/op). It never holds the buffer text:
   the only text here is the current window's lines, which are
   rendering state, replaced on every fetch.

   The app surface mirrors jonquil's: a menu bar whose menus are data
   (File / Edit / View / Search / Theme / Help, after jonquil's
   menus.edn, holding only the commands Narcis has), a command
   palette (Ctrl/Cmd+Shift+P) fed by the same command list plus
   jonquil.theme's palette entries, a find bar, a file panel, and
   theme selection — every choice in narcis.theme, :auto included,
   persisted server-side in the editor atom via the :theme/use op,
   so a reload and every other client see the same choice.

   Keys follow jonquil's keymap for the ported commands: Cmd/Ctrl+/
   or ; toggle-comment, Cmd/Ctrl+S save, Cmd/Ctrl+Y and
   Cmd/Ctrl+Shift+Z redo, F2 / Shift+F6 rename, Enter smart-enter,
   Tab/Shift+Tab indent/unindent, auto-pair on brackets/quotes via
   the server ops, paredit motion on Ctrl+Alt+arrows, theme cycle on
   Cmd/Ctrl+Shift+M, find on Cmd/Ctrl+F (next/previous Cmd/Ctrl+G and
   Cmd/Ctrl+Shift+G)."
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
         :theme-name "light" ; the resolved palette name
         :theme-choice "light" ; the stored choice ("auto" included)
         :themes []          ; /api/themes entries {:name :label :dark?}
         :tabs []
         :status nil
         :fetching false
         :want-start nil
         :sync-timer nil
         :drag-anchor nil
         :menu-open nil      ; label of the open menu, or nil
         :palette {:open false :query "" :sel 0}
         :find {:open false :query ""}
         :tree {:open false :dir nil :parent nil :entries []}
         :focused false}))

(def font "13px ui-monospace, SFMono-Regular, Menlo, Consolas, monospace")
(def line-h 19)
(def overscan 45)

(def theme-cycle-fallback
  ;; jonquil.theme/choice-names; the live list comes from /api/themes
  ["light" "dark" "solarized" "navy" "gruvbox-dark" "gruvbox-light"
   "dracula" "nord" "zenburn" "monokai" "tokyo-night" "catppuccin-mocha"
   "catppuccin-latte" "one-dark" "kanagawa" "rose-pine" "auto"])

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

(declare render! update-status! refresh-state! apply-theme-choice!)

(defn gutter-w [total-lines]
  (+ 20 (* (:char-w @metrics) (max 3 (count (str total-lines))))))

(defn focus-editor! []
  (when-let [ta (by-id "input")] (.focus ta)))

;; ---------------------------------------------------------------------------
;; API

(defn fetch-edn [url opts]
  (-> (js/fetch url opts)
      (.then (fn [r] (.text r)))
      (.then (fn [t] (reader/read-string t)))))

(defn get-edn [url] (fetch-edn url nil))

(defonce op-chain (atom (js/Promise.resolve nil)))

(defn op! [msg]
  ;; Ops on the current buffer carry the client's caret/sel (the sync
  ;; narcis.ops applies before the op). Ops that name their own :path
  ;; (:activate, :open) go as-is — another buffer's caret is not theirs.
  ;; SERIALIZED through op-chain, and the caret/sel assoc happens when
  ;; the op actually runs, not when it is queued: three fast keystrokes
  ;; otherwise all read the same stale caret and insert in reverse
  ;; order at one spot (measured: typing xyz produced "zyx").
  (let [run (fn []
              (let [s @state
                    msg (if (contains? msg :path)
                          msg
                          (assoc msg :path (:path s) :caret (:caret s) :sel (:sel s)))]
                (-> (fetch-edn "/api/op" #js {:method "POST" :body (pr-str msg)})
                    (.then (fn [res]
                             (when (and (:ok res) (= (:path msg) (:path @state)))
                               (swap! state assoc :caret (:caret res) :sel (:sel res)))
                             res)))))
        p (.then @op-chain run run)]
    (reset! op-chain (.catch p (fn [_] nil)))
    p))

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
  (if (:ok res)
    (do (swap! state assoc :status nil)
        (ensure-caret-visible!)
        (refresh-window!)
        (update-status!))
    (do (swap! state assoc :status (str "op failed: " (:error res)))
        (update-status!))))

(defn refresh-state!
  "Re-read /api/state (tabs, active buffer, caret/sel of the active
   buffer) and fetch its window. The one flow after an op that
   changes which buffers exist or which is active (:new, :open,
   :close, :save-as, tab clicks)."
  []
  (-> (get-edn "/api/state")
      (.then (fn [st]
               (let [active (:active st)
                     b (get (:buffers st) active)]
                 (swap! state assoc :tabs (:tabs st) :path active
                        :caret (or (:cursor b) 0) :sel (:sel b)
                        :theme-choice (or (:theme st) (:theme-choice @state))))
               (fetch-window! 0)
               (focus-editor!)))))

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
                  (str "font:inherit;font-size:12px;margin-right:4px;padding:3px 6px 3px 10px;"
                       "border:1px solid " (get-in s [:theme :theme :selection])
                       ";border-radius:4px;cursor:pointer;"
                       (if active?
                         (str "background:" (get-in s [:theme :theme :selection])
                              ";color:" (get-in s [:theme :theme :text]))
                         (str "background:transparent;color:" (get-in s [:theme :theme :text]))))
                  nil)
            label (el "span" "" name)
            x (el "span" "margin-left:7px;opacity:0.55;padding:0 2px;" "×")]
        (.appendChild b label)
        (.appendChild b x)
        (.addEventListener b "click"
                           (fn [_]
                             (-> (op! {:op :activate :path p})
                                 (.then (fn [_] (refresh-state!))))))
        (.addEventListener x "click"
                           (fn [e]
                             (.stopPropagation e)
                             (-> (op! {:op :close :path p})
                                 (.then (fn [_] (refresh-state!))))))
        (.appendChild bar b)))))

(defn update-status! []
  (let [s @state
        win (:win s)
        pos (caret-pos win (:caret s))
        txt (if (:status s)
              (:status s)
              (if (and win pos)
              (str (:path s) "  line " (inc (:n (nth (:lines win) (first pos))))
                   ", col " (inc (second pos))
                   "  ·  " (:total-lines win) " lines, " (:total-chars win) " chars"
                   "  ·  spans " (:spans-mode win)
                   (when win (str "  ·  rev " (:rev win))))
              (str (:path s) "")))
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

;; ---------------------------------------------------------------------------
;; theme selection — the stored choice lives server-side (the editor
;; atom, via :theme/use); this page resolves :auto against the system
;; and renders the resolved palette from /api/theme.

(defn system-dark? []
  (boolean (and (.-matchMedia js/window)
                (.-matches (.matchMedia js/window "(prefers-color-scheme: dark)")))))

(defn theme-choices []
  (let [ts (:themes @state)]
    (if (seq ts) (mapv :name ts) theme-cycle-fallback)))

(defn resolved-name [choice]
  (if (= choice "auto")
    (if (system-dark?) "dark" "light")
    choice))

(defn apply-theme! [payload]
  (swap! state assoc :theme payload :theme-name (name (get-in payload [:theme :name])))
  (let [t (:theme payload)
        style-el (by-id "theme-css")]
    (set! (.-textContent style-el)
          (str "body{background:" (:background t) ";color:" (:text t) ";}"
               "#hdr{border-bottom:1px solid " (:selection t) ";}"
               ".menu-panel{background:" (:background t) ";border:1px solid " (:selection t)
               ";box-shadow:0 6px 24px rgba(0,0,0,0.35);}"
               ".menu-item:hover{background:" (:selection t) ";}"
               "#findbar{border-bottom:1px solid " (:selection t) ";}"
               "@keyframes narcis-blink{0%,55%{opacity:1}56%,100%{opacity:0}}"))
    ;; colors only, as properties: replacing #app's cssText here would
    ;; wipe the flex column layout build-dom! gave it (it did — the
    ;; page fell back to one tall block and the scroll area lost its
    ;; bounded height).
    (set! (.-backgroundColor (.-style (by-id "app"))) (:background t))
    (set! (.-color (.-style (by-id "app"))) (:text t))
    (render!)))

(defn apply-theme-choice!
  "Select choice (a name from theme-choices): persist it server-side,
   then fetch and apply the resolved palette."
  [choice]
  (swap! state assoc :theme-choice choice)
  (-> (op! {:op :theme/use :name choice :system-dark (system-dark?)})
      (.then (fn [_] (get-edn (str "/api/theme?name=" (resolved-name choice)))))
      (.then apply-theme!)))

(defn cycle-theme! []
  (let [choices (theme-choices)
        cur (:theme-choice @state)
        nxt (nth choices (mod (inc (.indexOf choices cur)) (count choices)))]
    (apply-theme-choice! nxt)))

;; ---------------------------------------------------------------------------
;; commands — the one list the menus and the palette both run. A
;; command is {:id :label :keys :run}; :keys is display text for the
;; chord that runs it, where one exists.

(declare do-op! rename-ui! open-find! find-step! toggle-tree! open-palette!
         close-menus!)

(defn do-new! []
  (-> (op! {:op :new}) (.then (fn [_] (refresh-state!)))))

(defn do-open-prompt! []
  (when-let [p (js/prompt "File path to open:")]
    (when (seq p)
      (-> (op! {:op :open :path p}) (.then (fn [_] (refresh-state!)))))))

(defn do-save-as! []
  (when-let [p (js/prompt "Save as (new file path):")]
    (when (seq p)
      (-> (op! {:op :save-as :new-path p})
          (.then (fn [res]
                   (when (:ok res) (refresh-state!))
                   (when-not (:ok res)
                     (swap! state assoc :status (:error res))
                     (js/alert (str "Save As failed: " (:error res))))))))))

(defn activate-tab! [p]
  (-> (op! {:op :activate :path p}) (.then (fn [_] (refresh-state!)))))

(defn step-tab! [d]
  (let [tabs (:tabs @state)
        i (.indexOf tabs (:path @state))]
    (when (and (seq tabs) (<= 0 i))
      (activate-tab! (nth tabs (mod (+ i d) (count tabs)))))))

(def commands
  [{:id :file/new :label "New Tab" :run do-new!}
   {:id :file/open :label "Open…" :keys nil :run do-open-prompt!}
   {:id :file/save :label "Save" :keys "Ctrl+S" :run (fn [] (do-op! {:op :save}))}
   {:id :file/save-as :label "Save As…" :run do-save-as!}
   {:id :tab/close :label "Close Tab" :run (fn [] (-> (op! {:op :close :path (:path @state)})
                                                      (.then (fn [_] (refresh-state!)))))}
   {:id :edit/undo :label "Undo" :keys "Ctrl+Z" :run (fn [] (do-op! {:op :undo}))}
   {:id :edit/redo :label "Redo" :keys "Ctrl+Shift+Z" :run (fn [] (do-op! {:op :redo}))}
   {:id :edit/toggle-comment :label "Toggle Comment" :keys "Ctrl+/"
    :run (fn [] (do-op! {:op :toggle-comment}))}
   {:id :edit/indent :label "Indent" :keys "Tab" :run (fn [] (do-op! {:op :indent}))}
   {:id :edit/unindent :label "Unindent" :keys "Shift+Tab" :run (fn [] (do-op! {:op :unindent}))}
   {:id :edit/rename :label "Rename Symbol…" :keys "F2" :run (fn [] (rename-ui!))}
   {:id :paredit/forward :label "Forward by a Form" :keys "Ctrl+Alt+→"
    :run (fn [] (do-op! {:op :forward-sexp}))}
   {:id :paredit/backward :label "Backward by a Form" :keys "Ctrl+Alt+←"
    :run (fn [] (do-op! {:op :backward-sexp}))}
   {:id :tree/toggle :label "File Tree" :run (fn [] (toggle-tree!))}
   {:id :tab/next :label "Next Tab" :run (fn [] (step-tab! 1))}
   {:id :tab/previous :label "Previous Tab" :run (fn [] (step-tab! -1))}
   {:id :view/cycle-theme :label "Next Theme" :keys "Ctrl+Shift+M" :run cycle-theme!}
   {:id :palette/open :label "Command Palette…" :keys "Ctrl+Shift+P" :run (fn [] (open-palette!))}
   {:id :find/open :label "Find…" :keys "Ctrl+F" :run (fn [] (open-find!))}
   {:id :find/next :label "Find Next" :keys "Ctrl+G" :run (fn [] (find-step! :next))}
   {:id :find/previous :label "Find Previous" :keys "Ctrl+Shift+G" :run (fn [] (find-step! :prev))}])

(def command-by-id (into {} (map (fn [c] [(:id c) c])) commands))

;; ---------------------------------------------------------------------------
;; menus — jonquil's shape (menus as data, sections separated), only
;; the commands Narcis has. The Theme menu is generated from the
;; server's choice list, checkmarked at the stored choice.

(def menus
  [{:label "File"
    :sections [[:file/new :file/open]
               [:file/save :file/save-as]
               [:tab/close]]}
   {:label "Edit"
    :sections [[:edit/undo :edit/redo]
               [:edit/toggle-comment :edit/indent :edit/unindent]
               [:edit/rename]
               [:paredit/forward :paredit/backward]]}
   {:label "View"
    :sections [[:tree/toggle]
               [:tab/next :tab/previous]
               [:view/cycle-theme :palette/open]]}
   {:label "Search"
    :sections [[:find/open]
               [:find/next :find/previous]]}
   {:label "Theme" :theme-menu true}
   {:label "Help"
    :sections [[:help/about]]}])

(defn close-menus! []
  (swap! state assoc :menu-open nil)
  (when-let [p (by-id "menu-panel")]
    (set! (.-cssText (.-style p)) "display:none;")))

(defn menu-item-el [label keys-text checked? run]
  (let [row (el "div"
                (str "display:flex;align-items:center;gap:8px;padding:5px 22px 5px 10px;"
                     "cursor:pointer;white-space:nowrap;font-size:12.5px;")
                nil)
        tick (el "span" "width:14px;flex:0 0 auto;" (if checked? "✓" ""))
        lab (el "span" "" label)]
    (.appendChild row tick)
    (.appendChild row lab)
    (when keys-text
      (.appendChild row (el "span" "margin-left:auto;opacity:0.55;font-size:11.5px;" keys-text)))
    (set! (.-className row) "menu-item")
    (.addEventListener row "click"
                       (fn [_] (close-menus!) (run) (focus-editor!)))
    row))

(defn open-menu! [label anchor]
  (let [panel (by-id "menu-panel")
        menu (first (filter (fn [m] (= label (:label m))) menus))
        rect (.getBoundingClientRect anchor)]
    (set! (.-innerHTML panel) "")
    (if (:theme-menu menu)
      (doseq [t (:themes @state)]
        (.appendChild panel
                      (menu-item-el (str (:label t)
                                         (when (= "auto" (:name t))
                                           (str " (" (resolved-name "auto") ")")))
                                    nil
                                    (= (:name t) (:theme-choice @state))
                                    (fn [] (apply-theme-choice! (:name t))))))
      (doseq [[i section] (map-indexed vector (:sections menu))]
        (when (pos? i)
          (.appendChild panel (el "div" "height:1px;margin:4px 0;background:currentColor;opacity:0.18;" nil)))
        (doseq [cid section]
          (if (= cid :help/about)
            (.appendChild panel
                          (menu-item-el "About Narcis" nil false
                                        (fn []
                                          (js/alert (str "Narcis — jonquil on Eve.\n"
                                                         "Buffers live in an Eve disk atom; "
                                                         "the page is a viewport.\n"
                                                         "Theme: " (:theme-choice @state)
                                                         " (resolved " (:theme-name @state) ")")))))
            (let [c (get command-by-id cid)]
              (when c
                (.appendChild panel
                              (menu-item-el (:label c) (:keys c)
                                            (and (= cid :tree/toggle)
                                                 (get-in @state [:tree :open]))
                                            (:run c)))))))))
    (set! (.-cssText (.-style panel))
          (str "display:block;position:absolute;z-index:40;padding:5px 0;border-radius:6px;"
               "top:" (.-bottom rect) "px;left:" (.-left rect) "px;min-width:230px;"))
    (swap! state assoc :menu-open label)))

(defn build-menus! [bar]
  (doseq [m menus]
    (let [b (el "button"
                "font:inherit;font-size:12.5px;background:transparent;border:0;color:inherit;padding:4px 9px;cursor:pointer;border-radius:4px;"
                (:label m))]
      (.setAttribute b "data-menu" (:label m))
      (.addEventListener b "click"
                         (fn [e]
                           (.stopPropagation e)
                           (if (= (:menu-open @state) (:label m))
                             (close-menus!)
                             (open-menu! (:label m) b))))
      (.addEventListener b "mouseenter"
                         (fn [_]
                           (when (and (:menu-open @state)
                                      (not= (:menu-open @state) (:label m)))
                             (open-menu! (:label m) b))))
      (.appendChild bar b))))

;; ---------------------------------------------------------------------------
;; command palette — the command list plus jonquil.theme's palette
;; entries ("Use theme: X"), filtered by the input.

(declare close-palette! render-tree!)

(defn palette-items []
  (let [q (str/lower-case (get-in @state [:palette :query]))
        cmds (map (fn [c] {:label (:label c) :keys (:keys c) :run (:run c)}) commands)
        thms (map (fn [t] {:label (str "Use theme: " (:label t))
                           :keys nil
                           :run (fn [] (apply-theme-choice! (:name t)))})
                  (:themes @state))
        all (concat cmds thms)]
    (if (str/blank? q)
      (vec all)
      (vec (filter (fn [i] (str/includes? (str/lower-case (:label i)) q)) all)))))

(defn render-palette! []
  (let [box (by-id "palette-list")
        items (palette-items)
        sel (min (get-in @state [:palette :sel]) (max 0 (dec (count items))))]
    (set! (.-innerHTML box) "")
    (doseq [[i item] (map-indexed vector items)]
      (let [row (el "div"
                    (str "display:flex;gap:8px;padding:6px 12px;cursor:pointer;font-size:13px;"
                         (when (= i sel) (str "background:" (get-in @state [:theme :theme :selection]) ";")))
                    nil)]
        (.appendChild row (el "span" "" (:label item)))
        (when (:keys item)
          (.appendChild row (el "span" "margin-left:auto;opacity:0.55;font-size:11.5px;" (:keys item))))
        (.addEventListener row "click"
                           (fn [_]
                             (close-palette!)
                             ((:run item))
                             (focus-editor!)))
        (.appendChild box row)))
    (when (empty? items)
      (.appendChild box (el "div" "padding:6px 12px;opacity:0.6;font-size:13px;" "No matching commands")))))

(defn close-palette! []
  (swap! state assoc-in [:palette :open] false)
  (set! (.-cssText (.-style (by-id "palette"))) "display:none;"))

(defn open-palette! []
  (swap! state assoc :palette {:open true :query "" :sel 0})
  (set! (.-cssText (.-style (by-id "palette")))
        "display:block;position:absolute;z-index:50;top:48px;left:50%;transform:translateX(-50%);width:520px;max-width:92vw;border-radius:8px;overflow:hidden;")
  (set! (.-value (by-id "palette-input")) "")
  (render-palette!)
  (.focus (by-id "palette-input")))

(defn palette-key! [e]
  (let [items (palette-items)
        sel (get-in @state [:palette :sel])]
    (cond
      (= (.-key e) "Escape") (do (close-palette!) (focus-editor!))
      (= (.-key e) "ArrowDown")
      (do (.preventDefault e)
          (swap! state assoc-in [:palette :sel] (min (inc sel) (max 0 (dec (count items)))))
          (render-palette!))
      (= (.-key e) "ArrowUp")
      (do (.preventDefault e)
          (swap! state assoc-in [:palette :sel] (max (dec sel) 0))
          (render-palette!))
      (= (.-key e) "Enter")
      (when-let [item (nth items sel nil)]
        (close-palette!)
        ((:run item))
        (focus-editor!)))))

;; ---------------------------------------------------------------------------
;; find bar — drives the :find-next/:find-prev ops; the server selects
;; the match, this page just shows it.

(defn find-step! [dir]
  (let [q (get-in @state [:find :query])]
    (when (seq q)
      (-> (op! {:op (if (= dir :next) :find-next :find-prev) :query q})
          (.then (fn [res]
                   (if (:ok res)
                     (after-op! res)
                     (let [bar (by-id "status")]
                       (when bar (set! (.-textContent bar) (str "Find: " (:error res))))))))))))

(defn open-find! []
  (swap! state assoc-in [:find :open] true)
  (set! (.-cssText (.-style (by-id "findbar"))) "display:flex;")
  (let [input (by-id "find-input")]
    (when (seq (get-in @state [:find :query]))
      (set! (.-value input) (get-in @state [:find :query])))
    (.focus input)
    (.select input)))

(defn close-find! []
  (swap! state assoc-in [:find :open] false)
  (set! (.-cssText (.-style (by-id "findbar"))) "display:none;")
  (focus-editor!))

;; ---------------------------------------------------------------------------
;; file panel — /api/list over the launcher's directory; click a file
;; to open it, a directory to descend.

(defn tree-path [dir name] (str dir "/" name))

(defn load-tree! [dir]
  (-> (get-edn (if dir
                 (str "/api/list?dir=" (js/encodeURIComponent dir))
                 "/api/list"))
      (.then (fn [res]
               (swap! state assoc :tree {:open true :dir (:dir res)
                                         :parent (:parent res)
                                         :entries (:entries res)})
               (render-tree!)))))

(defn render-tree! []
  (let [panel (by-id "tree")
        {:keys [open dir parent entries]} (:tree @state)]
    (if-not open
      (set! (.-cssText (.-style panel)) "display:none;")
      (do (set! (.-cssText (.-style panel))
                (str "display:block;flex:0 0 250px;overflow:auto;padding:8px 6px;"
                     "border-right:1px solid " (get-in @state [:theme :theme :selection])
                     ";font-size:12.5px;"))
          (set! (.-innerHTML panel) "")
          (.appendChild panel
                        (el "div" "font-weight:bold;padding:2px 6px 8px;word-break:break-all;" dir))
          (when parent
            (let [up (el "div" "padding:3px 6px;cursor:pointer;opacity:0.8;" "📁 ..")]
              (.addEventListener up "click" (fn [_] (load-tree! parent)))
              (.appendChild panel up)))
          (doseq [{:keys [name dir?]} entries]
            (let [row (el "div"
                          (str "padding:3px 6px;cursor:pointer;white-space:nowrap;"
                               (when (= (tree-path dir name) (:path @state))
                                 (str "background:" (get-in @state [:theme :theme :selection]) ";")))
                          (str (if dir? "📁 " "📄 ") name))]
              (.addEventListener row "click"
                                 (fn [_]
                                   (if dir?
                                     (load-tree! (tree-path dir name))
                                     (-> (op! {:op :open :path (tree-path dir name)})
                                         (.then (fn [_] (refresh-state!) (render-tree!)))))))
              (.appendChild panel row)))))))

(defn toggle-tree! []
  (if (get-in @state [:tree :open])
    (do (swap! state assoc-in [:tree :open] false)
        (render-tree!))
    (load-tree! (get-in @state [:tree :dir]))))

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
        ;; an open menu takes Escape first
        (and (:menu-open s) (= k "Escape"))
        (do (.preventDefault e) (close-menus!))

        ;; command palette: Cmd/Ctrl+Shift+P or Cmd/Ctrl+K
        (and cmd shift (or (= k "P") (= k "p")))
        (do (.preventDefault e) (open-palette!))
        (and cmd (= k "k"))
        (do (.preventDefault e) (open-palette!))

        ;; find: Cmd/Ctrl+F opens the bar, Cmd/Ctrl+G / Shift+G step
        (and cmd (= k "f")) (do (.preventDefault e) (open-find!))
        (and cmd (= k "g")) (do (.preventDefault e) (find-step! (if shift :prev :next)))

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
  ;; preventDefault first: the browser's own mousedown default moves
  ;; focus to the clicked (non-focusable) element AFTER this handler
  ;; runs, undoing the programmatic focus — the click moved the caret
  ;; but typing went nowhere and the caret hid. Every editor does this.
  (.preventDefault e)
  (.focus (by-id "input"))
  (when-let [off (event-pos e)]
    (swap! state assoc :drag-anchor off)
    (set-caret-local! off nil)))

(defn on-mousemove [e]
  (when (:drag-anchor @state)
    (.preventDefault e)
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
    ;; header: title, menu bar, tabs, rename box, status
    (let [hdr (el "div" "display:flex;align-items:center;gap:6px;padding:6px 10px;flex:0 0 auto;position:relative;" nil)]
      (set! (.-id hdr) "hdr")
      (let [title (el "span" "font-weight:bold;font-size:14px;margin-right:2px;" "Narcis")
            menubar (el "span" "display:flex;align-items:center;" nil)
            tabs (el "span" "margin-left:8px;" nil)
            rename-box (el "span" "display:none;" nil)
            rename-input (el "input" "font:inherit;font-size:12px;" nil)
            status (el "span" "font-size:12px;opacity:0.75;margin-left:auto;" "")]
        (set! (.-id tabs) "tabs")
        (set! (.-id status) "status")
        (set! (.-id rename-box) "rename-box")
        (set! (.-id rename-input) "rename-input")
        (set! (.-placeholder rename-input) "new name, Enter to rename")
        (build-menus! menubar)
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
        (doseq [c [title menubar tabs rename-box status]]
          (.appendChild hdr c)))
      (let [panel (el "div" "display:none;" nil)]
        (set! (.-id panel) "menu-panel")
        (set! (.-className panel) "menu-panel")
        (.appendChild hdr panel))
      (.appendChild app hdr))
    ;; find bar (hidden until Ctrl/Cmd+F)
    (let [bar (el "div" "display:none;align-items:center;gap:8px;padding:5px 10px;flex:0 0 auto;" nil)
          input (el "input" "font:inherit;font-size:12.5px;min-width:260px;" nil)
          prev-b (el "button" "font:inherit;font-size:12px;" "↑ Prev")
          next-b (el "button" "font:inherit;font-size:12px;" "↓ Next")
          close-b (el "button" "font:inherit;font-size:12px;" "×")]
      (set! (.-id bar) "findbar")
      (set! (.-id input) "find-input")
      (set! (.-placeholder input) "Find in buffer…")
      (.addEventListener input "input"
                         (fn [_] (swap! state assoc-in [:find :query] (.-value input))))
      (.addEventListener input "keydown"
                         (fn [e]
                           (cond
                             (= (.-key e) "Enter") (find-step! (if (.-shiftKey e) :prev :next))
                             (= (.-key e) "Escape") (close-find!))))
      (.addEventListener prev-b "click" (fn [_] (find-step! :prev)))
      (.addEventListener next-b "click" (fn [_] (find-step! :next)))
      (.addEventListener close-b "click" (fn [_] (close-find!)))
      (doseq [c [input prev-b next-b close-b]] (.appendChild bar c))
      (.appendChild app bar))
    ;; command palette overlay (hidden until Ctrl/Cmd+Shift+P)
    (let [pal (el "div" "display:none;" nil)
          input (el "input" "font:inherit;font-size:13px;width:100%;box-sizing:border-box;padding:9px 12px;border:0;outline:none;background:transparent;color:inherit;" nil)
          list (el "div" "max-height:320px;overflow:auto;padding:4px 0;" nil)]
      (set! (.-id pal) "palette")
      (set! (.-className pal) "menu-panel")
      (set! (.-id input) "palette-input")
      (set! (.-id list) "palette-list")
      (set! (.-placeholder input) "Type a command or a theme…")
      (.addEventListener input "input"
                         (fn [_]
                           (swap! state assoc :palette {:open true
                                                         :query (.-value input)
                                                         :sel 0})
                           (render-palette!)))
      (.addEventListener input "keydown" palette-key!)
      (.appendChild pal input)
      (.appendChild pal list)
      (.appendChild app pal))
    ;; main row: file panel + editor scroll area
    (let [row (el "div" "display:flex;flex:1 1 auto;min-height:0;" nil)
          tree (el "div" "display:none;" nil)
          sc (el "div" "flex:1 1 auto;overflow:auto;position:relative;cursor:text;" nil)
          sizer (el "div" "position:relative;min-height:100%;" nil)
          win (el "div" "" nil)
          caret (el "div" "display:none;" nil)
          ta (el "textarea" "position:absolute;opacity:0;" nil)]
      (set! (.-id tree) "tree")
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
      (.appendChild row tree)
      (.appendChild row sc)
      (.appendChild app row)
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
      (.addEventListener js/document "mousedown"
                         (fn [e]
                           (when (and (:menu-open @state)
                                      (not (.contains (by-id "menu-panel") (.-target e)))
                                      (not (.getAttribute (.-target e) "data-menu")))
                             (close-menus!))))
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
                               (do-op! {:op :insert-text :text ""}))))))
    ;; :auto follows the system while it is the stored choice
    (when (.-matchMedia js/window)
      (.addEventListener (.matchMedia js/window "(prefers-color-scheme: dark)") "change"
                         (fn [_]
                           (when (= "auto" (:theme-choice @state))
                             (apply-theme-choice! "auto")))))
  ))

(defn ^:export init []
  (build-dom!)
  (reset! metrics {:char-w (char-w) :gutter-w 56})
  (-> (get-edn "/api/themes")
      (.then (fn [th]
               (swap! state assoc :themes (:themes th) :theme-choice (:choice th))
               (get-edn "/api/state")))
      (.then (fn [st]
               (let [active (:active st)
                     b (get (:buffers st) active)]
                 (swap! state assoc :tabs (:tabs st) :path active
                        :caret (or (:cursor b) 0) :sel (:sel b)
                        :theme-choice (or (:theme st) (:theme-choice @state))))
               (get-edn (str "/api/theme?name=" (resolved-name (:theme-choice @state))))))
      (.then (fn [payload]
               (apply-theme! payload)
               (when (:path @state)
                 (fetch-window! 0))
               (.focus (by-id "input"))))))
