(ns narcis.launcher
  "Editor server, media-cohost shape: open the buffer store by name,
   open the editor state in the Eve disk atom, serve the editor page +
   prebuilt cljs bundle, and answer the window/op API the page drives.
   The page renders rope windows fetched from here and sends edits
   back as narcis.ops messages — it never holds the text.

   It also holds the Interactions console (narcis.interactions): Run,
   the prompt, Stop, and the transcript the page reads.

   Usage: run -m narcis.launcher [--dir data] [--port 8377]
          [--serve-secs N] [--file path] [--theme name] [--web-dir web]
          [--project dir]
   --serve-secs omitted (or 0) serves until killed; the S0 gate passes
   it explicitly. Prints the NARCIS-S0 marker lines the S0 gate checks
   (store-impl, buffer-text-ok, served, ...) and NARCIS-S3 lines for
   the editor gates."
  #?(:clj (:require [clojure.java.io :as io]
                    [narcis.menus :as menus]
                    [narcis.store :as store]
                    [clojure.edn :as edn]
                    [clojure.string :as str]
                    [narcis.buffer :as buffer]
                    [narcis.interactions :as interactions]
                    [narcis.ops :as ops]
                    [narcis.text :as text]
                    [narcis.theme :as theme]
                    [narcis.view :as view]))
  #?(:clj (:import [java.net ServerSocket InetAddress URLDecoder]
                   [java.io BufferedReader InputStreamReader OutputStream File])))

;; The server's host seams: files, sockets, processes and the clock
;; are the clj hosts' shapes today; the request handling and the API
;; above them are every host's.
;; a request's numbers are file positions — bytes and characters of a
;; file that can be many gigabytes — so they parse as longs: an int
;; parse threw NumberFormatException for every offset past 2 GiB, the
;; window route answered 500, and the page fell back to the file's
;; head (measured on a 13.9 GB file: every jump past the first 15% of
;; the scrollbar snapped the view to line 1)
#?(:clj (defn- parse-int [s] #?(:clj (Long/parseLong s) :cljs (js/parseInt s 10))))
#?(:clj (defn- class-name [v] #?(:clj (.getName (class v)) :cljs (.-name (type v)))))
#?(:clj (defn- url-decode [s]
          #?(:clj (URLDecoder/decode s "UTF-8")
             :cljs (js/decodeURIComponent (str/replace s "+" " ")))))
#?(:clj
   (do (defn- existing-path [path] (when (.exists (File. ^String path)) path))
       (defn- file-parent [path] (some-> (File. ^String path) (.getAbsoluteFile) (.getParent)))
       (defn- list-dir
         "The /api/list payload for the directory at path, or nil when it
         is not one."
         [path]
         (let [dir (.getCanonicalFile (File. path))]
           (when (.isDirectory dir)
             {:dir (.getPath dir)
              :parent (some-> (.getParentFile dir) (.getPath))
              :entries (mapv (fn [^File f] {:name (.getName f) :dir? (.isDirectory f)})
                             (sort-by (fn [^File f]
                                        [(if (.isDirectory f) 0 1)
                                         (str/lower-case (.getName f))])
                                      (.listFiles dir)))})))))

(def hello-text
  "hello narcis\nthe buffer you are reading lives in a store opened by name.\nedit me in S2.\n")

(def page
  "<!doctype html><html><head><meta charset='utf-8'><title>Narcis</title>
<link rel='icon' href='data:,'>
<style>html,body{margin:0;height:100%}body{font-family:ui-monospace,Menlo,monospace}</style>
</head><body>
<div id='app'>loading Narcis…</div>
<script src='/app.js'></script>
<script>narcis.web.init();</script>
</body></html>")

#?(:clj
   (defn- page-html
     "The page with its script URL stamped by the bundle's
      modification time: a browser holding an older bundle in
      cache must not keep running it after a rebuild — a tab
      opened before an update otherwise goes on showing the old
      page's behavior (a load line that never appears, a scroll
      fix that never lands) while a fresh tab shows the new one."
     [web-dir]
     (let [f (File. web-dir "app.js")
           v (if (.exists f) (.lastModified f) 0)]
       (str/replace page "/app.js'" (str "/app.js?v=" v "'")))))

