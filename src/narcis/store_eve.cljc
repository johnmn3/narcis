(ns narcis.store-eve
  "Eve disk-atom binding for narcis.store. Loaded dynamically by
   narcis.store only on hosts where eve.atom loads.

   The store is a disk DOMAIN at a base path (release Eve,
   examples/disk_domain.cljc). It holds NAMED Eve atoms in that
   domain; every process that opens the same base joins the same
   domain and sees the same atoms.

   Buffer versions live one atom per buffer name, and the atom's
   value is a Versioned eve.deftype at the root:

     Versioned{:cur <current value> :hist <eve array of prior values>}

   A deftype value must sit at an atom's root: nested inside a
   collection value it would be frozen by its plain field view, and
   a field held by reference (a rope's children array) cannot be
   packed (measured at Eve @84bfc7c8). At the root the value is
   owned, never packed.

   Values enter by swap!. The versions atoms hold plain values (the
   launcher's hello text); a buffer's document is built inside its own
   atom's swap by narcis.buffer."
  (:require [eve.alpha :as e] [eve.atom :as ea]
            [eve.host :as eh] [eve.deftype :as edt]
            [eve.deftype.array :as arr]
            [clojure.string :as str]))

(defprotocol IVersioned
  (-cur [v] "The current version's value.")
  (-hist [v] "Eve array of prior values, oldest first, or nil."))

(edt/deftype Versioned [cur hist]
  IVersioned
  (-cur [_] cur)
  (-hist [_] hist))

(defn- sanitize [s] (str/replace s #"[^a-zA-Z0-9._-]" "_"))

(defn- arr-len [a] (if (nil? a) 0 (arr/alength a)))

(defn- arr-push [a v]
  (let [n (arr-len a)
        b (arr/make-array (inc n))]
    (dotimes [i n] (arr/aset b i (arr/aget a i)))
    (arr/aset b n v)
    b))

(defn- arr-vec [a]
  (if (nil? a) [] (vec (arr/array-seq a))))

(defn- named-atom
  "The store's atom for one namespaced key, created (with initial
   value nil) on first use and cached in the store handle."
  [{:keys [base atoms]} kind key]
  (let [k [kind key]]
    (or (get @atoms k)
        (let [a (e/atom {:id (keyword "narcis.store" (str kind "-" (sanitize key)))
                         :persistent base}
                        nil)]
          (swap! atoms assoc k a)
          a))))

(defn open [{:keys [dir name]}]
  (let [base (str dir "/" name)]
    ;; A regular Eve disk domain at base, with Eve's defaults —
    ;; journal included. Opening it now is the store's lifecycle:
    ;; the domain is created at open, closed at close!. The store's
    ;; atoms are regular Eve atoms in it (see named-atom).
    (ea/persistent-atom-domain base)
    {:impl :eve :base base :atoms (atom {})}))

(defn put! [store buffer-name value]
  (let [a (named-atom store "versions" buffer-name)]
    (eh/swap! a
              (fn [v]
                (if (nil? v)
                  (->Versioned value nil)
                  (->Versioned value
                               (arr-push (-hist v) (-cur v))))))
    (let [v @a]
      (dec (inc (arr-len (-hist v)))))))

(defn current [store buffer-name]
  (let [v @(named-atom store "versions" buffer-name)]
    (when (some? v) (-cur v))))

(defn version [store buffer-name i]
  (let [v @(named-atom store "versions" buffer-name)]
    (when (some? v)
      (nth (conj (arr-vec (-hist v)) (-cur v)) i nil))))

(defn version-count [store buffer-name]
  (let [v @(named-atom store "versions" buffer-name)]
    (if (nil? v) 0 (inc (arr-len (-hist v))))))

;; -- generic named atoms (narcis.buffer's editor + buffer state) --

(defn open-atom
  "Open the store's atom named key in the domain, with initial
   value init when the atom has never been set. Returns a handle
   for store/atom-deref and store/atom-swap!."
  [store key init]
  (let [a (named-atom store "atom" key)]
    (when (nil? @a)
      (eh/swap! a (fn [v] (if (nil? v) init v))))
    {:kind :eve :atom a}))

(defn atom-deref [handle]
  @(:atom handle))

(defn atom-read
  "(f value) inside a read region: the value in place, f's answer
   as plain data (see narcis.store/atom-read)."
  [handle f]
  (e/$ (f @(:atom handle))))

(defn atom-swap! [handle f]
  (eh/swap! (:atom handle) f))

(defn close! [{:keys [base]}]
  (ea/close-atom-domain! (ea/persistent-atom-domain base))
  nil)
