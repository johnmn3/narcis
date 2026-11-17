(ns narcis.store
  "Buffer store seam: open-by-name persistence for editor/buffer state.

   The store is opened by name (\"narcis/buffers\") in a directory.
   On the Eve arm the store is a disk DOMAIN holding named Eve atoms:
   one versions atom per buffer name (value: a Versioned eve.deftype —
   current value + history — at the atom's root), plus generic named
   atoms for narcis.buffer's editor state. Buffer values are swapped
   on those atoms; nothing is serialized in or out.

   Implementations, tried in order:
   1. Eve disk atoms (narcis.store-eve) — when eve.atom loads on this
      host. This is the default on jolt (Eve @84bfc7c8) and the JVM.
   2. File fallback (this namespace) — buffer values in an EDN file,
      written atomically (tmp + rename) on every put, in the shape
        {:buffers {<buffer-name> {:current <value> :versions [...]}}}
      This arm remains ONLY for hosts where Eve genuinely cannot run
      yet — today bb, whose SCI blocks the ByteBuffer put Eve's vector
      path needs (W2, see REPORT.md) — and this seam is the one place
      that says so. It holds EDN-representable values (the launcher's
      text buffers); eve.deftype rope values require the Eve arm —
      narcis.buffer says so when opened on a file store.
      App code cannot tell the difference except via (:impl store)."
  (:require [clojure.string :as str]
            [clojure.edn :as edn]))

(defn- sanitize [s] (str/replace s #"[^a-zA-Z0-9._-]" "_"))

#?(:cljs nil
   :default
   (do
     (defn- file-path [{:keys [dir name]}]
       (str dir "/" (sanitize name) ".edn"))

     (defn- file-open [{:keys [dir] :as opts}]
       (let [path (file-path opts)
             f (java.io.File. path)]
         (.mkdirs (java.io.File. dir))
         {:impl :file
          :path path
          :state (atom (if (.exists f)
                         (edn/read-string (slurp f))
                         {:buffers {}}))}))

     (defn- file-persist! [{:keys [path state]}]
       (let [tmp (str path ".tmp")]
         (spit tmp (pr-str @state))
         (.renameTo (java.io.File. tmp) (java.io.File. path))))

     (defn- try-eve-open [opts]
       (try
         (require 'narcis.store-eve)
         (let [open-fn (resolve 'narcis.store-eve/open)]
           (when open-fn (open-fn opts)))
         (catch Throwable t
           {:eve-error (str (.getName (class t)) ": " (ex-message t))})))

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
       "Store value as the current version of buffer-name; returns the
        new version index. Every put appends a durable version."
       [store buffer-name value]
       (if (= :eve (:impl store))
         ((resolve 'narcis.store-eve/put!) store buffer-name value)
         (let [idx (atom nil)]
           (swap! (:state store)
                  (fn [s]
                    (let [versions (get-in s [:buffers buffer-name :versions] [])]
                      (reset! idx (count versions))
                      (assoc-in s [:buffers buffer-name]
                                {:current value
                                 :versions (conj versions value)}))))
           (file-persist! store)
           @idx)))

     (defn current-buffer [store buffer-name]
       (if (= :eve (:impl store))
         ((resolve 'narcis.store-eve/current) store buffer-name)
         (get-in @(:state store) [:buffers buffer-name :current])))

     (defn buffer-version [store buffer-name i]
       (if (= :eve (:impl store))
         ((resolve 'narcis.store-eve/version) store buffer-name i)
         (get-in @(:state store) [:buffers buffer-name :versions i])))

     (defn version-count [store buffer-name]
       (if (= :eve (:impl store))
         ((resolve 'narcis.store-eve/version-count) store buffer-name)
         (count (get-in @(:state store) [:buffers buffer-name :versions]))))

     (defn open-atom
       "Open the store's atom named key, with initial value init when
        the atom has never been set. Returns a handle for atom-deref /
        atom-swap!. On the Eve arm this is a named Eve atom in the
        store's disk domain — swaps on it are Eve swaps, and a swap
        fn that constructs eve.deftype values constructs them in the
        domain. On the file arm it is an EDN-backed atom (values must
        be EDN-representable)."
       [store key init]
       (if (= :eve (:impl store))
         ((resolve 'narcis.store-eve/open-atom) store key init)
         (do (swap! (:state store)
                    (fn [s] (if (contains? (:atoms s) key)
                              s
                              (assoc-in s [:atoms key] init))))
             (file-persist! store)
             {:kind :file :store store :key key})))

     (defn atom-deref [handle]
       (if (= :eve (:kind handle))
         ((resolve 'narcis.store-eve/atom-deref) handle)
         (get-in @(:state (:store handle)) [:atoms (:key handle)])))

     (defn atom-swap!
       "Swap the atom behind handle with f (a fn of its current value).
        Returns the new value."
       [handle f]
       (if (= :eve (:kind handle))
         ((resolve 'narcis.store-eve/atom-swap!) handle f)
         (let [store (:store handle)
               new (atom nil)]
           (swap! (:state store)
                  (fn [s] (let [v (f (get-in s [:atoms (:key handle)]))]
                            (reset! new v)
                            (assoc-in s [:atoms (:key handle)] v))))
           (file-persist! store)
           @new)))

     (defn close-store [store]
       (when (= :eve (:impl store))
         ((resolve 'narcis.store-eve/close!) store))
       nil)))
