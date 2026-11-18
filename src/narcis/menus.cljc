(ns narcis.menus
  "The menus as data: jonquil's menus.edn (lifted, beside this file),
   narrowed to the commands Narcis has. An item whose command Narcis
   lacks is left out; a submenu or a section left empty goes with it.
   The page draws what menus answers and runs an item by its command
   id, so a command ported later appears by being named in supported."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]))

(def supported
  "The command ids the page can run."
  (into #{:tab/new :file/open :file/save :file/save-as :tab/close
          :edit/toggle-comment :edit/indent :edit/unindent
          :edit/format-all :edit/format-selection :edit/rename
          :tree/toggle :focus/toggle :tab/next :tab/previous
          :view/zoom-in :view/zoom-out :view/zoom-reset :view/cycle-theme
          :find/open :find/next :find/previous
          :run/run :run/selection :run/form :run/stop :console/clear
          :help/quick-doc :help/version}
        (map (fn [n] (keyword "paredit" n))
             ["slurp-forward" "barf-forward" "slurp-backward" "barf-backward"
              "raise" "splice" "wrap-round" "wrap-square" "wrap-curly" "wrap-set"
              "wrap-quote" "rewrap-round" "rewrap-square" "rewrap-curly" "rewrap-set"
              "drag-backward" "drag-forward" "transpose" "join" "split" "convolute"
              "kill-form" "kill-form-backward" "kill-right" "kill-left"
              "kill-list-forward" "kill-list-backward" "splice-killing-forward"
              "splice-killing-backward" "copy-form" "expand-selection"
              "shrink-selection" "select-forward" "select-backward" "forward"
              "backward" "up" "down" "forward-up" "backward-down" "list-start"
              "list-end"])))

(defn- narrow-item [item]
  (cond
    (:command item) (when (contains? supported (:command item))
                      {:command (subs (str (:command item)) 1) :label (:label item)})
    (:sections item) (let [secs (vec (keep (fn [sec]
                                             (let [is (vec (keep narrow-item sec))]
                                               (when (seq is) is)))
                                           (:sections item)))]
                       (when (seq secs) {:label (:label item) :sections secs}))))

(defn narrow
  "menus (the edn's vector) with only supported commands."
  [menus]
  (vec (keep narrow-item menus)))

(defn menus
  "The menus the page draws, from the edn text of jonquil's menus (its
   own name in a label reads as Narcis's)."
  [edn-text]
  (narrow (edn/read-string (str/replace edn-text "Jonquil" "Narcis"))))
