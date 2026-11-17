(ns narcis.forms
  "Form trees over buffer text: jonquil.commands' form-tree, extracted
   (jonquil.commands itself stays behind — it carries the console and
   Run machinery). A form is {:start :end :open :kids}; brackets in
   strings, comments and character literals don't count, a reader
   prefix ('x, #{..}, ^meta) belongs to its form, #_ discards, and an
   unclosed list has no forms. Ported from jonquil @0ac90991 (EPL-2.0,
   see UPSTREAM.md)."
  (:require [narcis.syntax :as syntax]))

(def ^:private prefix-chars
  ;; Reader prefixes that belong to the form right after them: quote,
  ;; syntax-quote, unquote, deref, and # (sets, fns, reader conditionals).
  #{\' \` \~ \@ \# \?})

(defn- hash-marked-symbol?
  "True when tok is the symbol sym written right after a # (the ? of #? or
  the _ of #_), which the tokenizer reads as a symbol of its own."
  [text sym [t s e]]
  (and (= :symbol t) (pos? s) (= sym (subs text s e))
       (= \# (.charAt ^String text (int (dec s))))))

(defn- meta-bracket?
  "True when the bracket at offset open (or nil) is written right after a ^."
  [text open]
  (and open (pos? open) (= \^ (.charAt ^String text (int (dec open))))))

(defn- absorb-form
  "parent with form folded in: a metadata tag or ^-prefixed bracket is held
  as :meta-start for the next form, a pending #_ swallows the form, and
  anything else joins parent's :kids (taking the held metadata start)."
  [text parent {:keys [start open kind] :as form}]
  (cond
    (= kind :metatag)
    (update parent :meta-start (fn [ms] (or ms start)))

    (meta-bracket? text open)
    (update parent :meta-start (fn [ms] (or ms (dec open))))

    :else
    (let [ms (:meta-start parent)
          form (cond-> form ms (assoc :start ms))
          parent (dissoc parent :meta-start)]
      (if (pos? (:discards parent 0))
        (update parent :discards dec)
        (update parent :kids conj form)))))

(defn- prefix-start
  "The offset where the form whose token i starts at s really starts: s
  moved back over reader prefix characters, but not into the token before."
  [{:keys [text toks]} i s]
  (let [floor (if (pos? i) (nth (nth toks (dec i)) 2) 0)]
    (loop [p s]
      (if (and (> p floor) (contains? prefix-chars (.charAt ^String text (int (dec p)))))
        (recur (dec p))
        p))))

(defn- close-bracket
  "stack after a closing bracket ch ending at e: the open form on top is
  finished and absorbed by its parent when ch matches it, else the closer
  is ignored."
  [{:keys [text stack ch e]}]
  (let [top (peek stack)]
    (if (and (> (count stack) 1) (= ch (:close-ch top)))
      (let [form (-> top (dissoc :close-ch) (assoc :end e))
            stack (pop stack)]
        (conj (pop stack) (absorb-form text (peek stack) form)))
      stack)))

(defn- tree-step
  "stack after reading token i of ctx's :toks."
  [{:keys [text toks] :as ctx} stack i]
  (let [[t s e :as tok] (nth toks i)
        ch (when (= :bracket t) (.charAt ^String text (int s)))]
    (cond
      (hash-marked-symbol? text "_" tok)
      (conj (pop stack) (update (peek stack) :discards (fnil inc 0)))

      (and ch (syntax/open->close ch))
      (conj stack {:start (prefix-start ctx i s) :open s :close-ch (syntax/open->close ch) :kids []})

      ch
      (close-bracket {:text text :stack stack :ch ch :e e})

      :else
      (let [form (cond-> {:start (prefix-start ctx i s) :end e} (= t :metatag) (assoc :kind :metatag))]
        (conj (pop stack) (absorb-form text (peek stack) form))))))

(defn form-tree
  "text's forms as a tree: a vector of top-level {:start :end :open :kids}.
  A bracketed form has :open (its opening bracket's offset) and :kids, an
  atom (symbol, keyword, number, string, character, regex) has neither.
  :start takes in any reader prefix written right before the form ('(..),
  #{..}, @x, #?(..)), and a leading ^meta or ^{...} metadata form stays
  attached the same way (it can stack: ^:a ^:b (def x 1)). A form right
  after #_ is discarded and never appears in the tree; #_ #_ discards two.
  Comments are skipped, a closer that doesn't match is ignored, and a form
  never closed is dropped along with everything in it, so an unfinished
  buffer has nothing to run there. The tokenizer reads the ? of #? (a
  reader conditional) and the _ of #_ as a symbol of its own; the ? is
  dropped and its two characters count as prefix, the _ is dropped and
  marks the discard."
  [text]
  (let [toks (filterv (fn [[t :as tok]] (not (or (= :comment t) (hash-marked-symbol? text "?" tok))))
                      (syntax/tokenize text))
        ctx {:text text :toks toks}
        n (count toks)]
    (loop [i 0 stack [{:kids []}]]
      (if (= i n)
        (:kids (first stack))
        (recur (inc i) (tree-step ctx stack i))))))
