(ns narcis.store
  "Buffer store seam: open-by-name persistence for editor/buffer state.

   The store is opened by name in a directory. On the Eve arm the
   store is a disk DOMAIN holding ONE Eve atom — the app state
   atom, the way a cljs app has one — whose value is a plain map:

     {:buffers  {<path> BufferState}   ; narcis.buffer's documents
      :editor   {...}                  ; narcis.buffer's editor state
      :console  {...}                  ; the Interactions transcript
      :versions {<name> Versioned}}    ; the launcher's text buffers

   Everything shares the one atom because everything shares the
   one contention domain anyway. A change is one swap! on the
   atom itself; a read is read in place, inside a read region.
   Nothing is serialized in or out. The store map carries the
   atom as (:atom store) on the Eve arm.

   Implementations, tried in order:
   1. Eve disk atom (narcis.store-eve) — when eve.atom loads on this
      host. This is the default on jolt and the JVM.
   2. File fallback (this namespace) — the same map shape in an EDN
      file, written atomically (tmp + rename) on every swap.
      This arm remains ONLY for hosts where Eve genuinely cannot run
      yet — today bb, whose SCI blocks the ByteBuffer put Eve's vector
      path needs — and this seam is the one place
      that says so. It holds EDN-representable values (the launcher's
      text buffers); eve.deftype values require the Eve arm —
      narcis.buffer says so when opened on a file store.
      App code cannot tell the difference except via (:impl store)."
  (:require #?(:clj [clojure.edn :as edn])
            [clojure.string :as str]))

#?(:clj (defn- sanitize [s] (str/replace s #"[^a-zA-Z0-9._-]" "_")))

(def store-name
  "The store the editor opens. The name carries the layout of what is in
   it: text3 is the one-atom layout (text2's buffers are eve.text
   documents too, but each in its own named atom; the first layout's
   were narcis.rope trees, a type this code no longer has). A store of
   an older layout is left as it is, under its own name, and not read."
  "narcis/text3")

(defn- class-name [v] #?(:clj (.getName (class v)) :cljs (.-name (type v))))

#?(:clj (defn- file-path [{:keys [dir name]}]
          (str dir "/" (sanitize name) ".edn")))

#?(:clj
   (do (defn- file-open [{:keys [dir] :as opts}]
         (let [path (file-path opts)
               f (java.io.File. path)]
           (.mkdirs (java.io.File. dir))
           {:impl :file
            :path path
            :state (atom (if (.exists f)
                           (edn/read-string (slurp f))
                           {:buffers {} :editor nil :console nil :versions {}}))}))
       (defn- file-persist! [{:keys [path state]}]
         (let [tmp (str path ".tmp")]
           (spit tmp (pr-str @state))
           (.renameTo (java.io.File. tmp) (java.io.File. path))))))

(defn- try-eve-open [opts]
  (try
    (require 'narcis.store-eve)
    (let [open-fn (resolve 'narcis.store-eve/open)]
      (when open-fn (open-fn opts)))
    (catch #?(:cljs :default :default Throwable) t
      {:eve-error (str (class-name t) ": " (ex-message t))})))

(defn open-store
  "Open (or create) the store named (:name opts) in (:dir opts).
   Returns a store map; (:impl store) is :eve or :file. When Eve was
   attempted and failed, (:eve-error store) carries the failure."
  [opts]
  (let [r (try-eve-open opts)]
    (if (and (map? r) (= :eve (:impl r)))
      r
      (assoc (file-open opts) :eve-error (:eve-error r)))))

(defn put-buffer!
  "Store value as the current version of buffer-name, in the app
   atom's :versions slot; returns the new version index. Every
   put appends a durable version."
  [store buffer-name value]
  (if (= :eve (:impl store))
    ((resolve 'narcis.store-eve/put!) store buffer-name value)
    (let [idx (atom nil)]
      (swap! (:state store)
             (fn [s]
               (let [versions (get-in s [:versions buffer-name :versions] [])]
                 (reset! idx (count versions))
                 (assoc-in s [:versions buffer-name]
                           {:current value
                            :versions (conj versions value)}))))
      (file-persist! store)
      @idx)))

(defn current-buffer [store buffer-name]
  (if (= :eve (:impl store))
    ((resolve 'narcis.store-eve/current) store buffer-name)
    (get-in @(:state store) [:versions buffer-name :current])))

(defn buffer-version [store buffer-name i]
  (if (= :eve (:impl store))
    ((resolve 'narcis.store-eve/version) store buffer-name i)
    (get-in @(:state store) [:versions buffer-name :versions i])))

(defn version-count [store buffer-name]
  (if (= :eve (:impl store))
    ((resolve 'narcis.store-eve/version-count) store buffer-name)
    (count (get-in @(:state store) [:versions buffer-name :versions]))))

(defn close-store [store]
  (when (= :eve (:impl store))
    ((resolve 'narcis.store-eve/close!) store))
  nil)