#?(:clj
   (defn- attempt-auto-open! [url]
     (let [os (str/lower-case (System/getProperty "os.name"))
           cmd (cond (str/includes? os "mac") ["open" url]
                     (str/includes? os "win") ["cmd" "/c" "start" url]
                     :else ["xdg-open" url])]
       (try
         (.start (ProcessBuilder. ^java.util.List cmd))
         (println "NARCIS-S0 auto-open attempted:" (str/join " " cmd))
         true
         (catch Throwable t
           (println "NARCIS-S0 auto-open failed (attempted):" (ex-message t))
           false)))))

;; ---------------------------------------------------------------------------
;; HTTP plumbing (S0's, with request bodies + a thread per connection)

#?(:clj (defn- parse-query [qs]
          (if (str/blank? qs)
            {}
            (into {}
                  (map (fn [pair]
                         (let [[k v] (str/split pair #"=" 2)]
                           [(keyword (url-decode k))
                            (url-decode (or v ""))])))
                  (str/split qs #"&")))))

#?(:clj (defn- read-request [^BufferedReader in]
          (let [request-line (.readLine in)]
            (when request-line
              (let [headers (loop [m {}]
                              (let [line (.readLine in)]
                                (if (or (nil? line) (str/blank? line))
                                  m
                                  (let [[k v] (str/split line #": " 2)]
                                    (recur (assoc m (str/lower-case k) v))))))
                    len (when-let [cl (get headers "content-length")]
                          (Integer/parseInt (str/trim cl)))
                    body (when len
                           (let [buf (char-array len)]
                             (loop [off 0]
                               (when (< off len)
                                 (let [n (.read in buf off (- len off))]
                                   (when (pos? n) (recur (+ off n))))))
                             (String. buf)))]
                {:method (first (str/split request-line #" +"))
                 :target (second (str/split request-line #" +"))
                 :body body})))))

#?(:clj (defn- respond! [^OutputStream out status body content-type]
          (let [bytes (.getBytes ^String body "UTF-8")
                head (str "HTTP/1.1 " status " OK\r\n"
                          "Content-Type: " content-type "\r\n"
                          "Content-Length: " (count bytes) "\r\n"
                          "Connection: close\r\n\r\n")]
            (.write out (.getBytes head "UTF-8"))
            (.write out bytes)
            (.flush out))))

;; ---------------------------------------------------------------------------
;; API

#?(:clj (defn- state-payload [ed theme-atom ctx]
          (let [[buffers doc-lines]
                (loop [ps (buffer/tabs ed) bs {} ds {}]
                  (if-let [p (first ps)]
                    (let [[bs ds] (if-let [s (buffer/snapshot ed p)]
                                    (let [sk (when (buffer/partial? ed p)
                                               (buffer/skeleton p))]
                                      [(assoc bs p {:lines (max (:lines s) (or (:lines sk) 0))
                                                    :chars (max (:chars s) (or (:chars sk) 0))
                                                    :cursor (:cursor s)
                                                    :sel (:sel s)
                                                    :undo (:undo s)
                                                    :redo (:redo s)})
                                       (assoc ds p (:lines s))])
                                    [bs ds])]
                      (recur (rest ps) bs ds))
                    [bs ds]))
        ;; a partial buffer's load, enriched for the page's load
        ;; line: how much of the file is materialized (the
        ;; hydrated prefix, in lines), the file's whole height,
        ;; and the extents navigation has pulled in ahead of the
        ;; frontier
                loading (into {}
                              (map (fn [[p l]]
                                     (let [;; blue: buffer B, the background
                                   ;; build's pieces; amber: buffer A,
                                   ;; the reader's pieces
                                           ranges (buffer/build-ranges ed p)
                                   ;; the load line's geometry, in
                                   ;; bytes against the file's byte
                                   ;; length — the one scale that is
                                   ;; exact from the first moment and
                                   ;; never grows under the segments
                                           scan (buffer/scan-of p)
                                           geo (when scan
                                                 {:prefix-bytes (text/byte-offset-of-line
                                                                 scan (get doc-lines p 0))
                                                  :extent-bytes (mapv (fn [[s n]]
                                                                        (let [b0 (text/byte-offset-of-line scan s)]
                                                                          [b0 (- (text/byte-offset-of-line scan (+ s n)) b0)]))
                                                                      ranges)
                                          ;; the stretches views read —
                                          ;; the temporary rope — drawn
                                          ;; over the built ones
                                                  :view-bytes (mapv (fn [[s n]]
                                                                      (let [b0 (text/byte-offset-of-line scan s)]
                                                                        [b0 (- (text/byte-offset-of-line scan (+ s n)) b0)]))
                                                                    (buffer/extent-ranges ed p))})
                                   ;; the parked tail's band, in
                                   ;; the same byte geometry: the
                                   ;; file's end, drawn from the
                                   ;; moment it is built
                                           tail-band (buffer/parked-tail-band p)
                                           geo (cond-> geo
                                                 tail-band
                                                 (update :extent-bytes
                                                         (fn [bs]
                                                           (conj (vec bs)
                                                                 [(first tail-band)
                                                                  (- (second tail-band)
                                                                     (first tail-band))]))))]
                                       [p (assoc l
                                                 :loaded-lines (get doc-lines p 0)
                                                 :total-lines (get-in buffers [p :lines] 0)
                                                 :extents ranges
                                                 :prefix-bytes (:prefix-bytes geo)
                                                 :extent-bytes (:extent-bytes geo)
                                                 :view-bytes (:view-bytes geo))])))
                              (buffer/loading-map ed))]
            {:tabs (buffer/tabs ed)
             :active (buffer/active ed)
             :loading loading
             :scanning (buffer/scanning-map)
             :project (:project ctx)
             :runtime "Jolt"
             :theme (name (or (buffer/theme-choice ed) (:name @theme-atom)))
             :buffers buffers})))

#?(:clj (defn- composed-payload
          "Document lines [start, start+count) of a loading buffer, as buffer A
   holds them: a piece's rows where one of A's pieces stands (its own
   document, edits and all, read in place), the file's rows elsewhere,
   shifted into the document frame by the line and char deltas of the
   edited pieces before them. A stretch read out of the file is noted
   as a new piece of A (the rope a reader's views populate). The root
   — buffer B, the background build — is never read here: it has none
   of A's edits until the load's one merge."
          [ed path start count total-lines total-chars]
          (let [scan (or (buffer/scan-of path) (atom {:chunks [] :done? false}))
                spans (buffer/extent-spans ed path)
                finish (fn [rows]
                         {:start-line start
                          :total-lines total-lines
                          :total-chars total-chars
                          :spans-mode :window
                          :match nil
                          :lines (mapv (fn [l] (if (:spans l) l (assoc l :spans []))) rows)})]
            (loop [line start rows []]
              (let [need (- count (clojure.core/count rows))
                    p (some (fn [sp] (when (and (<= (:key sp) line)
                                                (< line (+ (:key sp) (:lines sp))))
                                       sp))
                            spans)]
                (cond
                  (<= need 0) (finish rows)

                  p
                  (let [k (min need (- (+ (:key p) (:lines p)) line))
                        got (or (buffer/read-extent
                                 ed path line k
                                 (fn [s e]
                                   (mapv (fn [l] (-> l (update :n + s) (update :start + (:start-char e))))
                                         (:lines (view/window (:doc e)
                                                              {:start (- line s) :count k
                                                               :key [:extent path s (:chars e)]})))))
                                [])]
                    (if (seq got)
                      (recur (+ line (clojure.core/count got)) (into rows got))
                      (finish rows)))

                  :else
                  (let [before (filter (fn [sp] (<= (+ (:key sp) (:lines sp)) line)) spans)
                        dl (reduce + 0 (map (fn [sp] (- (:lines sp) (:file-lines sp))) before))
                        dc (reduce + 0 (map (fn [sp] (- (:chars sp) (:file-chars sp))) before))
                        nxt (some (fn [sp] (when (> (:key sp) line) (:key sp))) spans)
                        k (if nxt (min need (- nxt line)) need)
                        w (text/file-window path scan (- line dl) k)
                        ls (:lines w)]
                    (when (and (= (clojure.core/count ls) k) (not (:eof? w)))
                      (buffer/note-extent! ed path ls (:stream-start w) (:stream-end w)))
                    (let [rs (mapv (fn [l] (-> l (update :n + dl) (update :start + dc))) ls)]
                      (if (or (empty? ls) (:eof? w) (< (clojure.core/count ls) k))
                        (finish (into rows rs))
                        (recur (+ line (clojure.core/count ls)) (into rows rs)))))))))))

#?(:clj (defn- byte-window-payload
          "A loading buffer's window read straight from its file by byte (see
   narcis.text's reading by byte): the count lines ending with the
   line that holds byte b, in window-payload's shape, each line with
   its :byte, and :approx when the scan has not reached them yet (the
   line numbers and char offsets are then estimates). A stretch read
   with exact positions is noted as an extent, as a line read is — the
   temporary rope a reader's views build. Costs a seek and the
   window's own bytes anywhere in the file."
          [ed path b count]
          (let [scan (buffer/scan-of path)
                w (text/file-window-at-byte path scan b count)
                spans (buffer/extent-spans ed path)
                fl0 (or (:n (first (:lines w))) 0)
                edited? (fn [sp] (or (not= (:lines sp) (:file-lines sp))
                                     (not= (:chars sp) (:file-chars sp))))
        ;; A's pieces wholly before the window move its rows into the
        ;; document frame by their edits' deltas
                before (filter (fn [sp] (<= (+ (:file-start sp) (:file-lines sp)) fl0)) spans)
                dl (reduce + 0 (map (fn [sp] (- (:lines sp) (:file-lines sp))) before))
                dc (reduce + 0 (map (fn [sp] (- (:chars sp) (:file-chars sp))) before))
                touches-edit? (and (not (:approx? w))
                                   (some (fn [sp] (and (edited? sp)
                                                       (< (:file-start sp) (+ fl0 (clojure.core/count (:lines w))))
                                                       (> (+ (:file-start sp) (:file-lines sp)) fl0)))
                                         spans))
                lines (mapv (fn [l] (-> l (update :n + dl) (update :start + dc))) (:lines w))
                skel (buffer/skeleton path)
                doc-lines (or (buffer/read ed path (fn [s] (text/lines (:root s)))) 0)
                snap (buffer/snapshot ed path)]
            (when (and (not (:approx? w)) (not (:eof? w)) (= (clojure.core/count lines) count)
                       (not touches-edit?))
      ;; noted in the file frame, as a read of the file reports it
              (buffer/note-extent! ed path (mapv (fn [l] (dissoc l :byte)) (:lines w))
                                   (:stream-start w) (:stream-end w)))
            (merge
             (if touches-edit?
       ;; an edited piece of A is in the window: the rows come from
       ;; A as it stands, composed over the file (no :byte on them —
       ;; a reader's edit has no file byte)
               (composed-payload ed path (+ fl0 dl) count
                                 (max doc-lines (or (:lines skel) 0))
                                 (max (or (buffer/read ed path (fn [s] (text/chars (:root s)))) 0)
                                      (or (:chars skel) 0)))
               {:start-line (or (:n (first lines)) 0)
                :total-lines (max doc-lines (or (:lines skel) 0))
                :total-chars (max (or (buffer/read ed path (fn [s] (text/chars (:root s)))) 0)
                                  (or (:chars skel) 0))
                :spans-mode :window
                :match nil
                :lines (mapv (fn [l] (assoc l :spans [])) lines)})
             {:approx (:approx? w)
              :path path
              :caret (:cursor snap)
              :sel (:sel snap)
              :undo (:undo snap)
              :redo (:redo snap)
              :rev (buffer/rev ed path)
              :start-byte (:start-byte w)
              :total-bytes (get-in (buffer/loading-map ed) [path :total])}))))

#?(:clj (defn- window-payload [ed path start count]
          (let [doc-lines (buffer/read ed path (fn [s] (text/lines (:root s))))
                partial (buffer/partial? ed path)
                skel (when partial (buffer/skeleton path))
                total-lines (if partial (max doc-lines (or (:lines skel) 0)) doc-lines)
                total-chars (when partial
                              (max (buffer/read ed path (fn [s] (text/chars (:root s))))
                                   (or (:chars skel) 0)))
                base (buffer/read ed path
                                  (fn [s]
                                    (merge (view/window (:root s) {:start start :count count
                                                                   :caret (:cursor s)
                                                                   :key (:text-key s)})
                                           {:path path
                                            :caret (:cursor s)
                                            :sel (:sel s)
                                            :undo (:undo s)
                                            :redo (:redo s)})))]
            (assoc (if-not partial
             ;; a whole buffer: the document serves every window
             ;; (view/window clamps a count past the end)
                     (assoc base :total-lines total-lines
                            :total-chars (:total-chars base))
             ;; a loading buffer is buffer A: its pieces over the
             ;; file. The root is buffer B, the background build,
             ;; which holds none of A's edits until the load's merge
                     (merge (composed-payload ed path start count total-lines
                                              (or total-chars (:total-chars base)))
                            (select-keys base [:path :caret :sel :undo :redo])))
                   :rev (buffer/rev ed path)
           ;; the load-phase scroll geometry is denominated in
           ;; the file's bytes — the one quantity exact from t=0:
           ;; the page places this window's block at :start-byte
           ;; in a scroll space :total-bytes tall
                   :start-byte (when partial
                                 (text/byte-offset-of-line (buffer/scan-of path) start))
                   :total-bytes (when partial
                                  (get-in (buffer/loading-map ed) [path :total]))))))

#?(:clj
   (def ^:private menus-payload
     ;; jonquil's menus (narcis/menus.edn on the classpath), narrowed to
     ;; what Narcis runs
     (delay (pr-str (menus/menus (slurp (io/resource "narcis/menus.edn")))))))

#?(:clj (defn- display-name [path]
          (if (or (nil? path) (str/starts-with? path "untitled"))
            "Definitions"
            (last (str/split path #"/")))))

#?(:clj (defn- snapshot-of
          "The edit-op snapshot ({:text :caret :sel-start :sel-end}) of path at
   the message's caret, for the commands that read the text."
          [ed path msg]
          (buffer/read ed path
                       (fn [s]
                         (let [caret (get msg :caret (:cursor s))
                               [a b] (or (:sel msg) (:sel s) [caret caret])]
                           {:text (:text (view/analysis (:text-key s) (:root s)))
                            :caret caret :sel-start a :sel-end b})))))

#?(:clj (defn- console-op
          "One message to the Interactions console; answers what the page needs
   and where the transcript now ends."
          [ed con msg]
          (let [path (or (:path msg) (buffer/active ed))
                answer (case (:op msg)
                         :run (if-let [code (buffer/buffer-text ed path)]
                                (do (interactions/run! con code (display-name path)
                                                       (existing-path path)
                                                       (set (:breakpoints msg)))
                                    {:ok true})
                                {:ok false :error (str "no such buffer: " path)})
                         :run-selection (if (interactions/run-selection! con (snapshot-of ed path msg))
                                          {:ok true}
                                          {:ok false :error "nothing to run on this line"})
                         :run-form (if (interactions/run-form! con (snapshot-of ed path msg))
                                     {:ok true}
                                     {:ok false :error "the caret is on no form"})
                         :input (assoc (interactions/input! con (or (:text msg) "") (:shift msg)) :ok true)
                         :stop {:ok true :stopped (interactions/stop! con)}
                         :clear (do (interactions/clear! con) {:ok true})
                         :history {:ok true :text (interactions/history! con (:dir msg) (:current msg))}
                         :debug-arm (let [r (interactions/arm-breakpoints! con (boolean (:on msg)))]
                                      (assoc r :ok (not (:error r))))
                         :debug-frames {:ok true :frames (interactions/frames! con)}
                         :debug-local (let [r (interactions/local! con (:error-id msg) (:frame msg) (:local msg))]
                                        (assoc r :ok (not (:error r))))
                         :debug-eval (let [r (interactions/debug-eval! con (:error-id msg) (:frame msg) (or (:code msg) ""))]
                                       (assoc r :ok (not (:error r))))
                         :debug-continue {:ok (interactions/continue! con)}
                         :debug-step {:ok (interactions/step! con (or (:mode msg) :step))}
                         :debug-toggle-break (let [r (interactions/toggle-break! con)]
                                               (assoc r :ok (not (:error r))))
                         {:ok false :error (str "unknown console op: " (:op msg))})]
            (merge answer (select-keys (interactions/read-from con (:since msg)) [:status :ns :next])
                   {:debug (interactions/debug-state con)}))))

