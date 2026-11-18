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
   and read in a read region (eve.alpha/$), where they are read
   in place. A plain deref of the atom refuses — a value held by
   reference cannot be packed for delivery — so no path derefs
   it: writes go through swap-in!, reads through read-in."
  (:require [eve.alpha :as eve]
            [eve.deftype.array :as arr]))

(defprotocol IVersioned
  (-cur [v] "The current version's value.")
  (-hist [v] "Eve array of prior values, oldest first, or nil."))

(eve/deftype Versioned [cur hist]
  IVersioned
  (-cur [_] cur)
  (-hist [_] hist))

(defn- arr-len [a] (if (nil? a) 0 (arr/alength a)))

(defn- arr-push [a v]
  (let [n (arr-len a)
        b (arr/make-array (inc n))]
    (dotimes [i n] (arr/aset b i (arr/aget a i)))
    (arr/aset b n v)
    b))

(defn- arr-vec [a]
  (if (nil? a) [] (vec (arr/array-seq a))))

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
      (when (eve/$ (nil? @a))
        (eve/swap! a (fn [_] empty-state)))
      {:impl :eve :base base :atom a})))

(defn swap-in!
  "One swap! on the app atom: the value at ks becomes
   (f <current value at ks>). f runs inside the swap, in the
   atom's domain. Answers nil."
  [store ks f]
  (eve/swap! (:atom store)
            (fn [app] (assoc-in app ks (f (get-in app ks)))))
  nil)

(defn read-in
  "(f value-at-ks) inside a read region: the value in place, f's
   answer as plain data (see narcis.store/read-in)."
  [store ks f]
  (eve/$ (f (get-in @(:atom store) ks))))

;; -- versions: the launcher's plain text buffers, one slot each --

(defn put! [store buffer-name value]
  (swap-in! store [:versions buffer-name]
            (fn [v]
              (if (nil? v)
                (->Versioned value nil)
                (->Versioned value
                             (arr-push (-hist v) (-cur v))))))
  (read-in store [:versions buffer-name]
           (fn [v] (arr-len (-hist v)))))

(defn current [store buffer-name]
  (read-in store [:versions buffer-name]
           (fn [v] (when (some? v) (-cur v)))))

(defn version [store buffer-name i]
  (read-in store [:versions buffer-name]
           (fn [v] (when (some? v)
                     (nth (conj (arr-vec (-hist v)) (-cur v)) i nil)))))

(defn version-count [store buffer-name]
  (read-in store [:versions buffer-name]
           (fn [v] (if (nil? v) 0 (inc (arr-len (-hist v)))))))

(defn close! [{:keys [base]}]
  (eve/close-atom-domain! (eve/persistent-atom-domain base))
  nil)
