(ns narcis.nav
    "Paredit caret motion over text: forward-sexp / backward-sexp built
   on narcis.syntax's tokenizer and bracket matches — the same
   analysis the painter uses, so motion and painting can never
   disagree about what a form is. Whitespace and comments are not
   tokens, so motion skips them for free; a bracket token jumps to
   its match. Pure fns of text + offset; the caller supplies the text
   (a whole buffer, or a window slice around the caret on a huge one)
   and rebases."
    (:require [narcis.syntax :as syntax]))

(defn- bracket-kind [text [t s _]]
       (when (= t :bracket)
             (if (contains? syntax/open->close (.charAt text s)) :open :close)))

(defn forward-sexp
      "Offset just past the next form at or after pos in text: a bracket
   jumps to just past its match, any other token to its end. A pos
   inside a non-bracket token goes to that token's end. EOF when no
   form follows."
      [text pos]
      (let [tokens (syntax/tokenize text)
            matches (:matches (syntax/compute-brackets text))
            tok (first (filter (fn [[_ _ e]] (> e pos)) tokens))]
           (if (nil? tok)
               (count text)
               (let [[_ s e] tok
                     kind (bracket-kind text tok)]
                    (cond
                     (= kind :open) (if-let [m (get matches s)] (inc m) e)
                     :else e)))))

(defn backward-sexp
      "Offset at the start of the previous form ending at or before pos:
   a close bracket jumps back to its match, any other token to its
   start. A pos inside a non-bracket token goes to that token's
   start. 0 when no form precedes."
      [text pos]
      (let [tokens (syntax/tokenize text)
            matches (:matches (syntax/compute-brackets text))
            tok (last (filter (fn [[_ s _]] (< s pos)) tokens))]
           (if (nil? tok)
               0
               (let [[_ s e] tok
                     kind (bracket-kind text tok)]
                    (cond
                     (= kind :close) (if-let [m (get matches s)] m s)
                     (= kind :open) s
                     (< pos e) s
                     :else s)))))
