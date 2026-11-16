(ns narcis.store
  "Buffer store seam: open-by-name persistence for editor/buffer state.

   The store holds buffer VALUES (rope roots are plain data) under a name
   like \"narcis/buffers\", keeping every version:
     {:buffers {<buffer-name> {:current <value> :versions [<value> ...]}}}

   Implementations, tried in order:
   1. Eve disk atom (narcis.store-eve) — an eve.alpha disk atom opened by
      :id in the domain at :dir, when eve.atom loads on this host.
   2. File fallback (this namespace) — the same value shape in an EDN file,
      written atomically (tmp + rename) on every put. This is the seam arm
      for hosts where Eve does not load yet (jolt v0.8.23, see REPORT.md);
      app code cannot tell the difference except via (:impl store)."
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

     (defn close-store [store]
       (when (= :eve (:impl store))
         ((resolve 'narcis.store-eve/close!) store))
       nil)))
