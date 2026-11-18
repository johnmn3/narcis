(ns narcis.probe-domain
  "Which PUBLIC Eve shape replaces test-util's internal heap-domain
   binding? Candidates:
   A. swap! on the MmapAtomDomain eve/atom-domain returns, with
      the sizing passed as atom-domain opts.
   B. plain construction after eve/atom-domain seats its domain.
   C. swap! on a named in-process eve/atom (default-sized domain).
   Each builds a document and reads it back outside the swap.
   Run: scripts/jolt-run.sh narcis.probe-domain"
  (:require [eve.alpha :as eve] [narcis.text :as text]))

(def big-opts
  {:capacities {0 (* 64 1024 1024) 1 (* 64 1024 1024)
                2 (* 32 1024 1024) 3 (* 32 1024 1024)
                4 (* 64 1024 1024) 5 (* 32 1024 1024)}
   :coalesc-descriptors 262144})

(defn -main []
  (println "PROBE A: swap! on (eve/atom-domain nil big-opts)")
  (let [dom (eve/atom-domain nil big-opts)
        out (volatile! nil)]
    (println "PROBE A swap:"
             (try (swap! dom (fn [_] (vreset! out (text/from-string "a\nb\nc\n")) nil))
                  "ok"
                  (catch :default t (str "THREW " (ex-message t)))))
    (println "PROBE A read:" (try {:chars (text/chars @out) :lines (text/lines @out)}
                                  (catch :default t (str "THREW " (ex-message t))))))
  (println "PROBE B: plain construction, fresh atom-domain seated")
  (let [_ (eve/atom-domain nil big-opts)]
    (println "PROBE B:" (try (let [d (text/from-string "x\ny\n")]
                               {:chars (text/chars d) :lines (text/lines d)})
                             (catch :default t (str "THREW " (ex-message t))))))
  (println "PROBE C: swap! on a named in-process atom")
  (let [a (eve/atom {:id :narcis.probe-domain/scratch} nil)
        out (volatile! nil)]
    (swap! a (fn [_] (vreset! out (text/from-string "p\nq\n")) nil))
    (println "PROBE C read:" {:chars (text/chars @out) :lines (text/lines @out)}))
  (println "PROBE-DOMAIN DONE"))
