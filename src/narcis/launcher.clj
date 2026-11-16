(ns narcis.launcher
  "S0 launcher, media-cohost shape: open the buffer store by name, serve
   the hello cljs page over HTTP, attempt to auto-open the viewer.

   Usage: run -m narcis.launcher [--dir data] [--port 8377] [--serve-secs 10]
   Prints NARCOS-S0 marker lines the gate script checks:
     store-impl, buffer version count, reopened text check, served URL."
  (:require [narcis.store :as store]
            [clojure.string :as str])
  (:import [java.net ServerSocket InetAddress]
           [java.io BufferedReader InputStreamReader OutputStream]))

(def hello-text
  "hello narcis\nthe buffer you are reading lives in a store opened by name.\nedit me in S2.\n")

(def page
  "<!doctype html><html><head><meta charset='utf-8'><title>Narcis</title></head>
<body><h1>Narcis</h1><pre id='buffer'>loading…</pre>
<script>
fetch('/buffer').then(r => r.text()).then(t => {
  document.getElementById('buffer').textContent = t;
});
</script></body></html>")

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

(defn- handle-client [^java.net.Socket sock buffer-text]
  (with-open [in (BufferedReader. (InputStreamReader. (.getInputStream sock)))
              out (.getOutputStream sock)]
    (let [request-line (.readLine in)]
      ;; drain headers
      (loop [line (.readLine in)]
        (when (and line (not (str/blank? line)))
          (recur (.readLine in))))
      (let [[_ path] (when request-line (str/split request-line #" +"))
            [status body content-type]
            (cond (= path "/buffer") [200 buffer-text "text/plain; charset=utf-8"]
                  (or (= path "/") (= path "/index.html")) [200 page "text/html; charset=utf-8"]
                  :else [404 "not found" "text/plain"])
            bytes (.getBytes ^String body "UTF-8")
            head (str "HTTP/1.1 " status " OK\r\n"
                      "Content-Type: " content-type "\r\n"
                      "Content-Length: " (count bytes) "\r\n"
                      "Connection: close\r\n\r\n")]
        (.write out (.getBytes head "UTF-8"))
        (.write out bytes)
        (.flush out)
        (println "NARCIS-S0 served" path "->" status)))))

(defn serve! [port buffer-text serve-secs]
  (with-open [server (ServerSocket. port 50 (InetAddress/getByName "127.0.0.1"))]
    (.setSoTimeout server 500)
    (let [deadline (+ (System/currentTimeMillis) (* 1000 serve-secs))]
      (loop [served 0]
        (if (>= (System/currentTimeMillis) deadline)
          served
          (let [sock (try (.accept server)
                          (catch java.net.SocketTimeoutException _ nil))]
            (if (nil? sock)
              (recur served)
              (do (with-open [s sock] (handle-client s buffer-text))
                  (recur (inc served))))))))))

(defn -main [& args]
  (let [;; arg parse: --key value pairs
        m (loop [as (seq args) m {}]
            (if as
              (let [[k v & more] as]
                (recur more (assoc m (keyword (subs k 2)) v)))
              m))
        dir (get m :dir "data")
        port (Integer/parseInt (get m :port "8377"))
        serve-secs (Integer/parseInt (get m :serve-secs "10"))
        st (store/open-store {:dir dir :name "narcis/buffers"})]
    (println "NARCIS-S0 store-impl:" (:impl st))
    (when (:eve-error st)
      (println "NARCIS-S0 eve-error:" (:eve-error st)))
    (when (nil? (store/current-buffer st "hello"))
      (store/put-buffer! st "hello" hello-text)
      (println "NARCIS-S0 seeded hello buffer"))
    (let [text (store/current-buffer st "hello")
          versions (store/version-count st "hello")]
      (println "NARCIS-S0 buffer-versions:" versions)
      (println "NARCIS-S0 buffer-text-ok:" (= text hello-text))
      (attempt-auto-open! (str "http://127.0.0.1:" port "/"))
      (let [n (serve! port text serve-secs)]
        (println "NARCIS-S0 requests-served:" n)))
    (store/close-store st)
    (println "NARCIS-S0 done")))
