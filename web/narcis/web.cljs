(ns narcis.web
  "The Narcis client: jonquil's window in the browser.

   The window is jonquil's — a header bar (Files, Open…, + at its start,
   the file's name in the middle, Run, Stop, Save… at its end), the
   Definitions editor above the Interactions console, a status bar
   under them, a file tree and a tab strip when they are wanted — and
   so are its menus: the server sends jonquil's menus narrowed to the
   commands Narcis has (/api/menus), and every item runs by its
   command id through the one table below, which the header buttons,
   the keys and the command palette run through too.

   The page is a viewport, not a copy. The editor fetches window
   payloads (lines + paint spans) of the document in the Eve atom from
   /api/window, renders only those lines, and sends every edit back as
   a narcis.ops message (/api/op). The console reads the transcript in
   its Eve atom by position (/api/console) and sends what is typed at
   the prompt to the evaluator. The only text held here is what is on
   screen."
  (:require [cljs.reader :as reader]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; state — rendering state only; buffers and the transcript live in atoms

(defonce state
  (atom {:path nil
         :win nil            ; last /api/window payload
         :caret 0
         :sel nil            ; [a b] or nil
         :goal-col nil
         :theme nil          ; /api/theme payload {:theme :styles :console}
         :theme-name "light"
         :theme-choice "light"
         :themes []
         :tabs []
         :menus []           ; /api/menus, with Narcis's own items added
         :project nil
         :runtime "Jolt"
         :status nil         ; a message for the status bar, or nil
         :fetching false
         :want-start nil
         :sync-timer nil
         :drag-anchor nil
         :menu-open nil
         :font-px 13
         :line-numbers false
         :palette {:open false :query "" :sel 0}
         :find {:open false :query ""}
         :tree {:open false :root nil :expanded #{} :kids {}}
         :console {:entries [] :base 0 :next 0 :status "idle" :ns "user"}
         :console-h nil      ; px, or nil for the default share
         :breakpoints {}     ; path -> #{line} the gutter marks
         :debug nil          ; last debug-state from the console
         :frames {:open false :data nil :frame 0 :local nil :eval nil}
         :complete nil       ; {:start :end :candidates :sel :doc} while open
         :dialog nil
         :focused false}))

(def zoom-sizes [9 10 11 12 13 14 16 18 20 24 28])

(defn font-px [] (:font-px @state))
(defn line-h [] (js/Math.round (* 1.46 (font-px))))

(def max-scroll-px
  "The tallest scroll space the page may build. Browsers cap an
   element's height and the scroll offset near 33.5M px, and past
   the cap the offset clamps — measured on a 2,167,597-line file:
   the caret at the end asked for 41M px, the browser clamped it
   to a position at line ~1.77M, the scroll handler then fetched
   that window, and the caret sat outside every window the page
   held (the position readout went blank after a find to the end
   of the file, and the view never reached the end). Past the cap
   the scroll space scales: a line's slot in the scroll space is
   px-per-line, while the window's rows stack at the full line
   height inside their block, so the text stays readable and only
   the scrollbar's travel is compressed."
  30000000)

(defn doc-scale []
  (let [total (or (:total-lines (:win @state)) 0)
        full (* total (line-h))]
    (if (> full max-scroll-px) (/ max-scroll-px full) 1)))

(defn px-per-line [] (* (line-h) (doc-scale)))

;; While the buffer on screen is still loading, the scroll space
;; is denominated in the file's BYTES — the one quantity exact
;; from t=0 — not in the skeleton's estimated line count, which
;; drifts as the scan proceeds and dragged the parked scrollbar
;; (and every window fetched for the position) across the file.
;; The window payloads carry the denomination with them
;; (:total-bytes, and the window's own :start-byte), so the mode
;; is a property of the window held, and it ends by itself when
;; the load does: the first whole-buffer window has no
;; :start-byte, and refresh-state! hands the scroll fraction
;; over to the line geometry in the same sync.
(defn loading-entry []
  (get (:loading @state) (:path @state)))

(defn byte-mode? []
  (boolean (and (loading-entry) (:start-byte (:win @state)))))

(defn total-bytes []
  (or (:total-bytes (:win @state))
      (:total (loading-entry))
      0))

(defn px-per-byte []
  (let [tb (total-bytes)]
    (if (pos? tb) (min (line-h) (/ max-scroll-px tb)) (line-h))))

(defn win-start-px
  "The held window's block top in scroll space: its start byte
   at the byte rate while its buffer is loading, its start line
   at the line rate otherwise. The window's rows stack at the
   full line height inside the block either way. In byte mode
   the block is also pulled back so it never runs past the end
   of the space: its rows are taller than its bytes' slots, so
   placed at its byte the last window's block would overrun the
   space's end, the scroll height would grow by the overrun
   when it landed, and a view parked at the bottom would sit a
   block's height short of the file's last line. The bottom of
   the scrollbar is the bottom of the file."
  [win]
  (cond
    ;; a window fetched by a byte ENDS at the line holding that
    ;; byte (the server's contract: the byte is the viewport's
    ;; bottom edge), so its block ends at the byte's place. Its
    ;; rows are taller than their bytes' slots: placed by its
    ;; start byte, the block hung a block's height below the
    ;; anchor — a viewport of white with the window's first
    ;; rows at its bottom, the rest below it (measured in the
    ;; real page on a 2 GB load: block top 278 px into a 429 px
    ;; viewport, 8 rows of 113 on screen)
    (and (byte-mode?) (:anchor-byte win))
    (max 0 (- (* (:anchor-byte win) (px-per-byte))
              (* (count (:lines win)) (line-h))))

    (and (byte-mode?) (:start-byte win))
    (min (* (:start-byte win) (px-per-byte))
         (max 0 (- (* (total-bytes) (px-per-byte))
                   (* (count (:lines win)) (line-h)))))

    :else
    (* (:start-line win) (px-per-line))))

(defn scroll-scale
  "One unit of the scroll space's denomination, in pixels: the
   rate fetch-window!'s drift compensation compares across a
   fetch. In byte mode it never changes during a load, so the
   compensation stays silent — the space does not drift."
  []
  (if (byte-mode?) (px-per-byte) (px-per-line)))

(defn wheel-px-per-line
  "The compressed rate the wheel's relative regime converts at:
   in byte mode a byte's slot is px-per-byte and a line is
   total-bytes/total-lines bytes on average, so a line's slot
   is the product — the same two-regime feel, denominated in
   the space the scrollbar actually travels."
  []
  (if (byte-mode?)
    (let [tl (max 1 (or (:total-lines (:win @state)) 1))]
      (* (px-per-byte) (/ (total-bytes) tl)))
    (px-per-line)))

(def mono "ui-monospace, SFMono-Regular, Menlo, Consolas, 'DejaVu Sans Mono', monospace")
(defn font [] (str (font-px) "px " mono))
(def overscan 45)

;; Wheel scrolling has two regimes, divided by speed. SLOW
;; travel is absolute: the wheel's travel moves the text by the
;; same amount on every file, so inching through a passage feels
;; identical whatever the buffer's size — the earlier line-based
;; cap broke this, because a budget denominated in lines buys
;; fewer pixels the bigger the file gets, and the gig file crept
;; where the small file glided. Only past the threshold does
;; travel become relative to the buffer: the excess demand is
;; spent at the file's compressed rate, a line per px-per-line
;; of wheel travel, so spinning the wheel flies through a big
;; file the way it should. The boundary is a bucket of text
;; pixels: it refills at wheel-absolute-rate pixels per second
;; up to a burst of wheel-burst-px, demand spends from it at
;; par, and whatever demand overflows the bucket converts at
;; the compressed rate. On an unscaled file the two rates are
;; the same rate and the regimes are indistinguishable.
(def wheel-burst-px 160)
(def wheel-absolute-rate 700)
(def wheel-bucket (atom {:px wheel-burst-px :at 0}))

;; the paths this page has seen loading: their status says
;; "Whole document" once the load ends
(defonce seen-loading (atom #{}))

(def theme-cycle-fallback
  ["light" "dark" "solarized" "navy" "gruvbox-dark" "gruvbox-light"
   "dracula" "nord" "zenburn" "monokai" "tokyo-night" "catppuccin-mocha"
   "catppuccin-latte" "one-dark" "kanagawa" "rose-pine" "auto"])

;; ---------------------------------------------------------------------------
;; DOM helpers

(defn el
  "An element: tag, class (or nil), text (or nil)."
  [tag cls text]
  (let [e (.createElement js/document tag)]
    (when cls (set! (.-className e) cls))
    (when (some? text) (set! (.-textContent e) text))
    e))

(defn by-id [id] (.getElementById js/document id))

(defn bottom-byte
  "The file byte at the viewport's bottom edge, in byte mode:
   the byte the window fetch anchors on. The server ends the
   fetched window at the line holding it, so the lines on
   screen are the ones down to the bottom — the file's last
   line included, when the viewport sits at the end."
  []
  (let [sc (by-id "scroll")]
    (when sc
      (js/Math.floor (min (total-bytes)
                          (/ (+ (.-scrollTop sc) (.-clientHeight sc))
                             (px-per-byte)))))))

(defn css! [e s] (set! (.-cssText (.-style e)) s) e)

(defn clear! [e] (set! (.-innerHTML e) "") e)

(defn show! [e on?] (set! (.-display (.-style e)) (if on? "" "none")) e)

(defn on! [e ev f] (.addEventListener e ev f) e)

(defn append! [parent & kids]
  (doseq [k kids] (when k (.appendChild parent k)))
  parent)

(defn measure-char-w
  "The width of one character of the editor's font, measured where the
   text is drawn (a span in the editor pane: the font the browser really
   picked, at the size it really is)."
  []
  (if-let [sc (by-id "scroll")]
    (let [probe (el "span" nil (apply str (repeat 100 "x")))]
      (css! probe "position:absolute;visibility:hidden;white-space:pre;")
      (.appendChild sc probe)
      (let [w (/ (.-width (.getBoundingClientRect probe)) 100)]
        (.removeChild sc probe)
        (if (pos? w) w 8)))
    8))

(defonce metrics (atom {:char-w 8}))

(def code-pad-x
  "The code column's left padding, in px: character 0 of a line is
   drawn this far right of the gutter's edge. The caret's x and the
   mouse's x both count from here — when this was left out of the
   caret math, the caret drew exactly one character behind the text
   and clicks landed one character past the spot clicked."
  8)

(declare render! update-status! refresh-state! apply-theme-choice! render-tree!
         render-console! poll-console! close-complete! run-command! render-title!
         refresh-complete! accept-complete!
         toggle-breakpoint! render-frames! open-frames! close-frames! with-id)

(defn gutter-w [total-lines]
  (if (:line-numbers @state)
    (+ 22 (* (:char-w @metrics) (max 3 (count (str total-lines)))))
    16))

(defn focus-editor! []
  (when-let [ta (by-id "input")] (.focus ta #js {:preventScroll true})))

(defn focus-console! []
  (when-let [ta (by-id "console-in")] (.focus ta)))

(defn say!
  "Put a message in the status bar (nil clears it)."
  [msg]
  (swap! state assoc :status msg)
  (update-status!))

;; ---------------------------------------------------------------------------
;; API

(defn fetch-edn [url opts]
  (-> (js/fetch url opts)
      (.then (fn [r] (.text r)))
      (.then (fn [t] (reader/read-string t)))))

(defn get-edn [url] (fetch-edn url nil))

(defonce op-chain (atom (js/Promise.resolve nil)))

;; The typing batch lives further down, by on-keydown; its seal is
;; declared here so op!/chain-step! can call it: work queueing
;; behind a pending batch seals the batch first, freezing its
;; characters into their own send step, so queued work never jumps
;; the text and later characters never sweep into it.
(declare seal-batch!)

(defn chained
  "Run thunk (a fn answering a promise) after every request queued
   before it. Requests go to the server one at a time, in the order the
   user made them."
  [thunk]
  (let [p (.then @op-chain thunk thunk)]
    (reset! op-chain (.catch p (fn [_] nil)))
    p))

(defn- wait-whole
  "A promise that resolves when path's buffer is no longer partial:
   the load watch keeps :loading fresh in the state while it lasts."
  [path]
  (js/Promise.
   (fn [resolve _]
     (letfn [(tick []
               (if (contains? (:loading @state) path)
                 (js/setTimeout tick 900)
                 (resolve nil)))]
       (tick)))))

(defn op-send!
  "One op's send, unqueued: an op on the current buffer carries the
   page's caret and selection, read as they stand when the send runs;
   an op that names its own :path goes as it is. The reply's caret is
   adopted only when the user has not moved since the send read it.
   Callers queue the send (op!, the typing batch's flush)."
  [msg]
  (let [s @state
        own? (not (contains? msg :path))
        msg (if own?
              (assoc msg :path (:path s) :caret (:caret s) :sel (:sel s))
              msg)
        carried-caret (:caret msg)
        carried-sel (:sel msg)]
    ;; this op carries the caret: a caret sync still waiting has nothing
    ;; left to say (and sent later it would say a caret that has moved)
    (when own?
      (when-let [t (:sync-timer s)] (js/clearTimeout t))
      (swap! state assoc :sync-timer nil))
    (letfn [(send [msg]
              (-> (fetch-edn "/api/op" #js {:method "POST" :body (pr-str msg)})
                  (.then (fn [res]
                           ;; adopt the reply's caret only when the user has not
                           ;; moved since this op read it: a reply that lands
                           ;; after an arrow key or a click must not drag the
                           ;; caret back to where the op left it (the user's
                           ;; pending sync tells the server where they went)
                           (when (and (:ok res) (contains? res :caret) (= (:path msg) (:path @state))
                                      (= (:caret @state) carried-caret)
                                      (= (:sel @state) carried-sel))
                             (swap! state assoc :caret (:caret res) :sel (:sel res)))
                           (if (:preparing res)
                             ;; the buffer is a partial document being
                             ;; hydrated whole: the op waits for the
                             ;; document (the load watch keeps the
                             ;; progress in the status bar) and then
                             ;; goes again — the keystroke is not lost
                             (-> (wait-whole (:path msg))
                                 (.then (fn [] (send msg))))
                             res)))))]
      (send msg))))

(defn op!
  "Send one narcis.ops message, queued behind everything before it."
  [msg]
  (seal-batch!)
  (chained (fn [] (op-send! msg))))

(defn chain-step!
  "Queue f behind every op already in the chain. The page's one
   ordering rule: an op reads the caret when it runs, so anything
   that moves the caret queues here too — a move applied the moment
   its key is pressed, while keystrokes are still queued ahead of
   it, redirects those keystrokes to the moved caret (measured:
   typing abcdef at a line end and pressing Left twice mid-queue
   landed the last four letters before the line's last character)."
  [f]
  (seal-batch!)
  (chained f))

;; ---------------------------------------------------------------------------
;; window management

(defn visible-count []
  (let [sc (by-id "scroll")]
    (max 10 (js/Math.ceil (/ (.-clientHeight sc) (line-h))))))

(defn page-lines
  "How many lines one page of scrolling passes: the viewport's
   height in scroll space. On an unscaled file this is the
   visible row count; on a scaled one it is larger, because a
   screenful of scrollbar travel passes more lines than it
   shows rows."
  []
  (let [sc (by-id "scroll")]
    (max 1 (dec (js/Math.ceil (/ (.-clientHeight sc) (px-per-line)))))))

(defn first-visible []
  (let [sc (by-id "scroll")]
    (js/Math.floor (/ (.-scrollTop sc) (px-per-line)))))

(defn rendered-top-line
  "The line at the viewport's top as RENDERED: the held window's
   start plus the rows the offset reaches into its block. On an
   unscaled file this equals first-visible; on a scaled one the
   two part company — first-visible counts the offset in
   compressed scroll lines while the block's rows stack at the
   full line height, so first-visible runs ahead of the line on
   screen the further into the window the offset reaches, and a
   window fetched for it can start past the line the user is
   reading, whose text then jumps away when the fetch lands.
   nil when the viewport's top is not on a held row at all."
  []
  (let [win (:win @state)
        sc (by-id "scroll")]
    (when (and win sc (seq (:lines win)))
      (let [rel (- (.-scrollTop sc) (win-start-px win))]
        (when (and (<= 0 rel) (< rel (* (count (:lines win)) (line-h))))
          (+ (:start-line win) (js/Math.floor (/ rel (line-h)))))))))

(defn on-wheel
  "The two wheel regimes described at wheel-burst-px: the
   event's delta is first made into text pixels (a line-mode
   delta counts a text line per unit, a page-mode delta a
   viewport per unit), the part the bucket covers moves the
   scroll offset at par, and any overflow converts at the
   file's compressed rate — the offset advances by the overflow
   times the scale factor, which traverses a line per
   px-per-line of it. The scroll handler then fetches as usual.
   Ctrl-wheel is the browser's zoom gesture and is left alone."
  [e]
  (when (and (not (.-ctrlKey e)) (not (zero? (.-deltaY e))))
    (.preventDefault e)
    (let [sc (by-id "scroll")
          want (case (.-deltaMode e)
                 1 (* (.-deltaY e) (line-h))
                 2 (* (.-deltaY e) (.-clientHeight sc))
                 (.-deltaY e))
          now (.now js/performance)
          b @wheel-bucket
          avail (min wheel-burst-px
                     (+ (:px b)
                        (* (- now (:at b)) (/ wheel-absolute-rate 1000))))
          spend (max (- avail) (min avail want))
          excess (- want spend)]
      (reset! wheel-bucket {:px (- avail (js/Math.abs spend)) :at now})
      (set! (.-scrollTop sc)
            (+ (.-scrollTop sc)
               spend
               (* excess (/ (line-h) (wheel-px-per-line))))))))

(defn caret-pos
  "[line-idx col line-map] for caret in the current window, or nil."
  [win caret]
  (when win
    (first
     (keep-indexed
      (fn [i {:keys [start text] :as line}]
        (when (and (<= start caret) (<= caret (+ start (count text))))
          [i (- caret start) line]))
      (:lines win)))))

(defn- view-anchor
  "The reading position, in units that survive a new window or a
   new scroll geometry: the line at the top of the viewport and
   its top's offset from the viewport's top, in pixels (never
   positive — the line's top is at or above the viewport's). The
   scroll offset itself is in pixels of a scroll space whose
   scale changes while a file loads (the total grows as the scan
   counts, and past the browser's height cap a line's slot is a
   scaled fraction of its height), so a pixel position left alone
   lets the text slide under the reader; a line position does
   not move."
  []
  (let [win (:win @state)
        sc (by-id "scroll")]
    (when (and win sc (seq (:lines win)))
      (let [rel (- (.-scrollTop sc) (win-start-px win))]
        ;; no anchor when the viewport's top is not on a held row
        ;; at all (the window sits wholly above or below it):
        ;; clamping to an edge row would pin that row and mask
        ;; the window being in the wrong place
        (when (and (<= 0 rel) (< rel (* (count (:lines win)) (line-h))))
          (let [idx (js/Math.floor (/ rel (line-h)))]
            {:line (:n (nth (:lines win) idx))
             :off (- (* idx (line-h)) rel)
             :top (.-scrollTop sc)}))))))

(defn- restore-view!
  "Put the anchor line back where it was in the viewport, under
   the current window and geometry. No-op when the anchor line
   is not in the held window (the view moved on purpose — a
   find, a jump — and something else owns the scroll now).
   Unless force?, it is also a no-op when the scroll offset has
   moved since the anchor was taken (the user scrolled while
   the fetch was in flight — their offset is the intent, and a
   stale anchor must not zero it: measured, a load tick's
   restore landing after a jump to the middle of a loading file
   put the offset back to the top and the scroll handler then
   fetched the head window over the jumped-to one). A forced
   restore is the scroll fetch's own landing translation: its
   anchor is taken at landing time, from the offset as it then
   stands, so there is nothing stale to protect against."
  [{:keys [line off top]} force?]
  (let [win (:win @state)
        sc (by-id "scroll")]
    (when (and win sc (seq (:lines win))
               (or force?
                   (<= (js/Math.abs (- (.-scrollTop sc) top)) 1))
               (<= (:start-line win) line)
               (< line (+ (:start-line win) (count (:lines win)))))
      (let [row-top (+ (win-start-px win)
                       (* (- line (:start-line win)) (line-h)))]
        (set! (.-scrollTop sc) (- row-top off))))))

(defn fetch-window!
  "Fetch a window covering line `around`, then re-render. `around` may
   be :caret, which asks the server to anchor the window at the line
   holding the caret — the only way to bring the caret into view when
   it sits outside the window the page holds, since the page cannot
   turn a char offset into a line number on its own. Never moves the
   caret. Serialized: a fetch in flight records the newest want and
   follows up. Always returns a promise.

   With keep-view?, the reading position is anchored by line
   across the fetch: a re-sync the user did not cause (the load
   watch ticking while a file hydrates) must not move the text
   they are reading, even when the scroll geometry changed under
   it.

   Without it (the user's own scrolling, caret travel), the
   offset is translated at landing instead: on a scaled file a
   line's rendered position depends on the window's start — the
   window's block sits at its start times px-per-line while its
   rows stack at the full line height — so a window that lands
   with a different start shifts every line by the start change
   times the scale gap, and inching across a fetch boundary
   jumped the text twenty to forty lines. The landing therefore
   re-anchors the line at the viewport's top, taken from the
   offset as it stands when the window arrives, so the window
   change itself moves nothing the user can see; their scroll
   travel before and after the fetch is untouched.

   A deferrable? caller (the load watch's re-sync) whose fetch
   finds another already in flight simply waits for the next
   tick instead of recording a want: a want recorded against
   the window on screen now would be stale the moment the
   in-flight fetch lands a different one, and following it up
   would fetch the old place over the new (measured: a tick's
   want for the head, recorded while a jump's fetch was in
   flight, replaced the jumped-to window with the head one)."
  [around keep-view? deferrable?]
  (let [s @state]
    (if (:fetching s)
      (if deferrable?
        (js/Promise. (fn [resolve _]
                       (js/setTimeout (fn [] (resolve (:win @state))) 80)))
        (do (swap! state assoc :want-start around :want-keep keep-view?)
            (js/Promise. (fn [resolve _]
                           (js/setTimeout (fn [] (resolve (:win @state))) 80)))))
      (let [cnt (+ (visible-count) (* 2 overscan))
            path (:path s)
            anchor (when keep-view? (view-anchor))
            pre-top (when keep-view?
                      (when-let [sc (by-id "scroll")] (.-scrollTop sc)))
            pre-ppl (when keep-view? (scroll-scale))]
        (if path
          (do (swap! state assoc :fetching true)
              (-> (get-edn (str "/api/window?path=" (js/encodeURIComponent path)
                                (cond (= around :caret)
                                      (str "&caret=" (:caret s))
                                      ;; a byte anchor: the load-phase
                                      ;; geometry's fetch — the server
                                      ;; ends the window at the line
                                      ;; holding the byte
                                      (map? around)
                                      (str "&byte=" (:byte around))
                                      :else
                                      (str "&start=" (max 0 (- around overscan))))
                                "&count=" cnt))
                  (.then (fn [win]
                           (let [anchor (or anchor (view-anchor))
                                 held-start (when (:win @state)
                                              (:start-line (:win @state)))]
                             (swap! state assoc
                                    :win (if (map? around)
                                           (assoc win :anchor-byte (:byte around))
                                           win)
                                    :fetching false)
                             (render!)
                             (let [restored (when (and anchor (= path (:path @state)))
                                              ;; translate the offset whenever the
                                              ;; window actually moved, even for a
                                              ;; keep-view fetch: one that lands a
                                              ;; different start than the window on
                                              ;; screen (a stale re-sync racing a
                                              ;; scroll fetch) must not shift the
                                              ;; text either
                                              (restore-view! anchor
                                                             (or (not keep-view?)
                                                                 (not= held-start
                                                                       (:start-line win)))))]
                               ;; no line anchor held and the geometry
                               ;; drifted while the file loads (the
                               ;; total, hence the scale, changed
                               ;; under a mid-file reader): scale the
                               ;; offset by the geometry ratio, which
                               ;; keeps the reader at the same file
                               ;; position — without it the window's
                               ;; block walks away from the viewport as
                               ;; the total grows and the page sits
                               ;; white until the user scrolls
                               (when (and keep-view? (not restored) pre-top
                                          (= path (:path @state))
                                          (<= (js/Math.abs
                                               (- (.-scrollTop (by-id "scroll")) pre-top)) 1)
                                          (not (== pre-ppl (scroll-scale))))
                                 (set! (.-scrollTop (by-id "scroll"))
                                       (* pre-top (/ (scroll-scale) pre-ppl))))))
                           (when-let [w (:want-start @state)]
                             (let [k (:want-keep @state)]
                               (swap! state assoc :want-start nil :want-keep nil)
                               (fetch-window! w k false)))
                           win))
                  (.catch (fn [_] (swap! state assoc :fetching false) nil))))
          (do (swap! state assoc :win nil)
              (render!)
              (js/Promise.resolve nil)))))))

(defn ensure-caret-visible! []
  (let [win (:win @state)
        pos (caret-pos win (:caret @state))]
    (when pos
      (let [sc (by-id "scroll")
            row-top (+ (win-start-px win) (* (first pos) (line-h)))
            top (.-scrollTop sc)
            bottom (+ top (.-clientHeight sc))]
        (cond
          (< row-top top) (set! (.-scrollTop sc) row-top)
          (> (+ row-top (line-h)) bottom)
          (set! (.-scrollTop sc) (+ row-top (line-h) (- (.-clientHeight sc)))))))))

(defn refresh-window! []
  (let [win (:win @state)
        pos (caret-pos win (:caret @state))]
    (if pos
      (fetch-window! (:n (nth (:lines win) (first pos))) true false)
      ;; the caret is outside the window the page holds (a restored
      ;; session, a find or an undo that jumped across the file):
      ;; re-anchoring at the old window would leave it there, with no
      ;; caret and no position to show — fetch at the caret instead
      (fetch-window! :caret true false))))

(defn after-op! [res]
  (if (:ok res)
    (do (swap! state assoc :status nil)
        (ensure-caret-visible!)
        (-> (refresh-window!)
            (.then (fn [_] (ensure-caret-visible!) (render!))))
        (update-status!))
    (say! (:error res)))
  res)

(defn watch-load!
  "While any buffer is still loading or being indexed, re-sync the
   state about once a second: the skeleton's total settles as the
   scan finishes, the scroll bar takes the document's real height,
   and the status bar counts the work up."
  []
  (when (and (or (seq (:loading @state)) (seq (:scanning @state)))
             (not (:load-watch @state)))
    (swap! state assoc :load-watch true)
    (js/setTimeout (fn []
                     (swap! state assoc :load-watch false)
                     (refresh-state!))
                   900)))

(defn refresh-state!
  "Re-read /api/state (tabs, the active buffer and its caret) and fetch
   its window: the one flow after an op that changes which buffers exist
   or which is active. When the active buffer is the one already on
   screen, the window is re-fetched where the user is looking — a
   re-sync (the load watch ticking, a theme change) must not move the
   view; anchoring at the caret on every tick yanked a reader who had
   scrolled down back to the caret, which on a fresh open sits at the
   top of the file. Only a change of buffer (or no window yet) opens
   at the caret."
  []
  (let [prev-path (:path @state)
        prev-win (:win @state)
        ;; the load handoff: the buffer on screen was loading when
        ;; this sync began — if the fresh state says it is not any
        ;; more, the scroll space changes denomination (file bytes
        ;; to document lines) in this very sync. Capture the scroll
        ;; fraction off the live DOM now, before anything
        ;; re-renders, and re-apply it under the new geometry once
        ;; the window lands, so the reader stays at the same place
        ;; in the file across the flip
        flip? (boolean (and prev-path (get (:loading @state) prev-path)))
        flip-frac (when flip?
                    (when-let [sc (by-id "scroll")]
                      (let [h (.-scrollHeight sc)]
                        (when (pos? h) (/ (.-scrollTop sc) h)))))]
    (-> (get-edn "/api/state")
        (.then (fn [st]
                 (let [active (:active st)
                       b (get (:buffers st) active)]
                   (swap! state assoc :tabs (:tabs st) :path active
                          :project (:project st) :runtime (or (:runtime st) "Jolt")
                          :loading (:loading st) :scanning (:scanning st)
                          :caret (or (:cursor b) 0) :sel (:sel b)
                          :theme-choice (or (:theme st) (:theme-choice @state)))
                 (render-title!)
                 (render-tree!)
                 (watch-load!)
                 ;; the window to re-fetch is the one held NOW, not
                 ;; the one held when this sync began: the state
                 ;; read above is asynchronous, and a scroll fetch
                 ;; can land a newer window while it is in flight —
                 ;; re-fetching the stale one regressed the view to
                 ;; the older window, unanchored, and the text
                 ;; jumped backwards under a reader who was inching
                 ;; forwards (traced in the fetch log, Nov 18)
                 (if (and (= active prev-path) (seq (:lines (:win @state))))
                   (if (and flip-frac (= active prev-path)
                            ;; and the load is actually over: the
                            ;; entry was there when this sync began
                            ;; and is gone from the state it read.
                            ;; (Without this the branch fired on
                            ;; every load tick, re-fetching the held
                            ;; window and re-applying a stale
                            ;; fraction over the byte fetches.)
                            (not (get (:loading st) active)))
                     ;; the load handoff: the scroll space has just
                     ;; changed denomination under this buffer. No
                     ;; line anchor — it is denominated in the old
                     ;; geometry and would fight the fraction. Fetch
                     ;; the held window's place, then put the offset
                     ;; at the captured fraction of the new scroll
                     ;; space: the reader stays at the same place in
                     ;; the file across the flip
                     (-> (fetch-window! (+ (:start-line (:win @state)) overscan)
                                        false true)
                         (.then (fn [_]
                                  (when-let [sc (by-id "scroll")]
                                    (set! (.-scrollTop sc)
                                          (* flip-frac (.-scrollHeight sc))))
                                  (render!))))
                     ;; re-fetch the window the page already holds, at
                     ;; its own start: fetch-window! covers `around`
                     ;; by overscan lines above it, so the around for
                     ;; "this very window" is its start plus overscan.
                     ;; Passing its first line instead ratcheted the
                     ;; window one overscan earlier on every tick —
                     ;; the text slid up the page for as long as the
                     ;; file loaded, and the viewport ended up below
                     ;; the window altogether, looking at blank space.
                     ;; But when the viewport is not on the held
                     ;; window at all (it drifted off it while the
                     ;; geometry changed under the load, or the user
                     ;; jumped and no scroll event has landed yet),
                     ;; the held window is the wrong one to re-fetch:
                     ;; fetch the scroll position's window instead,
                     ;; or the page sits white next to a window it
                     ;; cannot see. In byte mode the position is a
                     ;; byte: the fetch anchors on the viewport's
                     ;; bottom byte, so the window it lands ends at
                     ;; the line the reader's bottom edge is on
                     (-> (fetch-window! (cond (rendered-top-line)
                                              (+ (:start-line (:win @state)) overscan)
                                              (byte-mode?) {:byte (bottom-byte)}
                                              :else (first-visible))
                                        true true)
                         (.then (fn [_] (render!)))))
                   ;; a new buffer on screen: the window opens at its
                   ;; caret, wherever in the file it sits — not at the
                   ;; top of the file
                   (-> (fetch-window! :caret true false)
                       (.then (fn [_] (ensure-caret-visible!) (render!)))))))))))

;; ---------------------------------------------------------------------------
;; rendering: the editor

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
   spans (later spans win, like jonquil.paint/style-at), the selection,
   and the bracket-match pair."
  [{:keys [start text spans]} sel match]
  (let [len (count text)
        bounds (into #{0 len}
                     (mapcat (fn [[_ c0 c1]] [c0 c1]) spans))
        bounds (cond-> bounds
                 sel (into [(max 0 (- (first sel) start)) (min len (- (second sel) start))])
                 match (into (mapcat (fn [p] (let [c (- p start)]
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
        box (by-id "win")
        lh (line-h)]
    (clear! box)
    (when win
      (let [theme (:theme (:theme s))
            styles (:styles (:theme s))
            gw (gutter-w (:total-lines win))
            numbers? (:line-numbers s)
            caret-line (when (:focused s) (first (caret-pos win (:caret s))))]
        (css! box (str "position:absolute;left:0;right:0;top:" (win-start-px win) "px;"))
        (doseq [[i line] (map-indexed vector (:lines win))]
          (let [line-no (inc (:n line))
                bp? (contains? (get-in s [:breakpoints (:path s)] #{}) line-no)
                row (css! (el "div" (if (= i caret-line) "line current" "line") nil)
                          (str "height:" lh "px;line-height:" lh "px;"))
                gut (css! (el "span" "gutter"
                              (str (when bp? "● ") (when numbers? line-no)))
                          (str "width:" gw "px;cursor:pointer;"
                               (when bp? "color:#d33;font-weight:bold;")))
                code (el "span" "code" nil)]
            (on! gut "mousedown"
                 (fn [e] (.preventDefault e) (.stopPropagation e)
                   (toggle-breakpoint! line-no)))
            (doseq [seg (line-segments line (:sel s) (:match win))]
              (.appendChild code
                            (css! (el "span" nil (:text seg))
                                  (segment-style styles (:kind seg) (:sel? seg) (:match? seg) theme))))
            ;; a selected line break shows as a selected space
            (when (and (:sel s)
                       (let [e (+ (:start line) (count (:text line)))]
                         (and (<= (first (:sel s)) e) (< e (second (:sel s))))))
              (.appendChild code (css! (el "span" nil " ")
                                       (str "background:" (:selection theme)))))
            (when (zero? (count (:text line)))
              (.appendChild code (el "span" nil " ")))
            (append! row gut code)
            (.appendChild box row)))))))

(defn caret-xy
  "[x y] of the caret inside the sizer, or nil when it is not in the window."
  []
  (let [s @state
        win (:win s)
        pos (caret-pos win (:caret s))]
    (when pos
      (let [[i col _line] pos]
        [(+ (gutter-w (:total-lines win)) code-pad-x (* col (:char-w @metrics)))
         (+ (win-start-px win) (* i (line-h)))]))))

(defn render-caret! []
  (let [s @state
        c (by-id "caret")
        xy (caret-xy)
        lh (line-h)]
    (if xy
      (let [[x y] xy]
        (if (:focused s)
          (css! c (str "position:absolute;width:2px;height:" lh "px;top:" y "px;left:" x
                       "px;background:" (get-in s [:theme :theme :caret])
                       ";animation:narcis-blink 1.1s steps(1) infinite;pointer-events:none;"))
          (css! c "display:none;"))
        ;; the hidden input sits at the caret, so IME and scrolling behave
        (css! (by-id "input")
              (str "position:absolute;width:10px;height:" lh "px;top:" y "px;left:" x
                   "px;opacity:0;border:0;padding:0;margin:0;resize:none;overflow:hidden;"
                   "background:transparent;color:transparent;outline:none;")))
      (css! c "display:none;"))))

(defn base-name [p] (last (str/split (or p "") #"/")))

(defn untitled? [p] (or (nil? p) (str/starts-with? p "untitled")))

(defn shown-name [p]
  (cond (nil? p) "Narcis"
        (untitled? p) "Untitled"
        :else (base-name p)))

(defn shown-dir
  "The folder of path as the header bar's subtitle: its last two parts."
  [p]
  (when (and p (not (untitled? p)))
    (let [parts (vec (butlast (remove str/blank? (str/split p #"/"))))]
      (cond (empty? parts) nil
            (<= (count parts) 2) (str "/" (str/join "/" parts))
            :else (str "…/" (str/join "/" (take-last 2 parts)))))))

(defn render-title! []
  (let [p (:path @state)]
    (set! (.-textContent (by-id "title")) (shown-name p))
    (let [sub (by-id "subtitle")
          d (shown-dir p)]
      (set! (.-textContent sub) (or d ""))
      (show! sub (some? d)))
    (set! (.-title js/document) (str (shown-name p) " — Narcis"))))

(defn render-tabs! []
  (let [s @state
        bar (by-id "tabs")]
    (clear! bar)
    (show! bar (> (count (:tabs s)) 1))
    (doseq [p (:tabs s)]
      (let [tab (el "div" (if (= p (:path s)) "tab active" "tab") nil)
            label (el "span" "tab-label" (shown-name p))
            x (el "span" "tab-close" "×")]
        (set! (.-title tab) p)
        (append! tab label x)
        (on! tab "mousedown"
             (fn [e]
               (.preventDefault e)
               (-> (op! {:op :activate :path p})
                   (.then (fn [_] (refresh-state!) (focus-editor!))))))
        (on! x "mousedown"
             (fn [e]
               (.preventDefault e)
               (.stopPropagation e)
               (-> (op! {:op :close :path p})
                   (.then (fn [_] (refresh-state!))))))
        (.appendChild bar tab)))))

(defn update-status! []
  (let [s @state
        win (:win s)
        pos (caret-pos win (:caret s))
        con (:console s)
        msg (or (:status s)
                (case (:status con)
                  "running" "Running…"
                  "waiting-input" "Waiting for input"
                  "Ready"))
        where (if (and win pos)
                (str "Ln " (inc (:n (nth (:lines win) (first pos)))) ", Col " (inc (second pos)))
                "")
        ;; the share of the file already built into documents —
        ;; the assembled prefix plus every extent (worker segments,
        ;; the parked tail, view stretches); the prefix alone sat
        ;; near 0% while most of a big file was built (measured:
        ;; "Loading 0%" two minutes into a 2 GB load). Once a
        ;; buffer this page saw loading is whole, the status says
        ;; so, so the switch from reading the file to the rope is
        ;; visible.
        loading (if-let [l (get (:loading s) (:path s))]
                  (when (and (:total l) (pos? (:total l)))
                    (swap! seen-loading conj (:path s))
                    (let [built (+ (or (:prefix-bytes l) (:loaded l) 0)
                                   (reduce + 0 (map second (:extent-bytes l))))]
                      (str " · Loading from file "
                           (min 99 (quot (* 100 built) (:total l))) "%")))
                  (when (contains? @seen-loading (:path s))
                    " · Whole document"))
        indexing (when-let [l (get (:scanning s) (:path s))]
                   (when (and (:total l) (pos? (:total l)))
                     (str " · Indexing " (quot (* 100 (:loaded l)) (:total l)) "%")))]
    (when-let [m (by-id "status-msg")] (set! (.-textContent m) msg))
    (when-let [p (by-id "status-pos")]
      (set! (.-textContent p) (str (:runtime s) "  " where (or loading indexing ""))))
    ;; the load line down the buffer's left side (see
    ;; build-dom!): visible while the active buffer is loading;
    ;; its segments are the materialized stretches of the file —
    ;; the hydrated prefix from the top, plus a band for every
    ;; extent navigation pulled in. The segment DOM is rebuilt
    ;; only when the segment signature changes
    (when-let [bar (by-id "loadbar")]
      (let [l (get (:loading s) (:path s))
            sc (by-id "scroll")
            ;; the strip's geometry is in bytes against the
            ;; file's byte length — exact from the first moment,
            ;; so a segment's place and thickness never move as
            ;; the scan's line count grows under it. Line
            ;; geometry only until the first scan table exists
            byte? (and l (some? (:prefix-bytes l)) (:total l) (pos? (:total l)))
            total (if byte? (:total l) (:total-lines l))
            pfx (if byte? (:prefix-bytes l) (:loaded-lines l))
            exts (if byte? (:extent-bytes l) (:extents l))]
        (if (and l sc total (pos? total))
          (do (show! bar true)
              (set! (.. bar -style -top) (str (.-offsetTop sc) "px"))
              (set! (.. bar -style -height) (str (.-clientHeight sc) "px"))
              (let [sig (str pfx "|" exts)]
                (when (not= sig (.-loadsig bar))
                  (set! (.-loadsig bar) sig)
                  (clear! bar)
                  (let [seg! (fn [start n]
                               (let [d (el "div" "loadseg" nil)]
                                 (css! d (str "position:absolute;left:0;width:100%;"
                                              "top:" (.toFixed (* 100 (/ start total)) 3) "%;"
                                              "height:max(1px," (.toFixed (* 100 (/ n total)) 3) "%);"
                                              "background:#2f7bff;box-shadow:0 0 6px rgba(47,123,255,.55);"))
                                 (append! bar d)))]
                    (seg! 0 pfx)
                    (doseq [[start n] exts]
                      (seg! start n))))))
          (show! bar false))))
    (when-let [stop (by-id "btn-stop")]
      (set! (.-disabled stop) (= "idle" (:status con))))))

(defn size-sizer! []
  (let [win (:win @state)
        sizer (by-id "sizer")]
    (if win
      (let [maxlen (reduce max 0 (map (fn [l] (count (:text l))) (:lines win)))
            gw (gutter-w (:total-lines win))
            w (+ gw (* (inc maxlen) (:char-w @metrics)) 40)]
        ;; the gutter's strip runs the pane's whole height, as jonquil's does
        (css! sizer (str "position:relative;min-height:100%;width:" w "px;min-width:100%;height:"
                         (if (byte-mode?)
                           (* (total-bytes) (px-per-byte))
                           (* (:total-lines win) (px-per-line))) "px;"
                         "background:linear-gradient(to right,var(--strip) " gw "px,transparent " gw "px);")))
      (css! sizer "position:relative;min-height:100%;"))))

(defn render! []
  (render-tabs!)
  (render-lines!)
  (render-caret!)
  (update-status!)
  (size-sizer!))

;; ---------------------------------------------------------------------------
;; theme — the stored choice lives server-side (the editor atom, via
;; :theme/use); this page resolves :auto against the system and draws the
;; resolved palette from /api/theme. The window's chrome (header bar,
;; buttons, status bar) takes its colours from the palette's background,
;; as a GTK theme's header bar sits beside its text view.

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

(defn parse-hex [h]
  (let [h (str/replace (or h "#ffffff") "#" "")
        h (if (= 3 (count h)) (apply str (mapcat (fn [c] [c c]) h)) h)]
    [(js/parseInt (subs h 0 2) 16) (js/parseInt (subs h 2 4) 16) (js/parseInt (subs h 4 6) 16)]))

(defn mix
  "Colour a mixed toward colour b by t (0..1), as a css rgb()."
  [a b t]
  (let [[r1 g1 b1] (parse-hex a) [r2 g2 b2] (parse-hex b)
        f (fn [x y] (js/Math.round (+ x (* t (- y x)))))]
    (str "rgb(" (f r1 r2) "," (f g1 g2) "," (f b1 b2) ")")))

(defn chrome-vars
  "The window's colours under theme t, as css custom properties."
  [t]
  (let [bg (:background t) fg (:text t) dark? (:dark? t)
        away (if dark? "#ffffff" "#000000")]
    (str ":root{"
         "--bg:" bg ";--fg:" fg ";--sel:" (:selection t) ";--caret:" (:caret t) ";"
         "--chrome:" (mix bg away (if dark? 0.10 0.125)) ";"
         "--chrome-border:" (mix bg away (if dark? 0.22 0.25)) ";"
         "--btn:" (if dark? (mix bg "#ffffff" 0.17) (mix bg "#ffffff" 0.72)) ";"
         "--btn-hover:" (if dark? (mix bg "#ffffff" 0.24) (mix bg "#000000" 0.02)) ";"
         "--btn-active:" (mix bg away (if dark? 0.30 0.20)) ";"
         "--btn-border:" (mix bg away (if dark? 0.30 0.22)) ";"
         "--strip:" (mix bg away (if dark? 0.05 0.035)) ";"
         "--statusbar:" (mix bg away (if dark? 0.07 0.055)) ";"
         "--muted:" (mix fg bg 0.45) ";"
         "--panel:" (if dark? (mix bg "#ffffff" 0.06) bg) ";"
         "--current:" (mix bg away 0.035) ";"
         "--accent:#3584e4;--accent-fg:#ffffff;"
         "--hover:" (mix bg "#3584e4" 0.22) ";"
         "}")))

(defn apply-theme! [payload]
  (swap! state assoc :theme payload :theme-name (name (get-in payload [:theme :name])))
  (set! (.-textContent (by-id "theme-css")) (chrome-vars (:theme payload)))
  (render!)
  (render-console!))

(defn apply-theme-choice!
  "Select choice (a name from theme-choices): persist it server-side,
   then fetch and apply the resolved palette."
  [choice]
  (swap! state assoc :theme-choice choice)
  (-> (op! {:op :theme/use :name choice :system-dark (system-dark?) :path nil})
      (.then (fn [_] (get-edn (str "/api/theme?name=" (resolved-name choice)))))
      (.then apply-theme!)))

(defn cycle-theme! []
  (let [choices (theme-choices)
        cur (:theme-choice @state)
        nxt (nth choices (mod (inc (.indexOf (to-array choices) cur)) (count choices)))]
    (apply-theme-choice! nxt)))

(defn set-font-px! [px]
  (swap! state assoc :font-px px)
  (set! (.-textContent (by-id "font-css"))
        (str ":root{--mono-px:" px "px;--line-h:" (line-h) "px;}"))
  (reset! metrics {:char-w (measure-char-w)})
  (refresh-window!)
  (render!))

(defn zoom! [dir]
  (let [cur (font-px)
        i (.indexOf (to-array zoom-sizes) cur)
        i (if (neg? i) 4 i)]
    (set-font-px! (case dir
                    :in (nth zoom-sizes (min (dec (count zoom-sizes)) (inc i)))
                    :out (nth zoom-sizes (max 0 (dec i)))
                    13))))

;; ---------------------------------------------------------------------------
;; dialogs — where jonquil opens a native file chooser, the page asks in
;; its own sheet: a path to type, and the folder's entries to pick from

(defn close-dialog! []
  (swap! state assoc :dialog nil)
  (show! (by-id "dialog-back") false)
  (focus-editor!))

(defn dialog-shell!
  "Show the dialog sheet with title; answers its body element."
  [title]
  (let [back (by-id "dialog-back")
        box (clear! (by-id "dialog"))]
    (.appendChild box (el "div" "dialog-title" title))
    (let [body (el "div" "dialog-body" nil)]
      (.appendChild box body)
      (show! back true)
      body)))

(defn dialog-buttons! [body ok-label on-ok]
  (let [row (el "div" "dialog-buttons" nil)
        cancel (el "button" "btn" "Cancel")
        ok (el "button" "btn suggested" ok-label)]
    (on! cancel "click" (fn [_] (close-dialog!)))
    (on! ok "click" (fn [_] (on-ok)))
    (append! row cancel ok)
    (.appendChild body row)))

(defn message-dialog! [title text]
  (let [body (dialog-shell! title)]
    (.appendChild body (el "div" "dialog-text" text))
    (dialog-buttons! body "OK" close-dialog!)
    (swap! state assoc :dialog {:kind :message})))

(defn text-dialog!
  "Ask for one line of text; (on-ok text) when confirmed."
  [title placeholder ok-label on-ok]
  (let [body (dialog-shell! title)
        input (el "input" "dialog-input" nil)
        go (fn [] (let [v (.-value input)] (close-dialog!) (when (seq v) (on-ok v))))]
    (set! (.-placeholder input) placeholder)
    (on! input "keydown" (fn [e]
                           (cond (= (.-key e) "Enter") (do (.preventDefault e) (go))
                                 (= (.-key e) "Escape") (close-dialog!))))
    (.appendChild body input)
    (dialog-buttons! body ok-label go)
    (swap! state assoc :dialog {:kind :text})
    (.focus input)))

(defn join-path [dir name]
  (if (str/ends-with? dir "/") (str dir name) (str dir "/" name)))

(defn path-dialog!
  "Ask for a path, browsing folders from start-dir. mode is :open (pick a
   file), :save (name a file) or :folder (pick a folder)."
  [title mode start-dir ok-label on-ok]
  (let [body (dialog-shell! title)
        input (el "input" "dialog-input" nil)
        listing (el "div" "dialog-list" nil)
        cur (atom start-dir)
        go (fn []
             (let [v (str/trim (.-value input))]
               (when (seq v)
                 (close-dialog!)
                 (on-ok v))))
        load! (fn load! [dir]
                (-> (get-edn (if dir
                               (str "/api/list?dir=" (js/encodeURIComponent dir))
                               "/api/list"))
                    (.then (fn [res]
                             (when (:dir res)
                               (reset! cur (:dir res))
                               (when (or (= mode :folder) (str/blank? (.-value input))
                                         (not= mode :save))
                                 (set! (.-value input)
                                       (if (= mode :folder) (:dir res) (join-path (:dir res) ""))))
                               (clear! listing)
                               (when (:parent res)
                                 (let [up (el "div" "dialog-row dir" "..")]
                                   (on! up "click" (fn [_] (load! (:parent res))))
                                   (.appendChild listing up)))
                               (doseq [{:keys [name dir?]} (:entries res)]
                                 (when-not (str/starts-with? name ".")
                                   (let [row (el "div" (if dir? "dialog-row dir" "dialog-row") name)
                                         p (join-path (:dir res) name)]
                                     (on! row "click"
                                          (fn [_]
                                            (cond dir? (load! p)
                                                  (= mode :folder) nil
                                                  :else (do (set! (.-value input) p)
                                                            (when (= mode :open) (go))))))
                                     (.appendChild listing row)))))))))]
    (set! (.-spellcheck input) false)
    (on! input "keydown" (fn [e]
                           (cond (= (.-key e) "Enter") (do (.preventDefault e) (go))
                                 (= (.-key e) "Escape") (close-dialog!))))
    (append! body input listing)
    (dialog-buttons! body ok-label go)
    (swap! state assoc :dialog {:kind :path})
    (load! start-dir)
    (.focus input)))

(defn current-dir []
  (let [p (:path @state)]
    (or (get-in @state [:tree :root])
        (when (and p (not (untitled? p)))
          (str/join "/" (butlast (str/split p #"/"))))
        (:project @state))))

;; ---------------------------------------------------------------------------
;; commands — the one table the menus, the header bar, the keys and the
;; palette all run. Keyed by jonquil's command ids; :keys is the chord
;; shown beside the item.

(declare do-op! open-find! find-step! toggle-tree! open-palette! close-menus!
         console-op! open-complete! quick-doc!)

(defn do-new! []
  (-> (op! {:op :new :path nil}) (.then (fn [_] (refresh-state!) (focus-editor!)))))

(defn open-path! [p]
  (-> (op! {:op :open :path p})
      (.then (fn [res]
               (if (:ok res)
                 (do (refresh-state!) (focus-editor!))
                 (say! (:error res)))))))

(defn do-open! []
  (path-dialog! "Open" :open (current-dir) "Open" open-path!))

(defn do-save-as! []
  (path-dialog! "Save As" :save (current-dir) "Save"
                (fn [p]
                  (-> (op! {:op :save-as :new-path p})
                      (.then (fn [res]
                               (if (:ok res)
                                 (do (refresh-state!) (say! (str "Saved " p)))
                                 (say! (str "Save As failed: " (:error res))))))))))

(defn do-save! []
  (if (untitled? (:path @state))
    (do-save-as!)
    (-> (op! {:op :save})
        (.then (fn [res]
                 (say! (if (:ok res)
                         (str "Saved " (base-name (:path @state)))
                         (str "Save failed: " (:error res)))))))))

(defn do-close-tab! []
  (when-let [p (:path @state)]
    (-> (op! {:op :close :path p}) (.then (fn [_] (refresh-state!))))))

(defn activate-tab! [p]
  (-> (op! {:op :activate :path p}) (.then (fn [_] (refresh-state!) (focus-editor!)))))

(defn step-tab! [d]
  (let [tabs (:tabs @state)
        i (.indexOf (to-array tabs) (:path @state))]
    (when (and (seq tabs) (<= 0 i))
      (activate-tab! (nth tabs (mod (+ i d) (count tabs)))))))

(defn rename-ui! []
  (text-dialog! "Rename Symbol" "new name" "Rename"
                (fn [v] (do-op! {:op :rename :name v}))))

(defn structural! [op-name]
  (-> (op! {:op :structural :name op-name})
      (.then (fn [res]
               (when (and (:ok res) (:copy res) (.-clipboard js/navigator))
                 (.catch (.writeText (.-clipboard js/navigator) (:copy res)) (fn [_] nil)))
               (after-op! res)))))

(defn toggle-focus! []
  (if (= (.-activeElement js/document) (by-id "console-in"))
    (focus-editor!)
    (focus-console!)))

(defn toggle-line-numbers! []
  (swap! state update :line-numbers not)
  (render!))

(defn about! []
  (message-dialog! "Narcis"
                   (str "Narcis — jonquil on Eve.\n\n"
                        "The buffer you edit is an eve.text document in an Eve disk atom: "
                        "every edit is one swap, every earlier version is still there, and "
                        "this page is a viewport onto it.\n\n"
                        "Runtime: " (:runtime @state) "\n"
                        "Theme: " (:theme-choice @state) " (" (:theme-name @state) ")")))

(def command-table
  {"tab/new" {:run do-new!}
   "file/open" {:run (fn [] (do-open!)) :keys "Ctrl+O"}
   "file/save" {:run (fn [] (do-save!)) :keys "Ctrl+S"}
   "file/save-as" {:run (fn [] (do-save-as!)) :keys "Ctrl+Shift+S"}
   "tab/close" {:run (fn [] (do-close-tab!))}
   "edit/undo" {:run (fn [] (do-op! {:op :undo})) :keys "Ctrl+Z"}
   "edit/redo" {:run (fn [] (do-op! {:op :redo})) :keys "Ctrl+Y"}
   "edit/toggle-comment" {:run (fn [] (do-op! {:op :toggle-comment})) :keys "Ctrl+/"}
   "edit/indent" {:run (fn [] (do-op! {:op :indent})) :keys "Tab"}
   "edit/unindent" {:run (fn [] (do-op! {:op :unindent})) :keys "Shift+Tab"}
   "edit/format-all" {:run (fn [] (do-op! {:op :format-all})) :keys "Ctrl+Shift+F"}
   "edit/format-selection" {:run (fn [] (do-op! {:op :format-selection})) :keys "Ctrl+Alt+F"}
   "edit/rename" {:run (fn [] (rename-ui!)) :keys "F2"}
   "tree/toggle" {:run (fn [] (toggle-tree!)) :keys "Ctrl+Shift+E"}
   "focus/toggle" {:run (fn [] (toggle-focus!)) :keys "F6"}
   "tab/next" {:run (fn [] (step-tab! 1)) :keys "Ctrl+}"}
   "tab/previous" {:run (fn [] (step-tab! -1)) :keys "Ctrl+{"}
   "view/zoom-in" {:run (fn [] (zoom! :in)) :keys "Ctrl+="}
   "view/zoom-out" {:run (fn [] (zoom! :out)) :keys "Ctrl+-"}
   "view/zoom-reset" {:run (fn [] (zoom! :reset)) :keys "Ctrl+0"}
   "view/cycle-theme" {:run (fn [] (cycle-theme!)) :keys "Ctrl+Shift+M"}
   "view/line-numbers" {:run (fn [] (toggle-line-numbers!))}
   "palette/open" {:run (fn [] (open-palette!)) :keys "Ctrl+Shift+P"}
   "find/open" {:run (fn [] (open-find!)) :keys "Ctrl+F"}
   "find/next" {:run (fn [] (find-step! :next)) :keys "Ctrl+G"}
   "find/previous" {:run (fn [] (find-step! :prev)) :keys "Ctrl+Shift+G"}
   "run/run" {:run (fn [] (console-op! {:op :run})) :keys "F5"}
   "run/frames" {:run (fn [] (if (get-in @state [:frames :open]) (close-frames!) (open-frames!)))}
   "run/break-on-error" {:run (fn [] (console-op! {:op :debug-toggle-break}))}
   "run/continue" {:run (fn [] (console-op! {:op :debug-continue})) :keys "F8"}
   "run/step-over" {:run (fn [] (console-op! {:op :debug-step :mode :over})) :keys "F10"}
   "run/step-into" {:run (fn [] (console-op! {:op :debug-step :mode :step})) :keys "F11"}
   "run/step-out" {:run (fn [] (console-op! {:op :debug-step :mode :out})) :keys "Shift+F11"}
   "run/selection" {:run (fn [] (console-op! {:op :run-selection})) :keys "Ctrl+E"}
   "run/form" {:run (fn [] (console-op! {:op :run-form})) :keys "Ctrl+Enter"}
   "run/stop" {:run (fn [] (console-op! {:op :stop})) :keys "Esc"}
   "console/clear" {:run (fn [] (console-op! {:op :clear})) :keys "Ctrl+L"}
   "completion/open" {:run (fn [] (open-complete!)) :keys "Ctrl+Space"}
   "help/quick-doc" {:run (fn [] (quick-doc!)) :keys "Ctrl+Q"}
   "help/version" {:run (fn [] (about!))}})

(def structural-chords
  "Structural editing's chords — jonquil's Calva set as it adapts to a
   browser (the chords the lane's page already used) — chord -> the
   structural op's name."
  {"Ctrl+Alt+." "slurp-forward"
   "Ctrl+Alt+," "barf-forward"
   "Ctrl+Alt+Shift+←" "slurp-backward"
   "Ctrl+Alt+Shift+→" "barf-backward"
   "Ctrl+Alt+S" "splice"
   "Ctrl+Alt+Shift+P" "wrap-round"
   "Ctrl+Alt+Shift+S" "wrap-square"
   "Ctrl+Alt+Shift+C" "wrap-curly"
   "Ctrl+Alt+Shift+E" "wrap-set"
   "Ctrl+Alt+Shift+Q" "wrap-quote"
   "Ctrl+Shift+Del" "kill-form"
   "Ctrl+Alt+Bksp" "kill-form-backward"
   "Ctrl+Del" "kill-list-forward"
   "Ctrl+Bksp" "kill-list-backward"
   "Ctrl+Alt+Shift+Del" "splice-killing-forward"
   "Ctrl+Alt+Shift+Bksp" "splice-killing-backward"
   "Alt+↓" "drag-forward"
   "Alt+↑" "drag-backward"
   "Ctrl+Alt+T" "transpose"
   "Ctrl+Shift+J" "join"
   "Ctrl+Shift+C" "convolute"
   "Alt+Shift+→" "expand-selection"
   "Alt+Shift+←" "shrink-selection"
   "Ctrl+Shift+→" "select-forward"
   "Ctrl+Shift+←" "select-backward"
   "Ctrl+Alt+→" "forward"
   "Ctrl+Alt+←" "backward"
   "Ctrl+↑" "up"
   "Ctrl+↓" "down"
   "Ctrl+Alt+↓" "forward-up"
   "Ctrl+Alt+↑" "backward-down"
   "Ctrl+End" "list-end"
   "Ctrl+Home" "list-start"})

(def chord-of-op (into {} (map (fn [[chord op]] [op chord])) structural-chords))

(defn chord-of
  "The chord of key event e as structural-chords writes it, or nil for a
   key that is in none. The key is named by its position (e.code), so
   Alt changing the character it types does not change the chord."
  [e]
  (let [code (.-code e)
        k (cond
            (str/starts-with? code "Key") (subs code 3)
            (= code "Period") "."
            (= code "Comma") ","
            (= code "ArrowRight") "→"
            (= code "ArrowLeft") "←"
            (= code "ArrowUp") "↑"
            (= code "ArrowDown") "↓"
            (= code "Delete") "Del"
            (= code "Backspace") "Bksp"
            (= code "End") "End"
            (= code "Home") "Home"
            :else nil)]
    (when (and k (or (.-ctrlKey e) (.-altKey e)))
      (str (when (.-ctrlKey e) "Ctrl+") (when (.-altKey e) "Alt+") (when (.-shiftKey e) "Shift+") k))))

(defn command
  "The table entry for command id: the table's own, or a structural
   command (paredit/<name>, run by the server's structural op of that
   name)."
  [id]
  (or (get command-table id)
      (when (str/starts-with? id "paredit/")
        {:run (fn [] (structural! (subs id 8))) :keys (get chord-of-op (subs id 8))})))

(defn run-command! [id]
  (when-let [c (command id)]
    ((:run c))))

;; ---------------------------------------------------------------------------
;; menus — jonquil's, as the server narrowed them, drawn as a menu bar
;; (the browser has no application menu to put them in). Narcis adds
;; what jonquil keeps elsewhere: Undo/Redo (GTK's text view has its own),
;; the theme list and the palette.

(defn with-own-items [menus]
  (mapv (fn [m]
          (case (:label m)
            "Edit" (update m :sections
                           (fn [secs] (into [[{:command "edit/undo" :label "Undo"}
                                              {:command "edit/redo" :label "Redo"}]]
                                            secs)))
            "View" (update m :sections conj
                           [{:label "Theme" :theme-menu true}
                            {:command "view/line-numbers" :label "Line Numbers"}
                            {:command "palette/open" :label "Command Palette…"}])
            m))
        menus))

(defn close-menus! []
  (swap! state assoc :menu-open nil)
  (show! (by-id "menu-panel") false)
  (show! (by-id "menu-sub") false)
  (doseq [b (array-seq (.querySelectorAll js/document ".menu-button.open"))]
    (.remove (.-classList b) "open")))

(defn checked? [item]
  (case (:command item)
    "tree/toggle" (get-in @state [:tree :open])
    "view/line-numbers" (:line-numbers @state)
    false))

(declare fill-panel!)

(defn open-submenu! [item row]
  (let [sub (by-id "menu-sub")
        rect (.getBoundingClientRect row)]
    (fill-panel! sub item)
    (show! sub true)
    (let [h (.-offsetHeight sub)
          top (max 4 (min (.-top rect) (- (.-innerHeight js/window) h 8)))]
      (set! (.-top (.-style sub)) (str top "px"))
      (set! (.-left (.-style sub)) (str (- (.-right rect) 2) "px")))))

(defn menu-row [item]
  (let [row (el "div" "menu-item" nil)
        sub? (or (:sections item) (:theme-menu item))
        c (when (:command item) (command (:command item)))]
    (append! row
             (el "span" "menu-tick" (if (or (:checked item) (checked? item)) "✓" ""))
             (el "span" "menu-label" (:label item))
             (cond sub? (el "span" "menu-keys" "▸")
                   (:keys c) (el "span" "menu-keys" (:keys c))))
    (if sub?
      (do (on! row "mouseenter" (fn [_] (open-submenu! item row)))
          (on! row "click" (fn [e] (.stopPropagation e) (open-submenu! item row))))
      (do (on! row "mouseenter"
               (fn [_] (when (= "menu-panel" (.-id (.-parentNode row)))
                         (show! (by-id "menu-sub") false))))
          (on! row "click"
               (fn [_]
                 (close-menus!)
                 (focus-editor!)
                 (if (:run item) ((:run item)) (run-command! (:command item)))))))
    row))

(defn fill-panel! [panel menu]
  (clear! panel)
  (if (:theme-menu menu)
    (doseq [t (:themes @state)]
      (.appendChild panel
                    (menu-row {:label (str (:label t)
                                           (when (= "auto" (:name t))
                                             (str " (" (resolved-name "auto") ")")))
                               :checked (= (:name t) (:theme-choice @state))
                               :run (fn [] (apply-theme-choice! (:name t)))})))
    (doseq [[i section] (map-indexed vector (:sections menu))]
      (when (pos? i) (.appendChild panel (el "div" "menu-sep" nil)))
      (doseq [item section]
        (.appendChild panel (menu-row item))))))

(defn open-menu! [label anchor]
  (let [panel (by-id "menu-panel")
        menu (first (filter (fn [m] (= label (:label m))) (:menus @state)))
        rect (.getBoundingClientRect anchor)]
    (close-menus!)
    (fill-panel! panel menu)
    (set! (.-top (.-style panel)) (str (.-bottom rect) "px"))
    (set! (.-left (.-style panel)) (str (.-left rect) "px"))
    (show! panel true)
    (.add (.-classList anchor) "open")
    (swap! state assoc :menu-open label)))

(defn build-menus! []
  (let [bar (clear! (by-id "menubar"))]
    (doseq [m (:menus @state)]
      (let [b (el "button" "menu-button" (:label m))]
        (.setAttribute b "data-menu" (:label m))
        (on! b "mousedown"
             (fn [e]
               (.preventDefault e)
               (.stopPropagation e)
               (if (= (:menu-open @state) (:label m))
                 (close-menus!)
                 (open-menu! (:label m) b))))
        (on! b "mouseenter"
             (fn [_]
               (when (and (:menu-open @state) (not= (:menu-open @state) (:label m)))
                 (open-menu! (:label m) b))))
        (.appendChild bar b)))))

;; ---------------------------------------------------------------------------
;; command palette — every command the menus name, plus the themes

(declare close-palette!)

(defn flat-commands
  "[{:label :command}] of every command item in menus, submenu items
   under their submenu's name."
  [menus]
  (letfn [(walk [prefix item]
            (cond (:command item) [{:label (str prefix (:label item)) :command (:command item)}]
                  (:sections item) (mapcat (fn [i] (walk (str prefix (:label item) ": ") i))
                                           (apply concat (:sections item)))
                  :else []))]
    (vec (mapcat (fn [m] (mapcat (fn [i] (walk "" i)) (apply concat (:sections m)))) menus))))

(defn palette-items []
  (let [q (str/lower-case (get-in @state [:palette :query]))
        cmds (map (fn [{:keys [label command]}]
                    {:label label :keys (:keys (narcis.web/command command))
                     :run (fn [] (run-command! command))})
                  (conj (flat-commands (:menus @state))
                        {:label "Complete the Word" :command "completion/open"}))
        thms (map (fn [t] {:label (str "Use theme: " (:label t))
                           :keys nil
                           :run (fn [] (apply-theme-choice! (:name t)))})
                  (:themes @state))
        all (concat cmds thms)]
    (if (str/blank? q)
      (vec all)
      (vec (filter (fn [i] (str/includes? (str/lower-case (:label i)) q)) all)))))

(defn render-palette! []
  (let [box (clear! (by-id "palette-list"))
        items (palette-items)
        sel (min (get-in @state [:palette :sel]) (max 0 (dec (count items))))]
    (doseq [[i item] (map-indexed vector items)]
      (let [row (el "div" (if (= i sel) "palette-row sel" "palette-row") nil)]
        (.appendChild row (el "span" nil (:label item)))
        (when (:keys item) (.appendChild row (el "span" "menu-keys" (:keys item))))
        (on! row "click" (fn [_] (close-palette!) (focus-editor!) ((:run item))))
        (.appendChild box row)
        (when (= i sel) (.scrollIntoView row #js {:block "nearest"}))))
    (when (empty? items)
      (.appendChild box (el "div" "palette-row muted" "No matching commands")))))

(defn close-palette! []
  (swap! state assoc-in [:palette :open] false)
  (show! (by-id "palette") false))

(defn open-palette! []
  (swap! state assoc :palette {:open true :query "" :sel 0})
  (show! (by-id "palette") true)
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
      (when-let [item (nth items (min sel (max 0 (dec (count items)))) nil)]
        (close-palette!)
        (focus-editor!)
        ((:run item))))))

;; ---------------------------------------------------------------------------
;; find bar — drives the :find-next/:find-prev ops; the server selects
;; the match, this page shows it

(defn find-step! [dir]
  (let [q (get-in @state [:find :query])]
    (if (seq q)
      (-> (op! {:op (if (= dir :next) :find-next :find-prev) :query q})
          (.then (fn [res]
                   (if (:ok res)
                     (after-op! res)
                     (say! (str "Find: " (:error res)))))))
      (open-find!))))

(defn open-find! []
  (swap! state assoc-in [:find :open] true)
  (show! (by-id "findbar") true)
  (let [input (by-id "find-input")]
    (when (seq (get-in @state [:find :query]))
      (set! (.-value input) (get-in @state [:find :query])))
    (.focus input)
    (.select input)))

(defn close-find! []
  (swap! state assoc-in [:find :open] false)
  (show! (by-id "findbar") false)
  (focus-editor!))

;; ---------------------------------------------------------------------------
;; file tree — the project folder, its folders opening in place

(defn load-dir!
  "Fetch dir's entries into the tree's :kids; answers a promise."
  [dir]
  (-> (get-edn (if dir (str "/api/list?dir=" (js/encodeURIComponent dir)) "/api/list"))
      (.then (fn [res]
               (when (:dir res)
                 (swap! state assoc-in [:tree :kids (:dir res)] (:entries res)))
               res))))

(defn open-tree-at! [dir]
  (-> (load-dir! dir)
      (.then (fn [res]
               (when (:dir res)
                 (swap! state update :tree assoc :open true :root (:dir res)))
               (render-tree!)))))

(defn tree-rows! [panel dir depth]
  (doseq [{:keys [name dir?]} (get-in @state [:tree :kids dir])]
    (when-not (str/starts-with? name ".")
      (let [p (join-path dir name)
            open? (contains? (get-in @state [:tree :expanded]) p)
            row (el "div" (cond (= p (:path @state)) "tree-row active"
                                dir? "tree-row dir"
                                :else "tree-row")
                    nil)]
        (css! row (str "padding-left:" (+ 8 (* depth 14)) "px;"))
        (append! row
                 (el "span" "tree-arrow" (cond (not dir?) "" open? "▾" :else "▸"))
                 (el "span" nil name))
        (on! row "click"
             (fn [_]
               (if dir?
                 (if open?
                   (do (swap! state update-in [:tree :expanded] disj p) (render-tree!))
                   (do (swap! state update-in [:tree :expanded] conj p)
                       (-> (load-dir! p) (.then (fn [_] (render-tree!))))))
                 (open-path! p))))
        (.appendChild panel row)
        (when (and dir? open?)
          (tree-rows! panel p (inc depth)))))))

(defn render-tree! []
  (let [panel (by-id "tree")
        {:keys [open root]} (:tree @state)
        btn (by-id "btn-files")]
    (when btn
      (if open (.add (.-classList btn) "active") (.remove (.-classList btn) "active")))
    (show! panel open)
    (when open
      (clear! panel)
      (let [head (el "div" "tree-head" nil)
            name (el "span" "tree-name" (base-name root))
            refresh (el "button" "btn small" "⟳")
            folder (el "button" "btn small" "Open Folder…")]
        (set! (.-title name) (or root ""))
        (set! (.-title refresh) "Reload the folder")
        (on! refresh "click"
             (fn [_]
               (-> (js/Promise.all
                    (to-array (map load-dir! (cons root (get-in @state [:tree :expanded])))))
                   (.then (fn [_] (render-tree!))))))
        (on! folder "click"
             (fn [_] (path-dialog! "Open Folder" :folder root "Open"
                                   (fn [d] (swap! state assoc-in [:tree :expanded] #{})
                                     (open-tree-at! d)))))
        (append! head name refresh folder)
        (.appendChild panel head))
      (let [rows (el "div" "tree-rows" nil)]
        (tree-rows! rows root 0)
        (.appendChild panel rows)))))

(defn toggle-tree! []
  (if (get-in @state [:tree :open])
    (do (swap! state assoc-in [:tree :open] false)
        (render-tree!))
    (open-tree-at! (or (get-in @state [:tree :root]) (:project @state)))))

;; ---------------------------------------------------------------------------
;; the Interactions console — the transcript in its Eve atom, read by
;; position; what is typed at the prompt goes to the evaluator

(defn console-style [kind]
  (let [st (get-in @state [:theme :console kind])
        theme (get-in @state [:theme :theme])]
    (str "color:" (or (:fg st) (:text theme)) ";"
         (when (:bold st) "font-weight:bold;")
         (when (:italic st) "font-style:italic;"))))

(defn render-console!
  "Redraw the transcript from entry index `from` on (the entries before
   it are on screen already), and the prompt."
  ([] (render-console! 0))
  ([from]
   (when-let [out (by-id "console-out")]
     (let [{:keys [entries status ns]} (:console @state)
           pane (by-id "console")
           stick? (< (- (.-scrollHeight pane) (.-scrollTop pane) (.-clientHeight pane)) 30)]
       (while (> (.-length (.-childNodes out)) from)
         (.removeChild out (.-lastChild out)))
       (doseq [[kind text] (drop from entries)]
         (.appendChild out (css! (el "span" nil text) (console-style kind))))
       (let [prompt (by-id "console-prompt")]
         (set! (.-textContent prompt) (str ns "=> "))
         (css! prompt (console-style "prompt"))
         (show! prompt (= status "idle")))
       (when stick? (set! (.-scrollTop pane) (.-scrollHeight pane)))))))

(defonce poll-timer (atom nil))

(defn take-console!
  "Fold a /api/console answer into the page's transcript."
  [res]
  (when (and res (contains? res :entries))
    (let [{:keys [entries base]} (:console @state)
          ;; :start is the oldest entry the transcript still keeps: what
          ;; the page holds from before it (trimmed, or cleared) goes
          start (or (:start res) base)
          gone (max 0 (min (count entries) (- start base)))
          entries (vec (drop gone entries))
          base (max base start)
          from (:from res)
          keep-n (max 0 (min (count entries) (- from base)))
          entries (into (vec (take keep-n entries)) (:entries res))]
      (swap! state assoc :console {:entries entries :base base :next (:next res)
                                   :status (:status res) :ns (:ns res)})
      (render-console! (if (pos? gone) 0 keep-n))))
  (when (and res (contains? res :status) (not (contains? res :entries)))
    (swap! state update :console assoc :status (:status res) :ns (:ns res)))
  (when (and res (contains? res :debug))
    (let [was (boolean (get-in @state [:debug :paused?]))
          now (boolean (:paused? (:debug res)))]
      (swap! state assoc :debug (:debug res))
      (cond
        (and now (not was)) (open-frames!)
        (and was (not now)) (do (swap! state assoc-in [:frames :data] nil)
                                (render-frames!)))))
  (update-status!))

(defn poll-console! []
  (when-let [t @poll-timer] (js/clearTimeout t))
  (reset! poll-timer nil)
  ;; the last entry may have grown: read again from it
  (let [since (max 0 (dec (get-in @state [:console :next])))]
    (-> (get-edn (str "/api/console?since=" since))
        (.then (fn [res]
                 (take-console! res)
                 (when (not= "idle" (:status res))
                   (reset! poll-timer (js/setTimeout poll-console! 120)))))
        (.catch (fn [_] nil)))))

(defn console-op!
  "Send one message to the console (Run, Stop, the prompt's input), after
   every edit queued before it, then read what it said."
  [msg]
  (chained
   (fn []
     (let [s @state
           msg (merge {:path (:path s) :caret (:caret s) :sel (:sel s)}
                      (when (= :run (:op msg))
                        {:breakpoints (vec (get-in s [:breakpoints (:path s)]))})
                      msg)]
       (-> (fetch-edn "/api/console" #js {:method "POST" :body (pr-str msg)})
           (.then (fn [res]
                    (when (and (false? (:ok res)) (:error res)) (say! (:error res)))
                    (when (:ok res) (swap! state assoc :status nil))
                    (take-console! res)
                    (poll-console!)
                    res)))))))

;; ---------------------------------------------------------------------------
;; the debugger: breakpoints in the gutter, the Frames panel

(defn toggle-breakpoint!
  "Mark or unmark line (1-based) of the active buffer. The first mark
   arms the console's breakpoint hook; a failed arm (a Jolt without
   jolt.debug) takes the marks away again, as upstream's does."
  [line]
  (let [path (:path @state)
        cur (get-in @state [:breakpoints path] #{})
        on? (not (contains? cur line))
        next (if on? (conj cur line) (disj cur line))
        first? (and on? (empty? cur))
        last? (and (not on?) (empty? next))]
    (swap! state assoc-in [:breakpoints path] next)
    (render-lines!)
    (when (or first? last?)
      (-> (console-op! {:op :debug-arm :on on?})
          (.then (fn [res]
                   (when (:error res)
                     (swap! state assoc-in [:breakpoints path] #{})
                     (render-lines!))))))))

(defn open-frames!
  "Show the Frames panel with the pause's (or last error's) frames."
  []
  (swap! state assoc-in [:frames :open] true)
  (-> (console-op! {:op :debug-frames})
      (.then (fn [res]
               (when (:frames res)
                 (swap! state assoc-in [:frames :data] (:frames res))
                 (swap! state assoc-in [:frames :frame] 0)
                 (swap! state assoc-in [:frames :local] nil)
                 (render-frames!))))))

(defn close-frames! []
  (swap! state assoc-in [:frames :open] false)
  (render-frames!))

(defn frames-eval! []
  (let [ta (by-id "frames-eval-in")
        code (.-value ta)
        d (get-in @state [:frames :data])]
    (when (and d (not (str/blank? code)))
      (-> (console-op! {:op :debug-eval :error-id (:error-id d)
                        :frame (get-in @state [:frames :frame]) :code code})
          (.then (fn [res]
                   (swap! state assoc-in [:frames :eval]
                          (if (:error res)
                            (str "Error: " (:error res))
                            (str (when (seq (:out res)) (str (:out res) "\n")) (:text res))))
                   (render-frames!)))))))

(defn render-frames!
  "Draw the Frames panel: the pause's controls, the frame and local
   rows, one local's full text, and the evaluate-in-a-frame line."
  []
  (when-let [box (by-id "frames")]
    (let [s @state
          {:keys [open data frame local]} (:frames s)
          eval-text (:eval (:frames s))
          ;; a redraw must not eat what is being typed in the eval line
          typed (when-let [ta (by-id "frames-eval-in")] (.-value ta))
          dbg (:debug s)
          paused? (boolean (:paused? dbg))]
      (clear! box)
      (show! box open)
      (when open
        (css! box "border-top:1px solid #8884;padding:4px 8px;max-height:40%;overflow:auto;")
        (let [head (el "div" nil nil)
              title (el "span" nil (or (:title data) "Frames"))
              btn (fn [label f]
                    (let [b (el "button" nil label)]
                      (css! b "margin-left:6px;")
                      (on! b "click" f)
                      b))]
          (css! title "font-weight:bold;")
          (append! head title)
          (when paused?
            (append! head
                     (btn "Continue" (fn [] (console-op! {:op :debug-continue})))
                     (btn "Step Into" (fn [] (console-op! {:op :debug-step :mode :step})))
                     (btn "Step Over" (fn [] (console-op! {:op :debug-step :mode :over})))
                     (btn "Step Out" (fn [] (console-op! {:op :debug-step :mode :out})))
                     (btn "Stop" (fn [] (console-op! {:op :stop})))))
          (append! head
                   (btn (if (:break-on? dbg) "Break on error: on" "Break on error: off")
                        (fn [] (-> (console-op! {:op :debug-toggle-break})
                                   (.then (fn [_] (render-frames!))))))
                   (btn "✕" close-frames!))
          (.appendChild box head))
        (if (nil? data)
          (.appendChild box (el "div" nil (if (false? (:available dbg))
                                            "Frames need a Jolt with jolt.debug (this one has none)."
                                            "No frames yet: they come with a pause or an error.")))
          (do
            (doseq [row (:rows data)]
              (case (:kind row)
                :frame (let [r (el "div" nil (:text row))]
                         (css! r (str "cursor:pointer;font-weight:bold;"
                                      (when (= frame (:frame row)) "text-decoration:underline;")))
                         (on! r "click" (fn [] (swap! state assoc-in [:frames :frame] (:frame row))
                                         (render-frames!)))
                         (.appendChild box r))
                :local (let [r (el "div" nil (:text row))]
                         (css! r "cursor:pointer;white-space:pre;")
                         (on! r "click"
                              (fn [] (-> (console-op! {:op :debug-local :error-id (:error-id data)
                                                       :frame (:frame row) :local (:name row)})
                                         (.then (fn [res]
                                                  (swap! state assoc-in [:frames :local]
                                                         (or (:text res) (:error res)))
                                                  (render-frames!))))))
                         (.appendChild box r))
                (.appendChild box (el "div" nil (:text row)))))
            (when local
              (.appendChild box (css! (el "pre" nil local) "white-space:pre-wrap;margin:4px 0;")))
            (let [row (el "div" nil nil)
                  inp (with-id (el "input" nil nil) "frames-eval-in")
                  b (el "button" nil "Eval in frame")]
              (set! (.-placeholder inp) "evaluate in the selected frame")
              (when typed (set! (.-value inp) typed))
              (css! inp "width:60%;margin-right:6px;")
              (on! inp "keydown" (fn [e] (when (= "Enter" (.-key e)) (.preventDefault e) (frames-eval!))))
              (on! b "click" (fn [] (frames-eval!)))
              (append! row inp b)
              (.appendChild box row))
            (when eval-text
              (.appendChild box (css! (el "pre" nil eval-text) "white-space:pre-wrap;margin:4px 0;")))))))))

(defn grow-console-input! []
  (let [ta (by-id "console-in")]
    (set! (.-rows ta) (max 1 (count (str/split (.-value ta) #"\n" -1))))))

(defn console-key! [e]
  (let [ta (by-id "console-in")
        k (.-key e)
        cmd (or (.-metaKey e) (.-ctrlKey e))
        single? (not (str/includes? (.-value ta) "\n"))]
    (cond
      (= k "Enter")
      (do (.preventDefault e)
          (let [text (.-value ta)]
            (-> (console-op! {:op :input :text text :shift (.-shiftKey e)})
                (.then (fn [res]
                         (case (:action res)
                           "newline" (do (set! (.-value ta) (str (.-value ta) "\n"))
                                         (grow-console-input!))
                           ("eval" "stdin") (do (set! (.-value ta) "") (grow-console-input!))
                           "busy" (say! "A program is running. Stop it first.")
                           nil))))))
      (and (= k "ArrowUp") single?)
      (do (.preventDefault e)
          (-> (console-op! {:op :history :dir :prev :current (.-value ta)})
              (.then (fn [res] (when (:ok res) (set! (.-value ta) (or (:text res) "")))))))
      (and (= k "ArrowDown") single?)
      (do (.preventDefault e)
          (-> (console-op! {:op :history :dir :next :current (.-value ta)})
              (.then (fn [res] (when (:ok res) (set! (.-value ta) (or (:text res) "")))))))
      (and (= k "Escape") (not= "idle" (get-in @state [:console :status])))
      (do (.preventDefault e) (run-command! "run/stop"))
      (= k "Escape") (focus-editor!)
      (= k "F6") (do (.preventDefault e) (focus-editor!))
      (= k "F5") (do (.preventDefault e) (run-command! "run/run"))
      (and cmd (= k "l")) (do (.preventDefault e) (run-command! "console/clear"))
      :else nil)))

;; ---------------------------------------------------------------------------
;; completion and quick documentation

(defn close-complete! []
  (when (:complete @state)
    (swap! state assoc :complete nil))
  (show! (by-id "completion") false))

(defn place-popup!
  "Put popup just under the caret (above it when there is no room)."
  [popup]
  (when-let [[x y] (caret-xy)]
    (let [rect (.getBoundingClientRect (by-id "sizer"))
          left (+ (.-left rect) x)
          top (+ (.-top rect) y (line-h) 2)
          h (.-offsetHeight popup)
          w (.-offsetWidth popup)
          top (if (> (+ top h) (- (.-innerHeight js/window) 8))
                (max 4 (- (+ (.-top rect) y) h 2))
                top)
          left (max 4 (min left (- (.-innerWidth js/window) w 8)))]
      (set! (.-top (.-style popup)) (str top "px"))
      (set! (.-left (.-style popup)) (str left "px")))))

(defn render-complete! []
  (let [popup (by-id "completion")
        {:keys [candidates sel doc]} (:complete @state)
        list (clear! (by-id "completion-list"))]
    (doseq [[i c] (map-indexed vector candidates)]
      (let [row (el "div" (if (= i sel) "completion-row sel" "completion-row") nil)]
        (append! row (el "span" nil (:symbol c)) (el "span" "completion-kind" (:category c)))
        (on! row "mousedown"
             (fn [e]
               (.preventDefault e)
               (swap! state assoc-in [:complete :sel] i)
               (accept-complete!)))
        (.appendChild list row)
        (when (= i sel) (.scrollIntoView row #js {:block "nearest"}))))
    (set! (.-textContent (by-id "completion-doc")) (or doc ""))
    (show! popup true)
    (place-popup! popup)))

(defn fetch-complete-doc! []
  (when-let [{:keys [candidates sel]} (:complete @state)]
    (when-let [c (nth candidates sel nil)]
      (-> (op! {:op :doc :symbol (:symbol c) :category (:category c)})
          (.then (fn [res]
                   (when (and (:complete @state)
                              (= (:symbol c) (:symbol (nth (:candidates (:complete @state))
                                                           (:sel (:complete @state)) nil))))
                     (swap! state assoc-in [:complete :doc] (when (:ok res) (:doc res)))
                     (render-complete!))))))))

(defn open-complete! []
  (-> (op! {:op :complete})
      (.then (fn [res]
               (if (and (:ok res) (seq (:candidates res)))
                 (do (swap! state assoc :complete {:start (:start res) :end (:end res)
                                                   :candidates (:candidates res)
                                                   :sel 0 :doc nil})
                     (render-complete!)
                     (fetch-complete-doc!))
                 (do (close-complete!)
                     (when (:ok res) (say! "No completions"))))))))

(defn refresh-complete!
  "After a keystroke while the popup is open: ask again, or close when
   the word is gone."
  []
  (when (:complete @state)
    (-> (op! {:op :complete})
        (.then (fn [res]
                 (if (and (:complete @state) (:ok res) (seq (:candidates res))
                          (seq (:prefix res)))
                   (do (swap! state assoc :complete {:start (:start res) :end (:end res)
                                                     :candidates (:candidates res)
                                                     :sel 0 :doc nil})
                       (render-complete!)
                       (fetch-complete-doc!))
                   (close-complete!)))))))

(defn move-complete! [d]
  (let [{:keys [candidates sel]} (:complete @state)
        n (count candidates)]
    (when (pos? n)
      (swap! state update :complete assoc :sel (mod (+ sel d) n) :doc nil)
      (render-complete!)
      (fetch-complete-doc!))))

(defn ^:export accept-complete! []
  (when-let [{:keys [start end candidates sel]} (:complete @state)]
    (when-let [c (nth candidates sel nil)]
      (close-complete!)
      ;; the word under the caret is replaced: sent as the selection the
      ;; insert replaces — queued like every caret move, so the insert
      ;; op behind it reads this caret, not an older one
      (chain-step!
       (fn [] (swap! state assoc :caret end :sel (when (< start end) [start end]))))
      (do-op! {:op :insert-text :text (:symbol c)}))))

(defn close-doc! [] (show! (by-id "quickdoc") false))

(defn quick-doc! []
  (-> (op! {:op :doc})
      (.then (fn [res]
               (if (:ok res)
                 (let [popup (by-id "quickdoc")]
                   (set! (.-textContent popup) (:doc res))
                   (show! popup true)
                   (place-popup! popup))
                 (say! (or (:error res) "No documentation")))))))

;; ---------------------------------------------------------------------------
;; caret motion (local; synced to the server debounced and with every op)

(defn sync-caret! []
  (when-let [t (:sync-timer @state)] (js/clearTimeout t))
  (swap! state assoc :sync-timer
         (js/setTimeout
          ;; the caret is read when the op runs, after every op queued
          ;; before it — never the caret of when the timer fired
          (fn [] (op! {:op :set-caret}))
          250)))

(defn set-caret-local! [offset sel]
  (chain-step!
   (fn []
     (swap! state assoc :caret offset :sel sel :goal-col nil)
     (render!)
     (sync-caret!))))

(defn extend-sel
  "The selection after moving the caret from `from` to `to` with Shift:
   it keeps its far end."
  [sel from to]
  (let [anchor (cond (nil? sel) from
                     (= from (first sel)) (second sel)
                     :else (first sel))]
    (when (not= anchor to) [(min anchor to) (max anchor to)])))

(defn move-lines! [n extend?]
  (chain-step!
   (fn []
     (let [s @state
           win (:win s)
           pos (caret-pos win (:caret s))]
       (when pos
         (let [[_ col line] pos
               goal (or (:goal-col s) col)
               target (max 0 (min (dec (:total-lines win)) (+ (:n line) n)))
               place! (fn [tl]
                        (let [off (+ (:start tl) (min goal (count (:text tl))))]
                          (swap! state assoc :caret off :goal-col goal
                                 :sel (when extend? (extend-sel (:sel s) (:caret s) off)))
                          (ensure-caret-visible!)
                          (render!)
                          (sync-caret!)))]
           (if (and (<= (:start-line win) target)
                    (< target (+ (:start-line win) (count (:lines win)))))
             (place! (nth (:lines win) (- target (:start-line win))))
             ;; the target line is not loaded: fetch around it, then place
             (do (swap! state assoc :goal-col goal)
                 (-> (fetch-window! target true false)
                     (.then (fn []
                              (when-let [tl (first (filter (fn [l] (= (:n l) target))
                                                           (:lines (:win @state))))]
                                (place! tl)))))))))))))

(defn move-horizontal! [d extend?]
  (chain-step!
   (fn []
     (let [s @state
           total (or (:total-chars (:win s)) 0)
           ;; a selection collapses to the end the arrow points at
           off (if (and (:sel s) (not extend?))
                 (if (neg? d) (first (:sel s)) (second (:sel s)))
                 (max 0 (min total (+ (:caret s) d))))]
       (swap! state assoc :caret off :goal-col nil
              :sel (when extend? (extend-sel (:sel s) (:caret s) off)))
       (ensure-caret-visible!)
       (render!)
       (sync-caret!)))))

(defn line-edge! [end? extend?]
  (chain-step!
   (fn []
     (let [s @state
           win (:win s)
           pos (caret-pos win (:caret s))]
       (when pos
         (let [line (nth (:lines win) (first pos))
               target (if end? (+ (:start line) (count (:text line))) (:start line))]
           (swap! state assoc :caret target :goal-col nil
                  :sel (when extend? (extend-sel (:sel s) (:caret s) target)))
           (render!)
           (sync-caret!)))))))

;; ---------------------------------------------------------------------------
;; keyboard — jonquil's keymap for the ported commands

(defn do-op! [msg]
  (-> (op! msg) (.then after-op!)))

(defn window-key!
  "The chords that work wherever the focus is (jonquil's :window keys).
   True when e was one."
  [e]
  (let [k (.-key e)
        cmd (or (.-metaKey e) (.-ctrlKey e))
        shift (.-shiftKey e)
        alt (.-altKey e)
        lower (str/lower-case k)
        run (fn [id] (.preventDefault e) (run-command! id) true)]
    (cond
      ;; the debugger's keys, live only while a pause is being served
      (and (= k "F8") (get-in @state [:debug :paused?]))
      (do (.preventDefault e) (console-op! {:op :debug-continue}) true)
      (and (= k "F10") (get-in @state [:debug :paused?]))
      (do (.preventDefault e) (console-op! {:op :debug-step :mode :over}) true)
      (and (= k "F11") shift (get-in @state [:debug :paused?]))
      (do (.preventDefault e) (console-op! {:op :debug-step :mode :out}) true)
      (and (= k "F11") (get-in @state [:debug :paused?]))
      (do (.preventDefault e) (console-op! {:op :debug-step :mode :step}) true)
      (and (= k "F5") shift) (run "run/stop")
      (= k "F5") (run "run/run")
      (and cmd (not shift) (not alt) (= lower "r")) (run "run/run")
      (and cmd shift (not alt) (= lower "p")) (run "palette/open")
      (and cmd (not shift) (= lower "k")) (run "palette/open")
      (and cmd (not shift) (not alt) (= lower "o")) (run "file/open")
      (and cmd shift (not alt) (= lower "s")) (run "file/save-as")
      (and cmd (not shift) (not alt) (= lower "s")) (run "file/save")
      (and cmd shift (not alt) (= lower "e")) (run "tree/toggle")
      (and cmd (not shift) (not alt) (= lower "e")) (run "run/selection")
      (and cmd (not shift) (not alt) (= lower "l")) (run "console/clear")
      (and cmd (not shift) (not alt) (= lower "f")) (run "find/open")
      (and cmd (not alt) (= lower "g")) (run (if shift "find/previous" "find/next"))
      (and cmd shift (= lower "m")) (run "view/cycle-theme")
      (and cmd (or (= k "=") (= k "+"))) (run "view/zoom-in")
      (and cmd (= k "-")) (run "view/zoom-out")
      (and cmd (= k "0")) (run "view/zoom-reset")
      (and cmd (= k "}")) (run "tab/next")
      (and cmd (= k "{")) (run "tab/previous")
      (and (= k "F6") (not shift)) (run "focus/toggle")
      :else false)))

;; ---------------------------------------------------------------------------
;; typing, batched: plain characters collect and go as ONE :insert-text
;; op — one swap on the server, one undo step — per burst. A burst
;; ends when it pauses (batch-quiet-ms), has run batch-max-ms, or
;; anything queues behind it: op!/chain-step! seal the burst first,
;; freezing its characters into their own send step on the chain, and
;; characters typed afterwards start a fresh burst behind the queued
;; work. The structural characters :type-char treats specially (the
;; pairs and the quote) and typing while the completion menu is open
;; keep the per-character path.
;;
;; Sealing at queue time is the whole design. The first cut waited
;; inside the chain and sent whatever text stood when the wait
;; ended: brackets queued behind the pause ran after the text
;; ((defn twice [x] (* 2 x)) landed as (defn twice x * 2 x[]())),
;; and freezing the text only when the step ran still swept
;; characters typed after a bracket into the insert before it
;; (…[x *]( 2 x)). A burst's text is fixed the moment anything
;; queues behind it — never read later.

(def ^:private batch-quiet-ms 120)
(def ^:private batch-max-ms 600)
(defonce ^:private type-batch (atom {:text "" :first 0 :timer nil}))

(defn- seal-batch!
  "End the current burst, if any: queue its characters as one
   :insert-text op and clear the accumulator."
  []
  (let [t (:text @type-batch)]
    (when (seq t)
      (when-let [tm (:timer @type-batch)] (js/clearTimeout tm))
      (swap! type-batch assoc :text "" :first 0 :timer nil)
      (chained (fn [] (-> (op-send! {:op :insert-text :text t})
                          (.then (fn [res]
                                   (after-op! res)
                                   (refresh-complete!)
                                   res))))))))

(defn- arm-batch-timer!
  "The burst's own end: batch-quiet-ms after the last character,
   and no later than batch-max-ms after the first."
  []
  (when-let [tm (:timer @type-batch)] (js/clearTimeout tm))
  (let [wait (max 0 (min batch-quiet-ms
                         (- (+ (:first @type-batch) batch-max-ms)
                            (js/Date.now))))]
    (swap! type-batch assoc :timer (js/setTimeout seal-batch! wait))))

(defn type-batched!
  "One plain typed character into the batch."
  [ch]
  (let [now (js/Date.now)]
    (swap! type-batch (fn [b]
                        (if (seq (:text b))
                          (assoc b :text (str (:text b) ch))
                          (assoc b :text (str ch) :first now))))
    (arm-batch-timer!)))

(defn complete-key!
  "Keys the completion popup takes while it is open. True when taken."
  [e]
  (when (:complete @state)
    (let [k (.-key e)]
      (cond
        (= k "ArrowDown") (do (.preventDefault e) (move-complete! 1) true)
        (= k "ArrowUp") (do (.preventDefault e) (move-complete! -1) true)
        (or (= k "Enter") (= k "Tab")) (do (.preventDefault e) (accept-complete!) true)
        (= k "Escape") (do (.preventDefault e) (close-complete!) true)
        :else false))))

(defn on-keydown [e]
  (when-not (.-isComposing e)
    (let [k (.-key e)
          cmd (or (.-metaKey e) (.-ctrlKey e))
          ctrl (.-ctrlKey e)
          shift (.-shiftKey e)
          alt (.-altKey e)
          lower (str/lower-case k)
          s @state
          typed! (fn [msg]
                   (.preventDefault e)
                   (-> (do-op! msg) (.then (fn [_] (refresh-complete!)))))]
      (close-doc!)
      (cond
        (complete-key! e) nil

        ;; an open menu takes Escape first, then a running program
        (and (:menu-open s) (= k "Escape")) (do (.preventDefault e) (close-menus!))
        (and (= k "Escape") (not= "idle" (get-in s [:console :status])))
        (do (.preventDefault e) (run-command! "run/stop"))
        (and (= k "Escape") (:sel s)) (do (.preventDefault e) (set-caret-local! (:caret s) nil))

        (window-key! e) nil

        ;; jonquil's :editor keys
        (and cmd (= k "Enter")) (do (.preventDefault e) (run-command! "run/form"))
        (and ctrl (= k " ")) (do (.preventDefault e) (open-complete!))
        (and ctrl (not (.-metaKey e)) (= lower "q")) (do (.preventDefault e) (quick-doc!))
        (= k "F1") (do (.preventDefault e) (quick-doc!))
        (and cmd (or (= k "/") (= k ";"))) (do (.preventDefault e) (do-op! {:op :toggle-comment}))
        (and cmd shift (= lower "f")) (do (.preventDefault e) (do-op! {:op :format-all}))
        (and cmd alt (or (= lower "f") (= lower "l"))) (do (.preventDefault e) (do-op! {:op :format-selection}))
        (and cmd (= lower "y")) (do (.preventDefault e) (do-op! {:op :redo}))
        (and cmd (= lower "z")) (do (.preventDefault e) (do-op! {:op (if shift :redo :undo)}))
        (and cmd (= lower "a"))
        (do (.preventDefault e)
            (let [n (or (:total-chars (:win s)) 0)]
              (chain-step!
               (fn []
                 (swap! state assoc :caret n :sel (when (pos? n) [0 n]))
                 (render!)
                 (sync-caret!)))))
        (or (= k "F2") (and (= k "F6") shift)) (do (.preventDefault e) (rename-ui!))

        ;; structural editing
        (get structural-chords (chord-of e))
        (do (.preventDefault e) (close-complete!) (structural! (get structural-chords (chord-of e))))

        (= k "Enter") (typed! {:op :smart-enter})
        (= k "Tab") (do (.preventDefault e) (do-op! {:op (if shift :unindent :indent)}))
        (= k "Backspace") (typed! {:op :backspace})
        (= k "Delete") (typed! {:op :delete-forward})

        (= k "ArrowLeft") (do (.preventDefault e) (close-complete!) (move-horizontal! -1 shift))
        (= k "ArrowRight") (do (.preventDefault e) (close-complete!) (move-horizontal! 1 shift))
        (= k "ArrowUp") (do (.preventDefault e) (move-lines! -1 shift))
        (= k "ArrowDown") (do (.preventDefault e) (move-lines! 1 shift))
        (= k "PageUp") (do (.preventDefault e) (move-lines! (- (page-lines)) shift))
        (= k "PageDown") (do (.preventDefault e) (move-lines! (page-lines) shift))
        (= k "Home") (do (.preventDefault e) (line-edge! false shift))
        (= k "End") (do (.preventDefault e) (line-edge! true shift))

        (and (not cmd) (not alt) (= 1 (count k)))
        (if (or (:complete s) (contains? #{"(" ")" "[" "]" "{" "}" "\""} k))
          (typed! {:op :type-char :ch k})
          (do (.preventDefault e) (type-batched! k)))

        :else nil))))

;; ---------------------------------------------------------------------------
;; mouse

(defn event-pos [e]
  (let [rect (.getBoundingClientRect (by-id "sizer"))
        x (- (.-clientX e) (.-left rect))
        y (- (.-clientY e) (.-top rect))
        win (:win @state)]
    (when win
      (let [line-n (max 0 (min (dec (:total-lines win))
                               (+ (:start-line win)
                                  (js/Math.floor (/ (- y (win-start-px win))
                                                    (line-h))))))
            gw (gutter-w (:total-lines win))
            col (js/Math.max 0 (js/Math.round (/ (- x gw code-pad-x) (:char-w @metrics))))
            line (first (filter (fn [l] (= (:n l) line-n)) (:lines win)))]
        (when line
          (+ (:start line) (min col (count (:text line)))))))))

(defn on-mousedown [e]
  ;; preventDefault first: the browser's own mousedown default moves
  ;; focus to the clicked (non-focusable) element AFTER this handler
  ;; runs, undoing the programmatic focus.
  (.preventDefault e)
  (close-complete!)
  (close-doc!)
  (focus-editor!)
  (when-let [off (event-pos e)]
    (if (.-shiftKey e)
      (chain-step!
       (fn []
         (let [s @state]
           (swap! state assoc :caret off :sel (extend-sel (:sel s) (:caret s) off))
           (render!)
           (sync-caret!))))
      (do (swap! state assoc :drag-anchor off)
          (set-caret-local! off nil)))))

(defn on-mousemove [e]
  (when-let [a (:drag-anchor @state)]
    (.preventDefault e)
    (when-let [off (event-pos e)]
      (chain-step!
       (fn []
         (swap! state assoc :caret off :sel (when (not= a off) [(min a off) (max a off)]))
         (render!))))))

(defn on-mouseup [_]
  (when (:drag-anchor @state)
    (swap! state assoc :drag-anchor nil)
    (sync-caret!)))

(defn selection-text
  "The selected text, when the selection lies inside the loaded window
   (the only text the page holds); nil otherwise."
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
            (when (and (<= a le) (< le b)) (.push sb "\n"))))
        (str/join "" sb)))))

;; ---------------------------------------------------------------------------
;; the window

(def base-css
  "The window's stylesheet. Colours are the theme's custom properties
   (chrome-vars); sizes follow the font's (set-font-px!)."
  (str
   "html,body{margin:0;height:100%;overflow:hidden;}"
   "body{background:var(--bg);color:var(--fg);font:13px system-ui,-apple-system,'Segoe UI',Cantarell,sans-serif;}"
   "#app{display:flex;flex-direction:column;height:100vh;background:var(--bg);color:var(--fg);}"
   "button{font:inherit;color:inherit;}"
   ;; menu bar
   "#menubar{display:flex;align-items:center;flex:0 0 auto;padding:0 6px;height:26px;background:var(--chrome);border-bottom:1px solid var(--chrome-border);}"
   ".menu-button{background:transparent;border:0;padding:3px 9px;border-radius:4px;cursor:default;font-size:13px;}"
   ".menu-button:hover,.menu-button.open{background:var(--btn-active);}"
   ".menu-panel{position:fixed;z-index:40;min-width:220px;max-height:calc(100vh - 60px);overflow-y:auto;padding:5px 0;border-radius:7px;"
   "background:var(--panel);color:var(--fg);border:1px solid var(--chrome-border);box-shadow:0 8px 28px rgba(0,0,0,0.28);font-size:13px;}"
   ".menu-item{display:flex;align-items:center;gap:6px;padding:4px 14px 4px 6px;cursor:default;white-space:nowrap;}"
   ".menu-item:hover{background:var(--accent);color:var(--accent-fg);}"
   ".menu-tick{width:14px;flex:0 0 auto;text-align:center;}"
   ".menu-keys{margin-left:auto;padding-left:22px;opacity:0.6;font-size:12px;}"
   ".menu-sep{height:1px;margin:4px 0;background:var(--chrome-border);}"
   ;; header bar
   "#hdr{display:flex;align-items:center;gap:8px;flex:0 0 auto;height:46px;padding:0 8px;position:relative;"
   "background:var(--chrome);border-bottom:1px solid var(--chrome-border);}"
   ".btn{background:var(--btn);border:1px solid var(--btn-border);border-radius:6px;padding:6px 15px;cursor:default;font-size:13px;"
   "box-shadow:0 1px 1px rgba(0,0,0,0.06);white-space:nowrap;}"
   ".btn:hover{background:var(--btn-hover);}"
   ".btn:active,.btn.active{background:var(--btn-active);box-shadow:inset 0 1px 2px rgba(0,0,0,0.12);}"
   ".btn:disabled{opacity:0.5;}"
   ".btn.small{padding:4px 9px;font-size:12.5px;}"
   ".btn.suggested{background:var(--accent);color:var(--accent-fg);border-color:var(--accent);}"
   "#hdr-title{position:absolute;left:50%;transform:translateX(-50%);text-align:center;pointer-events:none;max-width:40%;}"
   "#title{font-weight:700;font-size:13.5px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;}"
   "#subtitle{font-size:11px;color:var(--muted);white-space:nowrap;overflow:hidden;text-overflow:ellipsis;}"
   ".spacer{flex:1 1 auto;}"
   ;; body
   "#main{display:flex;flex:1 1 auto;min-height:0;}"
   "#work{display:flex;flex-direction:column;flex:1 1 auto;min-width:0;}"
   ;; file tree
   "#tree{flex:0 0 250px;display:flex;flex-direction:column;border-right:1px solid var(--chrome-border);background:var(--bg);min-height:0;}"
   ".tree-head{display:flex;align-items:center;gap:6px;padding:5px 6px;background:var(--chrome);border-bottom:1px solid var(--chrome-border);}"
   ".tree-name{flex:1 1 auto;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;}"
   ".tree-rows{overflow:auto;flex:1 1 auto;padding:4px 0;}"
   ".tree-row{display:flex;align-items:center;height:26px;cursor:default;white-space:nowrap;padding-right:8px;}"
   ".tree-row:hover{background:var(--hover);}"
   ".tree-row.active{background:var(--accent);color:var(--accent-fg);}"
   ".tree-arrow{width:16px;flex:0 0 auto;font-size:10px;text-align:center;}"
   ;; tabs
   "#tabs{display:flex;flex:0 0 auto;background:var(--chrome);border-bottom:1px solid var(--chrome-border);overflow-x:auto;}"
   ".tab{display:flex;align-items:center;gap:14px;padding:8px 14px 6px 18px;cursor:default;border-bottom:3px solid transparent;white-space:nowrap;}"
   ".tab:hover{background:var(--btn-active);}"
   ".tab.active{border-bottom-color:var(--accent);}"
   ".tab-close{opacity:0.5;padding:0 3px;border-radius:3px;}"
   ".tab-close:hover{opacity:1;background:var(--btn-active);}"
   ;; find bar
   "#findbar{display:flex;align-items:center;gap:6px;flex:0 0 auto;padding:5px 8px;background:var(--chrome);border-bottom:1px solid var(--chrome-border);}"
   "#find-input{font:inherit;min-width:260px;padding:4px 7px;border:1px solid var(--btn-border);border-radius:5px;background:var(--bg);color:var(--fg);}"
   ;; editor
   "#scroll{flex:1 1 0;min-height:60px;overflow:auto;position:relative;cursor:text;background:var(--bg);"
   "font-family:" mono ";font-size:var(--mono-px);}"
   ".line{display:flex;}"
   ".line.current{background:var(--current);}"
   ".gutter{flex:0 0 auto;box-sizing:border-box;text-align:right;padding-right:10px;user-select:none;color:var(--muted);}"
   ".code{white-space:pre;padding-left:" code-pad-x "px;}"
   "@keyframes narcis-blink{0%,55%{opacity:1}56%,100%{opacity:0}}"
   ;; divider + console
   "#divider{flex:0 0 5px;cursor:row-resize;background:var(--bg);border-top:1px solid var(--chrome-border);}"
   "#console{flex:0 0 auto;overflow:auto;background:var(--bg);cursor:text;padding:2px 8px 8px 8px;"
   "font-family:" mono ";font-size:var(--mono-px);line-height:var(--line-h);}"
   "#console-out{white-space:pre-wrap;word-break:break-word;}"
   "#console-row{display:flex;align-items:flex-start;}"
   "#console-prompt{white-space:pre;flex:0 0 auto;}"
   "#console-in{flex:1 1 auto;font:inherit;line-height:inherit;color:var(--fg);background:transparent;border:0;outline:none;resize:none;padding:0;margin:0;overflow:hidden;}"
   ;; status bar
   "#status{display:flex;align-items:center;flex:0 0 auto;height:24px;padding:0 10px;position:relative;font-size:12.5px;"
   "background:var(--statusbar);border-top:1px solid var(--chrome-border);}"
   "#status-msg{position:absolute;left:50%;transform:translateX(-50%);white-space:nowrap;overflow:hidden;text-overflow:ellipsis;max-width:70%;}"
   "#status-pos{margin-left:auto;white-space:pre;}"
   ;; palette, completion, quick doc
   "#palette{position:fixed;z-index:50;top:64px;left:50%;transform:translateX(-50%);width:540px;max-width:92vw;padding:0;overflow:hidden;}"
   "#palette-input{font:inherit;width:100%;box-sizing:border-box;padding:10px 12px;border:0;border-bottom:1px solid var(--chrome-border);outline:none;background:transparent;color:inherit;}"
   "#palette-list{max-height:340px;overflow:auto;padding:4px 0;}"
   ".palette-row{display:flex;gap:8px;padding:5px 12px;cursor:default;}"
   ".palette-row.sel{background:var(--accent);color:var(--accent-fg);}"
   ".muted{opacity:0.6;}"
   "#completion{position:fixed;z-index:45;width:520px;max-width:92vw;padding:6px;border-radius:9px;"
   "background:var(--panel);border:1px solid var(--chrome-border);box-shadow:0 8px 28px rgba(0,0,0,0.25);"
   "font-family:" mono ";font-size:12.5px;}"
   "#completion-list{max-height:176px;overflow:auto;}"
   ".completion-row{display:flex;gap:10px;padding:2px 6px;cursor:default;white-space:nowrap;}"
   ".completion-row.sel{background:var(--hover);}"
   ".completion-kind{opacity:0.75;}"
   "#completion-doc{white-space:pre-wrap;max-height:150px;overflow:auto;padding:4px 6px 2px 6px;}"
   "#quickdoc{position:fixed;z-index:45;max-width:560px;max-height:300px;overflow:auto;padding:8px 10px;border-radius:9px;white-space:pre-wrap;"
   "background:var(--panel);border:1px solid var(--chrome-border);box-shadow:0 8px 28px rgba(0,0,0,0.25);"
   "font-family:" mono ";font-size:12.5px;}"
   ;; dialog
   "#dialog-back{position:fixed;z-index:60;inset:0;background:rgba(0,0,0,0.28);display:flex;align-items:flex-start;justify-content:center;}"
   "#dialog{margin-top:90px;width:560px;max-width:92vw;border-radius:10px;background:var(--panel);border:1px solid var(--chrome-border);"
   "box-shadow:0 14px 40px rgba(0,0,0,0.35);overflow:hidden;}"
   ".dialog-title{font-weight:700;text-align:center;padding:11px;background:var(--chrome);border-bottom:1px solid var(--chrome-border);}"
   ".dialog-body{padding:12px;}"
   ".dialog-text{white-space:pre-wrap;line-height:1.45;}"
   ".dialog-input{font:inherit;width:100%;box-sizing:border-box;padding:6px 8px;border:1px solid var(--btn-border);border-radius:6px;background:var(--bg);color:var(--fg);outline:none;}"
   ".dialog-input:focus{border-color:var(--accent);}"
   ".dialog-list{height:260px;overflow:auto;margin-top:8px;border:1px solid var(--chrome-border);border-radius:6px;background:var(--bg);}"
   ".dialog-row{padding:4px 10px;cursor:default;white-space:nowrap;}"
   ".dialog-row:hover{background:var(--hover);}"
   ".dialog-row.dir{font-weight:600;}"
   ".dialog-row.dir::after{content:'/';opacity:0.6;}"
   ".dialog-buttons{display:flex;justify-content:flex-end;gap:8px;margin-top:12px;}"))

(defn header-button [id label command-id]
  (let [b (el "button" "btn" label)]
    (set! (.-id b) id)
    (when-let [k (:keys (command command-id))] (set! (.-title b) k))
    ;; mousedown, not click: the button must not take the focus the
    ;; editor has
    (on! b "mousedown" (fn [e] (.preventDefault e)))
    (on! b "click" (fn [_] (run-command! command-id)))
    b))

(defn with-id [e id] (set! (.-id e) id) e)

(defn build-dom! []
  (let [app (clear! (by-id "app"))
        head (.-head js/document)]
    (doseq [[id text] [["base-css" base-css] ["theme-css" ""] ["font-css" ""]]]
      (.appendChild head (with-id (el "style" nil text) id)))
    ;; menu bar, header bar
    (let [menubar (with-id (el "div" nil nil) "menubar")
          hdr (with-id (el "div" nil nil) "hdr")
          title-box (with-id (el "div" nil nil) "hdr-title")]
      (append! title-box
               (with-id (el "div" nil "Narcis") "title")
               (with-id (el "div" nil "") "subtitle"))
      (append! hdr
               (header-button "btn-files" "≡ Files" "tree/toggle")
               (header-button "btn-open" "Open…" "file/open")
               (header-button "btn-new" "+" "tab/new")
               title-box
               (el "div" "spacer" nil)
               (header-button "btn-run" "▶ Run" "run/run")
               (header-button "btn-stop" "Stop" "run/stop")
               (header-button "btn-save" "Save…" "file/save"))
      (append! app menubar hdr))
    ;; file tree | tabs, find bar, editor, console
    (let [main (with-id (el "div" nil nil) "main")
          tree (show! (with-id (el "div" nil nil) "tree") false)
          work (with-id (el "div" nil nil) "work")
          tabs (show! (with-id (el "div" nil nil) "tabs") false)
          findbar (show! (with-id (el "div" nil nil) "findbar") false)
          find-input (with-id (el "input" nil nil) "find-input")
          prev-b (el "button" "btn small" "↑")
          next-b (el "button" "btn small" "↓")
          close-b (el "button" "btn small" "×")
          sc (with-id (el "div" nil nil) "scroll")
          loadbar (show! (with-id (el "div" nil nil) "loadbar") false)
          sizer (with-id (el "div" nil nil) "sizer")
          win (with-id (el "div" nil nil) "win")
          caret (with-id (el "div" nil nil) "caret")
          ta (with-id (el "textarea" nil nil) "input")
          divider (with-id (el "div" nil nil) "divider")
          console (with-id (el "div" nil nil) "console")
          con-out (with-id (el "div" nil nil) "console-out")
          frames (with-id (el "div" nil nil) "frames")
          con-row (with-id (el "div" nil nil) "console-row")
          con-prompt (with-id (el "span" nil "user=> ") "console-prompt")
          con-in (with-id (el "textarea" nil nil) "console-in")]
      ;; find bar
      (set! (.-placeholder find-input) "Find")
      (set! (.-spellcheck find-input) false)
      (on! find-input "input" (fn [_] (swap! state assoc-in [:find :query] (.-value find-input))))
      (on! find-input "keydown"
           (fn [e]
             (cond
               (= (.-key e) "Enter") (do (.preventDefault e) (find-step! (if (.-shiftKey e) :prev :next)))
               (= (.-key e) "Escape") (close-find!)
               :else (window-key! e))))
      (on! prev-b "click" (fn [_] (find-step! :prev)))
      (on! next-b "click" (fn [_] (find-step! :next)))
      (on! close-b "click" (fn [_] (close-find!)))
      (append! findbar find-input prev-b next-b close-b)
      ;; editor
      (css! sizer "position:relative;min-height:100%;")
      (css! caret "display:none;")
      (css! ta "position:absolute;opacity:0;width:10px;height:10px;")
      (set! (.-spellcheck ta) false)
      (.setAttribute ta "autocapitalize" "off")
      (.setAttribute ta "autocomplete" "off")
      (append! sizer win caret ta)
      (.appendChild sc sizer)
      ;; the load line: a thin blue strip down the buffer's left
      ;; side while a file is still loading into it — a map of
      ;; what is materialized in the store: the hydrated stretch
      ;; fills from the top, and every extent navigation pulled
      ;; in ahead of the frontier fills its own band, so a reader
      ;; can see the part they jumped to is on disk. When the
      ;; strip is blue end to end the buffer is whole and the
      ;; direct-from-disk jankiness is over. Positioned over the
      ;; scroll area and driven from update-status!
      (css! work "position:relative;")
      (css! loadbar "position:absolute;left:0;width:3px;z-index:6;pointer-events:none;background:transparent;")
      (append! work loadbar)
      ;; console
      (set! (.-rows con-in) 1)
      (set! (.-spellcheck con-in) false)
      (.setAttribute con-in "autocapitalize" "off")
      (append! con-row con-prompt con-in)
      (css! frames "display:none;")
      (append! console con-out frames con-row)
      (append! work tabs findbar sc divider console)
      (append! main tree work)
      (.appendChild app main)
      ;; editor events
      (on! sc "wheel" on-wheel)
      (on! sc "scroll"
           (fn [_]
             (close-complete!)
             (close-doc!)
             (let [win (:win @state)
                   top (rendered-top-line)]
               (when win
                 (if top
                   ;; keep the window around the line on screen,
                   ;; fetching for the middle of the visible rows
                   (when (or (< top (+ (:start-line win) 15))
                             (> (+ top (visible-count))
                                (+ (:start-line win) (count (:lines win)) -15)))
                     (fetch-window! (+ top (quot (visible-count) 2)) false false))
                   ;; the viewport is off the held window entirely
                   ;; (a teleport): fetch for the scroll position —
                   ;; a byte while the buffer loads, so the window
                   ;; lands ending at the line under the viewport's
                   ;; bottom edge, the file's last line included
                   ;; when the jump was to the very end
                   (fetch-window! (if (byte-mode?)
                                    {:byte (bottom-byte)}
                                    (first-visible))
                                  false false))))))
      (on! sizer "mousedown" on-mousedown)
      (on! js/document "mousemove" on-mousemove)
      (on! js/document "mouseup" on-mouseup)
      (on! ta "keydown" on-keydown)
      (on! ta "focus" (fn [_] (swap! state assoc :focused true) (render-lines!) (render-caret!)))
      (on! ta "blur" (fn [_] (swap! state assoc :focused false) (render-lines!) (render-caret!)))
      (on! ta "paste"
           (fn [e]
             (.preventDefault e)
             (let [txt (.getData (.-clipboardData e) "text")]
               (when (seq txt) (do-op! {:op :insert-text :text txt})))))
      (on! ta "copy"
           (fn [e]
             (when-let [[a b] (:sel @state)]
               (when-let [txt (selection-text a b)]
                 (.preventDefault e)
                 (.setData (.-clipboardData e) "text/plain" txt)))))
      (on! ta "cut"
           (fn [e]
             (when-let [[a b] (:sel @state)]
               (when-let [txt (selection-text a b)]
                 (.preventDefault e)
                 (.setData (.-clipboardData e) "text/plain" txt)
                 (do-op! {:op :insert-text :text ""})))))
      ;; console events
      (on! con-in "keydown" (fn [e] (when-not (window-key! e) (console-key! e))))
      (on! con-in "input" (fn [_] (grow-console-input!)))
      (on! console "mouseup"
           (fn [_]
             ;; a click in the console types at the prompt, unless it selected text
             (when (str/blank? (str (.getSelection js/window)))
               (focus-console!))))
      ;; the divider resizes the console
      (on! divider "mousedown"
           (fn [e]
             (.preventDefault e)
             (let [move (fn [ev]
                          (let [rect (.getBoundingClientRect work)
                                h (- (.-bottom rect) (.-clientY ev))
                                h (max 40 (min (- (.-height rect) 120) h))]
                            (swap! state assoc :console-h h)
                            (set! (.-height (.-style console)) (str h "px"))))
                   up (fn up [_]
                        (.removeEventListener js/document "mousemove" move)
                        (.removeEventListener js/document "mouseup" up)
                        (refresh-window!))]
               (.addEventListener js/document "mousemove" move)
               (.addEventListener js/document "mouseup" up))))
      (set! (.-height (.-style console)) "32vh"))
    ;; status bar
    (let [status (with-id (el "div" nil nil) "status")]
      (append! status
               (with-id (el "span" nil "Ready") "status-msg")
               (with-id (el "span" nil "") "status-pos"))
      (.appendChild app status))
    ;; overlays: menus, palette, completion, quick doc, dialog
    (let [body (.-body js/document)
          panel (show! (with-id (el "div" "menu-panel" nil) "menu-panel") false)
          sub (show! (with-id (el "div" "menu-panel" nil) "menu-sub") false)
          pal (show! (with-id (el "div" "menu-panel" nil) "palette") false)
          pal-in (with-id (el "input" nil nil) "palette-input")
          pal-list (with-id (el "div" nil nil) "palette-list")
          comp (show! (with-id (el "div" nil nil) "completion") false)
          quick (show! (with-id (el "div" nil nil) "quickdoc") false)
          back (show! (with-id (el "div" nil nil) "dialog-back") false)
          dialog (with-id (el "div" nil nil) "dialog")]
      (set! (.-placeholder pal-in) "Type a command or a theme…")
      (on! pal-in "input"
           (fn [_]
             (swap! state assoc :palette {:open true :query (.-value pal-in) :sel 0})
             (render-palette!)))
      (on! pal-in "keydown" palette-key!)
      (append! pal pal-in pal-list)
      (append! comp
               (with-id (el "div" nil nil) "completion-list")
               (with-id (el "div" nil nil) "completion-doc"))
      (.appendChild back dialog)
      (on! back "mousedown" (fn [e] (when (= (.-target e) back) (close-dialog!))))
      (append! body panel sub pal comp quick back)
      (on! js/document "mousedown"
           (fn [e]
             (let [t (.-target e)]
               (when (and (:menu-open @state)
                          (not (.contains panel t))
                          (not (.contains sub t))
                          (not (and (.-getAttribute t) (.getAttribute t "data-menu"))))
                 (close-menus!))
               (when (and (get-in @state [:palette :open]) (not (.contains pal t)))
                 (close-palette!))))))
    ;; keys that reach nothing focused still run window commands
    (on! js/document "keydown"
         (fn [e]
           (when (= (.-target e) (.-body js/document))
             (when-not (window-key! e)
               (when (and (= (.-key e) "Escape") (:dialog @state)) (close-dialog!))))))
    (on! js/window "resize" (fn [_] (refresh-window!)))
    ;; :auto follows the system while it is the stored choice
    (when (.-matchMedia js/window)
      (on! (.matchMedia js/window "(prefers-color-scheme: dark)") "change"
           (fn [_]
             (when (= "auto" (:theme-choice @state))
               (apply-theme-choice! "auto")))))))

(defn ^:export init []
  (build-dom!)
  (set! (.-textContent (by-id "font-css"))
        (str ":root{--mono-px:" (font-px) "px;--line-h:" (line-h) "px;}"))
  (reset! metrics {:char-w (measure-char-w)})
  (-> (get-edn "/api/menus")
      (.then (fn [menus]
               (swap! state assoc :menus (with-own-items menus))
               (build-menus!)
               (get-edn "/api/themes")))
      (.then (fn [th]
               (swap! state assoc :themes (:themes th) :theme-choice (:choice th))
               (get-edn "/api/state")))
      (.then (fn [st]
               (let [active (:active st)
                     b (get (:buffers st) active)]
                 (swap! state assoc :tabs (:tabs st) :path active
                        :project (:project st) :runtime (or (:runtime st) "Jolt")
                        :loading (:loading st) :scanning (:scanning st)
                        :caret (or (:cursor b) 0) :sel (:sel b)
                        :theme-choice (or (:theme st) (:theme-choice @state))))
               (render-title!)
               (watch-load!)
               (get-edn (str "/api/theme?name=" (resolved-name (:theme-choice @state))))))
      (.then (fn [payload]
               (apply-theme! payload)
               (poll-console!)
               ;; open at the restored caret, wherever in the file it
               ;; sits — the same anchoring refresh-state! does
               (-> (fetch-window! :caret true false)
                   (.then (fn [_] (ensure-caret-visible!) (render!))))
               (focus-editor!)))))
