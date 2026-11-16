(ns narcis.store-eve
  "Eve disk-atom binding for narcis.store. Loaded dynamically by
   narcis.store only on hosts where eve.atom loads.

   Shape (release Eve, examples/disk_domain.cljc): a named atom in a
   disk domain at a base path —
     (eve.alpha/atom {:id :narcis/buffers :persistent <dir>} {:buffers {}})
   Every process that opens the same :id at the same base sees the same
   atom; swap! is a CAS on the slot's root pointer."
  #?(:bb  (:require [eve.alpha :as e] [eve.atom :as ea])
     :clj (:require [eve.alpha :as e] [eve.atom :as ea])
     :cljs nil))

#?(:cljs nil
   :default
   (do
     (defn open [{:keys [dir name]}]
       (let [base (str dir "/" name)
             _ (.mkdirs (.getParentFile (java.io.File. base)))
             a (e/atom {:id (keyword name) :persistent base} {:buffers {}})]
         {:impl :eve :base base :atom a}))

     (defn put! [{:keys [atom]} buffer-name value]
       (let [new-state (swap! atom
                              (fn [s]
                                (assoc-in s [:buffers buffer-name]
                                          {:current value
                                           :versions (conj (get-in s [:buffers buffer-name :versions] [])
                                                           value)})))]
         (dec (count (get-in new-state [:buffers buffer-name :versions])))))

     (defn current [{:keys [atom]} buffer-name]
       (get-in @atom [:buffers buffer-name :current]))

     (defn version [{:keys [atom]} buffer-name i]
       (get-in @atom [:buffers buffer-name :versions i]))

     (defn version-count [{:keys [atom]} buffer-name]
       (count (get-in @atom [:buffers buffer-name :versions])))

     (defn close! [{:keys [base]}]
       (ea/close-atom-domain! (ea/persistent-atom-domain base))
       nil)))
