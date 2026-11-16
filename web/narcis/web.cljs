(ns narcis.web
  "Hello page source of record (S0). The launcher serves a hand-mirrored
   JS twin of this in the page HTML until the S3 cljs build lands.")

(defn render-buffer! [text]
  (set! (.-textContent (.getElementById js/document "buffer")) text))

(defn ^:export init []
  (-> (js/fetch "/buffer")
      (.then (fn [r] (.text r)))
      (.then render-buffer!)))
