(ns narcis.launcher
  "Editor server, media-cohost shape: open the buffer store by name,
   open the editor state in the Eve disk atom, serve the editor page +
   prebuilt cljs bundle, and answer the window/op API the page drives.
   The page renders rope windows fetched from here and sends edits
   back as narcis.ops messages — it never holds the text.

   Usage: run -m narcis.launcher [--dir data] [--port 8377]
          [--serve-secs N] [--file path] [--theme name] [--web-dir web]
   --serve-secs omitted (or 0) serves until killed; the S0 gate passes
   it explicitly. Prints the NARCIS-S0 marker lines the S0 gate checks
   (store-impl, buffer-text-ok, served, ...) and NARCIS-S3 lines for
   the editor gates."
  (:require [narcis.buffer :as buffer]
            [narcis.ops :as ops]
            [narcis.rope :as rope]
            [narcis.store :as store]
            [narcis.theme :as theme]
            [narcis.view :as view]
            [clojure.edn :as edn]
            [clojure.string :as str])
  (:import [java.net ServerSocket InetAddress URLDecoder]
           [java.io BufferedReader InputStreamReader OutputStream File]))

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
        false))))

;; ---------------------------------------------------------------------------
;; HTTP plumbing (S0's, with request bodies + a thread per connection)

(defn- parse-query [qs]
  (if (str/blank? qs)
    {}
    (into {}
          (map (fn [pair]
                 (let [[k v] (str/split pair #"=" 2)]
                   [(keyword (URLDecoder/decode k "UTF-8"))
                    (URLDecoder/decode (or v "") "UTF-8")])))
          (str/split qs #"&"))))

(defn- read-request [^BufferedReader in]
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
         :body body}))))

(defn- respond! [^OutputStream out status body content-type]
  (let [bytes (.getBytes ^String body "UTF-8")
        head (str "HTTP/1.1 " status " OK\r\n"
                  "Content-Type: " content-type "\r\n"
                  "Content-Length: " (count bytes) "\r\n"
                  "Connection: close\r\n\r\n")]
    (.write out (.getBytes head "UTF-8"))
    (.write out bytes)
    (.flush out)))

;; ---------------------------------------------------------------------------
;; API

(defn- state-payload [ed theme-name]
  {:tabs (buffer/tabs ed)
   :active (buffer/active ed)
   :theme theme-name
   :buffers (into {}
                  (map (fn [p]
                         (let [root (buffer/buffer-root ed p)]
                           [p {:lines (rope/lines root)
                               :chars (rope/chars root)
                               :cursor (buffer/cursor ed p)
                               :sel (buffer/selection ed p)
                               :undo (buffer/undo-depth ed p)
                               :redo (buffer/redo-depth ed p)}])))
                  (buffer/tabs ed))})

(defn- window-payload [ed path start count]
  (let [root (buffer/buffer-root ed path)]
    (merge (view/window root {:start start :count count
                              :caret (buffer/cursor ed path)})
           {:path path
            :caret (buffer/cursor ed path)
            :sel (buffer/selection ed path)
            :undo (buffer/undo-depth ed path)
            :redo (buffer/redo-depth ed path)
            :rev (buffer/rev ed)})))

(defn- handle-api [ed theme-atom lock req path query]
  (locking lock
    (try
      (case path
        "/api/state" [200 (pr-str (state-payload ed (name (:name @theme-atom)))) "text/plain; charset=utf-8"]
        "/api/theme" (let [t (theme/theme (keyword (or (:name query) (name (:name @theme-atom)))))]
                       [200 (pr-str (view/theme-payload t)) "text/plain; charset=utf-8"])
        "/api/window" (let [p (or (:path query) (buffer/active ed))
                            start (Integer/parseInt (or (:start query) "0"))
                            count (Integer/parseInt (or (:count query) "100"))]
                        (if (and p (buffer/buffer ed p))
                          [200 (pr-str (window-payload ed p start count)) "text/plain; charset=utf-8"]
                          [404 "no buffer" "text/plain"]))
        "/api/op" (let [msg (edn/read-string (:body req))
                        res (ops/apply-op ed msg)
                        p (or (:path msg) (buffer/active ed))
                        res (if (and (:ok res) (:view msg) p (buffer/buffer ed p))
                              (assoc res :window (window-payload ed p
                                                                 (get-in msg [:view :start] 0)
                                                                 (get-in msg [:view :count] 100)))
                              res)]
                    [200 (pr-str res) "text/plain; charset=utf-8"])
        [404 "not found" "text/plain"])
      (catch Throwable t
        [500 (pr-str {:ok false :error (str (.getName (class t)) ": " (ex-message t))})
         "text/plain; charset=utf-8"]))))

(defn- handle-client [ctx ^java.net.Socket sock]
  (with-open [in (BufferedReader. (InputStreamReader. (.getInputStream sock)))
              out (.getOutputStream sock)]
    (when-let [req (read-request in)]
      (let [target (:target req)
            [path qs] (str/split target #"\?" 2)
            query (parse-query qs)
            [status body ctype]
            (cond
              (= path "/buffer") [200 (store/current-buffer (:store ctx) "hello") "text/plain; charset=utf-8"]
              (or (= path "/") (= path "/index.html")) [200 page "text/html; charset=utf-8"]
              (= path "/app.js") (let [f (File. (:web-dir ctx) "app.js")]
                                   (if (.exists f)
                                     [200 (slurp f) "text/javascript; charset=utf-8"]
                                     [404 "app.js not built" "text/plain"]))
              (str/starts-with? path "/api/") (handle-api (:ed ctx) (:theme-atom ctx) (:lock ctx) req path query)
              :else [404 "not found" "text/plain"])]
        (respond! out status body ctype)
        (println "NARCIS-S0 served" path "->" status)))))

(defn serve! [ctx port serve-secs]
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
                  (recur (inc served))))))))))

(defn -main [& args]
  (let [m (loop [as (seq args) m {}]
            (if as
              (let [[k v & more] as]
                (recur more (assoc m (keyword (subs k 2)) v)))
              m))
        dir (get m :dir "data")
        port (Integer/parseInt (get m :port "8377"))
        serve-secs (Integer/parseInt (get m :serve-secs "0"))
        web-dir (get m :web-dir "web")
        st (store/open-store {:dir dir :name "narcis/buffers"})
        ed (buffer/open-editor st)
        theme-atom (atom (theme/theme (keyword (get m :theme (name theme/default-theme)))))]
    (println "NARCIS-S0 store-impl:" (:impl st))
    (when (:eve-error st)
      (println "NARCIS-S0 eve-error:" (:eve-error st)))
    (when (nil? (store/current-buffer st "hello"))
      (store/put-buffer! st "hello" hello-text)
      (println "NARCIS-S0 seeded hello buffer"))
    (when-let [f (:file m)]
      (buffer/open-file! ed f)
      (println "NARCIS-S3 file-opened:" f "lines:" (rope/lines (buffer/buffer-root ed f))))
    (let [text (store/current-buffer st "hello")
          versions (store/version-count st "hello")]
      (println "NARCIS-S0 buffer-versions:" versions)
      (println "NARCIS-S0 buffer-text-ok:" (= text hello-text))
      (attempt-auto-open! (str "http://127.0.0.1:" port "/"))
      (let [ctx {:store st :ed ed :theme-atom theme-atom
                 :lock (Object.) :web-dir web-dir}
            n (serve! ctx port serve-secs)]
        (println "NARCIS-S0 requests-served:" n)))
    (store/close-store st)
    (println "NARCIS-S0 done")))
