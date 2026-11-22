(ns narcis.store-eve
    "Eve disk-atom binding for narcis.store. Loaded dynamically by
   narcis.store only on hosts where eve.atom loads.

   The store is a disk DOMAIN at a base path (release Eve,
   examples/disk_domain.cljc) holding ONE Eve atom — the app
   state atom, the way a cljs app has one. Its value is a plain
   map, the whole app state:

     {:buffers  {<path> BufferState}   ; narcis.buffer's documents
      :editor   {:tabs [...] :active <path> :rev n :theme <choice>}
      :console  {:entries [...] :dropped n :ns <string>}
      :versions {<name> Versioned}}     ; the launcher's text buffers

   One atom because everything in it shares the one contention
   domain anyway; a collection of atoms bought nothing. Every
   change is one swap! on the atom, updating one slot of the map.

   BufferState and Versioned are eve.deftype values nested in the
   map. That is sound because they are only ever built and read
   inside the atom's own machinery: constructed in a swap fn,
   where the domain is bound and a deftype value is Eve pointers,
   and read in a read region ($), where they are read
   in place. A plain deref of the atom refuses — a value held by
   reference cannot be packed for delivery — so no path derefs
   it: writes are swap!s on the atom itself, reads are read in
   place inside a read region ($)."
    (:require [eve.alpha :as eve :refer [$]]))

(defprotocol IVersioned
             (-cur [v] "The current version's value.")
             (-hist [v] "Eve array of prior values, oldest first, or nil."))

(eve/deftype Versioned [cur hist]
             IVersioned
             (-cur [_] cur)
             (-hist [_] hist))

(defn- arr-len [a] (if (nil? a) 0 (eve/alength a)))

(defn- arr-push [a v]
       (let [n (arr-len a)
             b (eve/make-array (inc n))]
            (dotimes [i n] (eve/aset b i (eve/aget a i)))
            (eve/aset b n v)
            b))

(defn- arr-vec [a]
       (if (nil? a) [] (vec (eve/array-seq a))))

(def empty-state
     "The app atom's first value: every slot empty."
     {:buffers {} :editor nil :console nil :versions {}})

(defn open [{:keys [dir name]}]
      (let [base (str dir "/" name)]
    ;; A regular Eve disk domain at base, with Eve's defaults —
    ;; journal included. Opening it now is the store's lifecycle:
    ;; the domain is created at open, closed at close!.
           (eve/persistent-atom-domain base)
           (let [a (eve/atom {:id :narcis.store/app :persistent base} nil)]
                (when ($ (nil? @a))
                      (swap! a (fn [_] empty-state)))
                {:impl :eve :base base :atom a})))

;; -- versions: the launcher's plain text buffers, one slot each --

(defn put! [store buffer-name value]
      (swap! (:atom store) update-in [:versions buffer-name]
             (fn [v]
                 (if (nil? v)
                     (->Versioned value nil)
                     (->Versioned value
                                  (arr-push (-hist v) (-cur v))))))
      ($ (arr-len (-hist (get-in @(:atom store) [:versions buffer-name])))))

(defn current [store buffer-name]
      ($ (let [v (get-in @(:atom store) [:versions buffer-name])]
              (when (some? v) (-cur v)))))

(defn version [store buffer-name i]
      ($ (let [v (get-in @(:atom store) [:versions buffer-name])]
              (when (some? v)
                    (nth (conj (arr-vec (-hist v)) (-cur v)) i nil)))))

(defn version-count [store buffer-name]
      ($ (let [v (get-in @(:atom store) [:versions buffer-name])]
              (if (nil? v) 0 (inc (arr-len (-hist v)))))))

(defn close! [{:keys [base]}]
      (eve/close-atom-domain! (eve/persistent-atom-domain base))
      nil)
