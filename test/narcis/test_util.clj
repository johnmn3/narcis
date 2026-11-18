(ns narcis.test-util
  "Test and bench scaffolding: one in-process Eve atom domain
   (eve/atom-domain — Eve's public API) for code that runs outside
   a buffer atom. The library builds documents only inside a swap!
   on an Eve atom, where the nodes are the atom's own; tests,
   benches and tools build standalone documents the same way, by
   running inside a swap! on this domain. Nothing here touches
   Eve's internals — an app tests against the library's public
   surface or it is not testing the library. (Probe:
   narcis.probe-domain — swap! on the atom-domain constructs and
   reads back; plain construction outside a swap! is refused by
   Eve itself.)"
  (:require [eve.alpha :as eve]))

(def ^:private doc-domain
  ;; One in-process domain for standalone documents, sized for
  ;; editor-scale trees (a line is a block of its own: a million
  ;; lines is a million small blocks and their leaves).
  (delay (eve/atom-domain nil
           {:capacities {0 (* 64 1024 1024) 1 (* 64 1024 1024)
                         2 (* 32 1024 1024) 3 (* 32 1024 1024)
                         4 (* 64 1024 1024) 5 (* 32 1024 1024)}
            :coalesc-descriptors 262144})))

(def ^:private ^:dynamic *in-doc-domain* false)

(defn with-heap-domain
  "(f) inside a swap! on the test domain, so Eve data can be
   built; (f)'s value is the answer. A nested call runs (f) as
   it stands."
  [f]
  (if *in-doc-domain*
    (f)
    (binding [*in-doc-domain* true]
      (let [out (volatile! nil)]
        (swap! @doc-domain (fn [_] (vreset! out (f)) nil))
        @out))))
