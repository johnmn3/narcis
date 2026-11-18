(ns narcis.test-util
  "Test and bench scaffolding: a heap Eve domain to build documents
   in, for code that runs outside a buffer atom. The library itself
   builds documents only inside a swap! on an Eve atom, where the
   nodes are the atom's own — this domain is how tests, benches and
   tools build standalone documents of their own."
  (:require [eve.deftype-proto.alloc :as alloc]
            [eve.deftype-proto.data :as d]))

(def ^:private heap-sio
  ;; One heap domain for standalone documents, sized for editor-scale
  ;; trees (a line is a block of its own: a million lines is a million
  ;; small blocks and their leaves).
  (delay (alloc/heap-domain-sio!
          {:capacities {0 (* 64 1024 1024) 1 (* 64 1024 1024)
                        2 (* 32 1024 1024) 3 (* 32 1024 1024)
                        4 (* 64 1024 1024) 5 (* 32 1024 1024)}
           :coalesc-descriptors 262144})))

(defn with-heap-domain
  "(f) with a heap domain to build documents in, unless a domain is
   already current (inside a swap! it is the atom's)."
  [f]
  (if (some? d/*domain-sio*)
    (f)
    (binding [d/*domain-sio* @heap-sio]
      (f))))
