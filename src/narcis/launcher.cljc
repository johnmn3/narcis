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
  (:require [narcis.buffer :as buffer]
            [narcis.interactions :as interactions]
            [narcis.menus :as menus]
            [narcis.ops :as ops]
            [narcis.store :as store]
            [narcis.text :as text]
            [narcis.theme :as theme]
            [narcis.view :as view]
            [clojure.edn :as edn]
            #?(:clj [clojure.java.io :as io])
            [clojure.string :as str])
  #?(:clj (:import [java.net ServerSocket InetAddress URLDecoder]
                   [java.io BufferedReader InputStreamReader OutputStream File])))

;; The server's host seams: files, sockets, processes and the clock
;; are the clj hosts' shapes today; the request handling and the API
;; above them are every host's.
(defn- now-ms [] #?(:clj (System/currentTimeMillis) :cljs (js/Date.now)))
(defn- parse-int [s] #?(:clj (Integer/parseInt s) :cljs (js/parseInt s 10)))
(defn- class-name [v] #?(:clj (.getName (class v)) :cljs (.-name (type v))))
(defn- url-decode [s]
  #?(:clj (URLDecoder/decode s "UTF-8")
     :cljs (js/decodeURIComponent (str/replace s "+" " "))))
#?(:clj
   (do (defn- existing-path [path] (when (.exists (File. ^String path)) path))
       (defn- canonical-path [path] (.getCanonicalPath (File. ^String path)))
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

(defn- parse-query [qs]
  (if (str/blank? qs)
    {}
    (into {}
          (map (fn [pair]
                 (let [[k v] (str/split pair #"=" 2)]
                   [(keyword (url-decode k))
                    (url-decode (or v ""))])))
          (str/split qs #"&"))))

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

(defn- state-payload [ed theme-atom ctx]
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
                             (let [ranges (buffer/extent-ranges ed p)
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
                                                              ranges)})
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
                                         :extent-bytes (:extent-bytes geo))])))
                      (buffer/loading-map ed))]
    {:tabs (buffer/tabs ed)
     :active (buffer/active ed)
     :loading loading
     :scanning (buffer/scanning-map)
     :project (:project ctx)
     :runtime "Jolt"
     :theme (name (or (buffer/theme-choice ed) (:name @theme-atom)))
     :buffers buffers}))

(defn- file-lines-payload
  "Lines [start, start+count) of path's file, read by its scan's
   index, in view/window's shape (no spans — the stretch is not a
   document yet). The stretch is noted as a just-in-time extent
   when it is a full stretch that stops short of the file's end,
   so the hydration absorbs it instead of reading it again."
  [ed path start count total-lines total-chars]
  (let [w (text/file-window path (buffer/scan-of path) start count)
        lines (:lines w)]
    (when (and (= (clojure.core/count lines) count) (not (:eof? w)))
      (buffer/note-extent! ed path lines (:stream-start w) (:stream-end w)))
    {:start-line start
     :total-lines (or total-lines (:total-lines w) start)
     :total-chars (or total-chars (:total-chars w) 0)
     :spans-mode :window
     :match nil
     :lines (mapv (fn [l] (assoc l :spans [])) lines)}))

(defn- beyond-payload
  "A window wholly past path's document: served from a just-in-time
   extent when one covers it (it is rope already, analysis and
   all), else read out of the file."
  [ed path start count total-lines total-chars]
  ;; the extent's document is read in place, through read-extent's
  ;; region: a document taken out of the app atom (buffer/extents)
  ;; is an unpinned handle the assembly may let go mid-render
  (or (buffer/read-extent
       ed path start count
       (fn [s e]
         (let [w (view/window (:doc e) {:start (- start s) :count count
                                        :key [:extent path s (:chars e)]})]
           {:start-line start
            :total-lines total-lines
            :total-chars total-chars
            :spans-mode (:spans-mode w)
            :match nil
            :lines (mapv (fn [l] (-> l
                                    (update :n + s)
                                    (update :start + (:start-char e))))
                         (:lines w))})))
      (file-lines-payload ed path start count total-lines total-chars)))

(defn- window-payload [ed path start count]
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
             (cond
               ;; wholly within the document
               (<= (+ start count) doc-lines)
               (assoc base :total-lines total-lines
                      :total-chars (or total-chars (:total-chars base)))

               ;; wholly past it: an extent or the file serves
               (>= start doc-lines)
               (merge (beyond-payload ed path start count total-lines total-chars)
                      (select-keys base [:path :caret :sel :undo :redo]))

               ;; straddling the frontier: the document's lines (the
               ;; doc window already clamps to them), then the file's
               :else
               (let [rest (beyond-payload ed path doc-lines
                                          (+ start count (- doc-lines))
                                          total-lines total-chars)]
                 (assoc base :lines (into (:lines base) (:lines rest))
                        :total-lines total-lines
                        :total-chars (or total-chars (:total-chars base))))))
           :rev (buffer/rev ed path)
           ;; the load-phase scroll geometry is denominated in
           ;; the file's bytes — the one quantity exact from t=0:
           ;; the page places this window's block at :start-byte
           ;; in a scroll space :total-bytes tall
           :start-byte (when partial
                         (text/byte-offset-of-line (buffer/scan-of path) start))
           :total-bytes (when partial
                          (get-in (buffer/loading-map ed) [path :total])))))

#?(:clj
   (def ^:private menus-payload
     ;; jonquil's menus (narcis/menus.edn on the classpath), narrowed to
     ;; what Narcis runs
     (delay (pr-str (menus/menus (slurp (io/resource "narcis/menus.edn")))))))

(defn- display-name [path]
  (if (or (nil? path) (str/starts-with? path "untitled"))
    "Definitions"
    (last (str/split path #"/"))))

(defn- snapshot-of
  "The edit-op snapshot ({:text :caret :sel-start :sel-end}) of path at
   the message's caret, for the commands that read the text."
  [ed path msg]
  (buffer/read ed path
               (fn [s]
                 (let [caret (get msg :caret (:cursor s))
                       [a b] (or (:sel msg) (:sel s) [caret caret])]
                   {:text (:text (view/analysis (:text-key s) (:root s)))
                    :caret caret :sel-start a :sel-end b}))))

(defn- console-op
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
                 :debug-continue {:ok (boolean (interactions/continue! con))}
                 :debug-step {:ok (boolean (interactions/step! con (or (:mode msg) :step)))}
                 :debug-toggle-break (let [r (interactions/toggle-break! con)]
                                       (assoc r :ok (not (:error r))))
                 {:ok false :error (str "unknown console op: " (:op msg))})]
    (merge answer (select-keys (interactions/read-from con (:since msg)) [:status :ns :next])
           {:debug (interactions/debug-state con)})))

(defn- handle-api [{:keys [ed theme-atom lock con] :as ctx} req path query]
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
                            start (cond
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
                                    :else (parse-int (or (:start query) "0")))]
                        (if (and p (buffer/open? ed p))
                          [200 (pr-str (window-payload ed p start count)) "text/plain; charset=utf-8"]
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
         "text/plain; charset=utf-8"]))))

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