#?(:clj (defn- handle-api [{:keys [ed theme-atom lock con] :as ctx} req path query]
          (locking lock
            (try
              (case path
                "/api/state" [200 (pr-str (state-payload ed theme-atom ctx)) "text/plain; charset=utf-8"]
                "/api/theme" (let [t (theme/theme (keyword (or (:name query) (name (:name @theme-atom)))))]
                               [200 (pr-str (assoc (view/theme-payload t)
                                                   :console (interactions/console-styles t)))
                                "text/plain; charset=utf-8"])
                "/api/menus" [200 @menus-payload "text/plain; charset=utf-8"]
                "/api/console" (if (= "POST" (:method req))
                                 [200 (pr-str (console-op ed con (edn/read-string (:body req))))
                                  "text/plain; charset=utf-8"]
                                 [200 (pr-str (assoc (interactions/read-from con (some-> (:since query) parse-int))
                                                     :ok true
                                                     :debug (interactions/debug-state con)))
                                  "text/plain; charset=utf-8"])
                "/api/themes" [200 (pr-str {:choice (name (or (buffer/theme-choice ed) (:name @theme-atom)))
                                            :themes (mapv (fn [k]
                                                            {:name (name k)
                                                             :label (theme/label k)
                                                             :dark? (when (not= k :auto)
                                                                      (:dark? (theme/theme k)))})
                                                          theme/choice-names)})
                               "text/plain; charset=utf-8"]
                "/api/list" (let [path (or (:dir query)
                                           (:project ctx)
                                           (some-> (buffer/active ed) file-parent)
                                           ".")]
                              (if-let [payload (list-dir path)]
                                [200 (pr-str payload) "text/plain; charset=utf-8"]
                                [404 (pr-str {:ok false :error (str "not a directory: " path)})
                                 "text/plain; charset=utf-8"]))
                "/api/window" (let [p (or (:path query) (buffer/active ed))
                                    count (parse-int (or (:count query) "100"))
                            ;; a delay: the byte read below needs no
                            ;; line number, and counting one past the
                            ;; scan is the costly part
                                    start (delay
                                            (cond
                                    ;; a byte param anchors the window
                                    ;; so it ENDS at the line holding
                                    ;; that file byte: the page's
                                    ;; load-phase geometry is byte-
                                    ;; denominated, and the byte it
                                    ;; sends is its viewport's bottom —
                                    ;; the lines down to it, the file's
                                    ;; last line included, are the ones
                                    ;; it must be able to show
                                              (and (:byte query) p (buffer/open? ed p))
                                              (max 0 (- (text/line-of-byte p (buffer/scan-of p)
                                                                           (parse-int (:byte query)))
                                                        (dec count)))
                                    ;; a caret param anchors the window at the
                                    ;; line holding that char offset: the page
                                    ;; asks for this when its caret sits outside
                                    ;; the window it holds (a restored session,
                                    ;; a jump across the file), where a line
                                    ;; number is the one thing it cannot know
                                              (and (:caret query) p (buffer/open? ed p))
                                              (buffer/line-of ed p (parse-int (:caret query)))
                                              :else (parse-int (or (:start query) "0"))))]
                                (cond
                          ;; a loading buffer whose document is still
                          ;; its file: a byte window is read from the
                          ;; file by byte, with no line count
                                  (and (:byte query) p (buffer/open? ed p)
                                       (buffer/partial? ed p) (buffer/scan-of p))
                                  [200 (pr-str (byte-window-payload ed p (parse-int (:byte query)) count))
                                   "text/plain; charset=utf-8"]

                                  (and p (buffer/open? ed p))
                                  [200 (pr-str (window-payload ed p @start count)) "text/plain; charset=utf-8"]

                                  :else
                                  [404 "no buffer" "text/plain"]))
                "/api/op" (let [msg (edn/read-string (:body req))
                                res (ops/apply-op ed msg)
                                p (or (:path msg) (buffer/active ed))
                                res (if (and (:ok res) (:view msg) p (buffer/open? ed p))
                                      (assoc res :window (window-payload ed p
                                                                         (get-in msg [:view :start] 0)
                                                                         (get-in msg [:view :count] 100)))
                                      res)]
                            [200 (pr-str res) "text/plain; charset=utf-8"])
                [404 "not found" "text/plain"])
              (catch :default t
                [500 (pr-str {:ok false :error (str (class-name t) ": " (ex-message t))})
                 "text/plain; charset=utf-8"])))))

