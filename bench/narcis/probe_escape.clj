(ns narcis.probe-escape
  "Is a document read out of a $ region still readable after the
   atom lets it go? The shape of launcher/beyond-payload: a window
   is served from (buffer/extents ed path), whose :doc values pass
   $'s materialization untouched (eve deftypes carry no IPlain), so
   the doc leaves the region unpinned; the assembler then absorbs
   or merges that extent — the app atom drops it — while the window
   is still being rendered from it.

   Arms, each over the same fresh docs:
     :escaped  doc taken out with $, dropped from the atom, the
               domain churned (builds and drops, so the zero queue
               flushes and blocks are reused), then read
     :rooted   the same, but a second atom keeps the doc (the
               control: what a rooted read sees)
     :door     the doc found in the atom and read inside ONE region
               while the drop and the churn land — the shape of
               buffer/read-extent, the fix
   A read that throws or answers other text than the doc was built
   from is a FAIL.

   Each arm runs in a fresh domain of its own (an earlier one-domain
   version was order-sensitive: run after :door, :escaped read clean).

   Run: scripts/jolt-run.sh narcis.probe-escape <dir> [rounds] [arms,
        e.g. escaped,door,rooted]

   Measured 2026-11-20 (pinned Eve a14775a, Jolt v0.8.28 release
   binary, x86_64; 30 rounds, fresh domain per arm, both orders):
   escaped 0/30 ok — every read answered ANOTHER document's lines
   (the churn's own, e.g. "R100041 L00256 ..."); door 30/30;
   rooted 30/30."
  (:require [eve.alpha :as eve :refer [$]]
            [clojure.string]
            [narcis.text :as text]))

(defn- doc-text [k]
  (let [sb (StringBuilder.)]
    (dotimes [i 400]
      (.append sb (format "R%d L%05d the quick brown fox jumps over the lazy dog\n" k i)))
    (str sb)))

(defn- churn! [scratch k]
  ;; builds and drops: every swap replaces the scratch's root, so the
  ;; previous build reaches a zero count and is queued for reuse
  (dotimes [j 60]
    (swap! scratch (fn [_] (text/from-string (doc-text (+ 100000 (* 1000 k) j)))))))

(defn- one-round [app keeper scratch k arm]
  (let [s (doc-text k)]
    (swap! app assoc :x nil)
    (swap! app (fn [m] (assoc m :x (text/from-string s))))
    (when (= arm :rooted)
      (swap! keeper (fn [_] ($ (get @app :x)))))
    (if (= arm :door)
      ;; buffer/read-extent's shape: found in the atom and read inside
      ;; one region; the drop and the churn land while the region holds
      ($ (let [d (get @app :x)]
           (swap! app assoc :x nil)
           (churn! scratch k)
           (try
             (let [got (text/to-string d)]
               (if (= got s) :ok [:wrong (subs got 0 (min 60 (count got)))]))
             (catch Throwable t [:threw (ex-message t)]))))
    ;; out of the region, as buffer/extents hands it out
    (let [d ($ (get @app :x))]
      ;; the assembler absorbs it: the app atom lets it go
      (swap! app assoc :x nil)
      (churn! scratch k)
      (try
        (let [got (text/to-string d)]
          (if (= got s)
            :ok
            [:wrong (subs got 0 (min 60 (count got)))]))
        (catch Throwable t [:threw (ex-message t)]))))))

(defn -main [& [dir rounds order]]
  (let [dir (or dir "/tmp/probe-escape")
        rounds (Integer/parseInt (or rounds "20"))
        arms (if order
               (mapv keyword (clojure.string/split order #","))
               [:rooted :door :escaped])]
    ;; each arm in a FRESH domain of its own: an arm's reclamation
    ;; state (the zero queue, pins, free lists) must not reach the next
    (doseq [arm arms]
      (let [base (str dir "/" (name arm) "-" (System/nanoTime) "/narcis/text3")
            _ (eve/persistent-atom-domain base)
            mk (fn [n] (eve/atom {:id (keyword "narcis.probe-escape" n) :persistent base} nil))
            app (mk "app")
            keeper (mk "keeper")
            scratch (mk "scratch")]
        (swap! app (fn [_] {}))
        (let [rs (mapv (fn [k] (one-round app keeper scratch k arm)) (range rounds))
              bad (remove #{:ok} rs)]
          (println (format "%-8s rounds %d  ok %d  bad %d" (name arm) rounds
                           (count (filter #{:ok} rs)) (count bad)))
          (doseq [b (take 3 bad)] (println "   " (pr-str b))))))
    (println "PROBE-ESCAPE DONE")
    (System/exit 0)))