#?(:clj (defn- handle-client [ctx ^java.net.Socket sock]
          (with-open [in (BufferedReader. (InputStreamReader. (.getInputStream sock)))
                      out (.getOutputStream sock)]
            (when-let [req (read-request in)]
              (let [target (:target req)
                    [path qs] (str/split target #"\?" 2)
                    query (parse-query qs)
                    [status body ctype]
                    (cond
                      (= path "/buffer") [200 (store/current-buffer (:store ctx) "hello") "text/plain; charset=utf-8"]
                      (or (= path "/") (= path "/index.html")) [200 (page-html (:web-dir ctx)) "text/html; charset=utf-8"]
                      (= path "/app.js") (let [f (File. (:web-dir ctx) "app.js")]
                                           (if (.exists f)
                                             [200 (slurp f) "text/javascript; charset=utf-8"]
                                             [404 "app.js not built" "text/plain"]))
                      (str/starts-with? path "/api/") (handle-api ctx req path query)
                      :else [404 "not found" "text/plain"])]
                (respond! out status body ctype)
        ;; the page polls the console and re-reads windows constantly: only
        ;; what changes something, or fails, is worth a line
                (when (or (not= 200 status)
                          (not (or (= path "/api/console") (= path "/api/window") (= path "/api/state"))))
                  (println "NARCIS-S0 served" path "->" status)))))))

#?(:clj
   (defn- port-free?
     "True when nothing is listening on 127.0.0.1:port — bound and
      released at once, the address serve! listens on."
     [port]
     (try (.close (ServerSocket. port 50 (InetAddress/getByName "127.0.0.1")))
          true
          (catch Throwable _ false))))

#?(:clj (defn serve! [ctx port serve-secs]
          (with-open [server (ServerSocket. port 50 (InetAddress/getByName "127.0.0.1"))]
            (.setSoTimeout server 500)
            (let [deadline (when (pos? serve-secs)
                             (+ (System/currentTimeMillis) (* 1000 serve-secs)))]
              (loop [served 0]
                (if (and deadline (>= (System/currentTimeMillis) deadline))
                  served
                  (let [sock (try (.accept server)
                                  (catch java.net.SocketTimeoutException _ nil))]
                    (if (nil? sock)
                      (recur served)
                      (do (.start (Thread. (fn [] (try (handle-client ctx sock)
                                                       (catch Throwable t
                                                         (println "NARCIS-S3 client error:" (ex-message t)))))))
                          (recur (inc served)))))))))))

#?(:clj (defn -main [& args]
          (let [m (loop [as (seq args) m {}]
                    (if as
                      (let [[k v & more] as]
                        (recur more (assoc m (keyword (subs k 2)) v)))
                      m))
                dir (get m :dir "data")
                port (Integer/parseInt (get m :port "8377"))
        ;; first, before the store is opened or the browser is: is the
        ;; port free? A Narcis already running there would otherwise
        ;; be the one the browser opens — its own session and code —
        ;; while this launch dies a second later on the bind (measured:
        ;; a second launch on a busy port seeded its store, opened the
        ;; browser onto the first server, then threw BindException)
                _ (when-not (port-free? port)
                    (println (str "NARCIS: port " port " is already in use — another Narcis is"
                                  " probably still running there. Stop it first (on macOS or Linux,"
                                  " `lsof -nP -iTCP:" port " -sTCP:LISTEN` shows its PID), or launch"
                                  " on another port with --port."))
                    (System/exit 1))
                serve-secs (Integer/parseInt (get m :serve-secs "0"))
                web-dir (get m :web-dir "web")
                st (store/open-store {:dir dir :name store/store-name})
                ed (buffer/open-editor st)
                _ (when-let [t (:theme m)]
                    (when (theme/choice? (keyword t))
                      (buffer/set-theme! ed (keyword t))))
                theme-atom (atom (theme/theme (or (buffer/theme-choice ed)
                                                  (keyword (get m :theme (name theme/default-theme))))))]
            (println "NARCIS-S0 store-impl:" (:impl st))
            (when (:eve-error st)
              (println "NARCIS-S0 eve-error:" (:eve-error st)))
            (when (nil? (store/current-buffer st "hello"))
              (store/put-buffer! st "hello" hello-text)
              (println "NARCIS-S0 seeded hello buffer"))
            (when-let [f (:file m)]
              (buffer/open-file! ed f)
              (println "NARCIS-S3 file-opened:" f "lines:" (:lines (buffer/snapshot ed f))))
    ;; every other buffer left loading by the last run goes on loading
            (doseq [p (buffer/resume-loads! ed)]
              (println "NARCIS-S3 load-resumed:" p))
            (let [text (store/current-buffer st "hello")
                  versions (store/version-count st "hello")]
              (println "NARCIS-S0 buffer-versions:" versions)
              (println "NARCIS-S0 buffer-text-ok:" (= text hello-text))
              (attempt-auto-open! (str "http://127.0.0.1:" port "/"))
              (let [con (interactions/open st)
                    project (.getCanonicalPath
                             (File. ^String (or (:project m)
                                                (some-> (:file m) (File.) (.getAbsoluteFile) (.getParent))
                                                ".")))
                    ctx {:store st :ed ed :theme-atom theme-atom :con con :project project
                         :lock (Object.) :web-dir web-dir}
                    n (serve! ctx port serve-secs)]
                (println "NARCIS-S0 requests-served:" n)))
            (store/close-store st)
            (println "NARCIS-S0 done"))))
