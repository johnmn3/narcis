# Narcis upstream: Jonquil

**Repository:** https://github.com/b12n-oss/jonquil
**Pinned commit:** `0ac909914bd5e355967c1acab7022014d1e97711` (v0.4.4, 2026-11-16)

Narcis lifts Jonquil's pure core — text analysis, editing and
structural ops, the formatter, the console's decisions, the evaluator,
the menus as data — and runs it over an Eve document in a browser page. Jonquil itself ports DrClojure-Neo; the
original UPSTREAM.md follows below the lift table, unchanged.

| Narcis file | Jonquil source | How |
|---|---|---|
| `src/narcis/syntax.clj` | `src/jonquil/syntax.clj` | Lifted verbatim; delta: the `jonquil.dialect` dependency is inlined as data (the Jolt profile's `:special-forms` / `:builtins` sets — the only two values read from it) |
| `src/narcis/edit.clj` | `src/jonquil/edit.clj` | Lifted verbatim apart from the namespace; a Narcis rope layer is appended (snapshots from rope slices, `:edits` applied to a rope as one new root) |
| `test/narcis/syntax_test.clj` | `test/jonquil/syntax_test.clj` | Ported (namespace only) |
| `test/narcis/upstream_syntax_test.clj` | `test/jonquil/upstream/syntax_test.clj` | Ported (namespace only; DrClojure's tests via Jonquil) |
| `test/narcis/edit_test.clj` | `test/jonquil/edit_test.clj` | Ported (namespace only; the `apply-edits` oracle is unchanged) |
| `test/narcis/edit_doc_test.clj` | — | Narcis's own: the same ops driven through the document layer (`narcis.text`), checked against the text oracle |
| `src/narcis/paint.clj`, `src/narcis/theme.clj` | `src/jonquil/paint.clj`, `src/jonquil/theme.clj` | Lifted (S3); their own headers name the deltas |
| `src/narcis/eval.clj` | `src/jonquil/eval.clj` | Lifted verbatim apart from the namespace: Run, stdin, output and Stop over Jolt's interrupt token (`jolt.host`) |
| `test/narcis/eval_test.clj` | `test/jonquil/eval_test.clj` | Ported (namespace only) |
| `src/narcis/console.clj` | `src/jonquil/console.clj` | Lifted verbatim apart from the namespaces it requires: what Return does, how a result reads, the console's styles |
| `test/narcis/console_test.clj` | `test/jonquil/console_test.clj` | Ported (namespace only) |
| `src/narcis/commands.clj` | `src/jonquil/commands.clj` | Lifted verbatim apart from the namespaces: Run Selection, Run Form, the bracket check before Run, the formatter's ops, find and replace over a snapshot, zoom steps |
| `test/narcis/commands_test.clj` | `test/jonquil/commands_test.clj` | Ported; delta: the two Paren-stable format tests are left out (they drive `jonquil.parinfer-edit`, not lifted) |
| `src/narcis/structural.clj` | `src/jonquil/structural.clj` | Lifted verbatim apart from the namespaces: slurp and barf, raise, splice, wrap, rewrap, drag, transpose, join, split, convolute, kill and copy a form, select and move by form |
| `test/narcis/structural_test.clj` | `test/jonquil/structural_test.clj` | Ported (namespaces only) |
| `src/narcis/menus.edn` | `src/jonquil/menus.edn` | Copied unchanged apart from the namespace named in its header comment; `narcis.menus` narrows it to the commands Narcis has |

Jonquil is distributed under the Eclipse Public License 2.0 (see
LICENSE and NOTICE in this repo, copied from Jonquil).

# Narcis upstream: eve.text

**Repository:** https://github.com/johnmn3/atom.broker (`libs/eve-text`)
**Pinned commit:** `03f5277a9` (branch `claude/trusting-planck-luz9w2`, 2026-11-17)

The buffer's text is an `eve.text` document: CodeMirror 6's `Text` as Eve
data (a B-tree of lines, every edit a path copy). The library's two source
files are carried here unchanged so the app runs from this clone and an Eve
checkout alone; its tests, bench and report stay in the library. A change
to either file is made there and re-copied, never edited here.

| Narcis file | eve-text source | sha256 |
|---|---|---|
| `src/eve/text.cljc` | `libs/eve-text/src/eve/text.cljc` | `bef1719dca1299c979e1220b528bb0b1a3e2f87c3c7f7cebe3dc5fc36fd76d1e` |
| `src/eve/text/store.cljc` | `libs/eve-text/src/eve/text/store.cljc` | `eeb3a07a3a2e43683b06565dce652629c1092ce497e9033570649aef4db3c3d9` |

`src/narcis/text.clj` is Narcis's own: the document read and edited in
Narcis's conventions (0-based lines, the file's line ending kept beside the
document), and the file door (a file read in through eve.text's builder).

---

# Upstream: DrClojure-Neo

**Repository:** https://github.com/kimtg/DrClojure-Neo  
**Pinned commit:** [`7056c61`](https://github.com/kimtg/DrClojure-Neo/commit/7056c61e09f8e9ad3c63fc4b1bd9ac35d15ad3ed) (2026-09-28), also in `UPSTREAM_PIN`  
**Checking for new commits:** `jolt upstream:diff` (see [Porting and attribution](docs/guide/porting-and-attribution.md#staying-in-sync))

## Lifted code

Jonquil ports the pure core of DrClojure, lifting its lexing, analysis, and completion code.

| Jonquil file | Upstream source | How |
|---|---|---|
| `src/jonquil/syntax.clj` | `src/DrClojure/syntax.clj` | Lifted |
| `test/jonquil/upstream/syntax_test.clj` | `test/DrClojure/syntax_test.clj` | Ported |
| `src/jonquil/paint.clj` | `src/DrClojure/syntax.clj` | Re-expresses `highlight-doc!` |
| `src/jonquil/edit.clj` | `src/DrClojure/ui.clj` | Re-expresses handlers |
| `src/jonquil/console.clj` | `src/DrClojure/ui.clj` | Re-expresses `attr-normal`, `attr-prompt`, `attr-result`, `attr-error`, `attr-system` as hex colours |
| `src/jonquil/eval.clj` | `src/DrClojure/eval.clj` | Reworks |
| `test/jonquil/eval_test.clj` | `test/DrClojure/eval_test.clj` | Ports upstream tests, plus new rows |
| `test/jonquil/paint_test.clj` | `test/DrClojure/syntax_test.clj` | Re-expresses 3 upstream deftests |
| `test/jonquil/edit_test.clj` | `test/DrClojure/core_test.clj` | Cases derived from upstream |
| `src/jonquil/parinfer.clj` | `src/DrClojure/parinfer.clj` | Lifted |
| `test/jonquil/upstream/parinfer_test.clj` | `test/DrClojure/parinfer_test.clj` | Ported, without the Swing deftest |
| `test-resources/parinfer/*.json` | `test-resources/parinfer/*.json` | Copied unchanged. These are parinfer.js v3.13.1's own test cases (Shaun Williams and contributors, MIT; see `NOTICE` and `licenses/parinfer.js-LICENSE.md`) |

## Code deltas from upstream

- `src/jonquil/syntax.clj`: `compute-smart-indent` takes upstream `7056c61`'s rule (no dedent before a closer while a bracket before the caret is open) but not its method. Upstream scans the text before the caret on every Enter, which measured 82 ms at the end of a 4574-line file. Jonquil's private `prefix-unclosed?` answers the same question from the painter's cached whole-buffer `compute-brackets` result when the cached text is equal (about 0.2 ms there). Otherwise it scans the text before the caret, uncached, as upstream does. That happens on an Enter within about 120 ms of the last edit, before the painter has caught up, and on an Enter that replaces a selection. It's only checked when Enter is right before a closer and the line before the caret opened no form. A test checks it against upstream's prefix scan at every caret position. The rule also means the dedent before a closer now applies only when nothing before the caret is open, so in well-formed code a new line before `))` keeps the body's indent. That's upstream's intent, so don't restore the old dedent.
- `src/jonquil/parinfer.clj`: `array-slice` copies through `(subvec (vec arr) f t)` in place of `.subList`, which the Jolt releases up to v0.8.23 lack (jolt-lang/jolt#1437 added it on main, unreleased as of 2026-10-29). Upstream copies the view into a new `ArrayList` at once, so the result is the same, and all 154 fixture cases pass. Once Jonquil's minimum Jolt has `.subList`, this can go back to upstream's line.
- `test/jonquil/upstream/syntax_test.clj`: in `get-autocomplete-candidates-test` the `deftest` closed one form early, so its last three `testing` forms (blank-prefix ordering, user definitions first, and the 100-query timing) ran at load time, outside clojure.test, and a failure there was printed but never counted. They are inside the `deftest` now (8 assertions that count), and the timing budget is 250 ms where upstream's is 50 ms, which measured 65 to 71 ms on loaded machines (2026-11-13).
- `test/jonquil/upstream/parinfer_test.clj` reads the fixtures with `clojure.data.json` (`:key-fn keyword`) in place of upstream's `test/DrClojure/json_helper.clj`, which isn't lifted. Both give keyword keys.
- `src/jonquil/parinfer.clj`: `line-col->offset` counts each crossed line's own ending (2 for `\r\n`), where upstream adds 1 per line, so on CRLF text it landed one char short per line above the target and disagreed with `offset->line-col` (`(line-col->offset "ab\r\ncd" 1 1)` gave 4, want 5; found in the review of Jonquil PR 141, fixed 2026-10-29 with a round-trip test over every offset). The position between `\r` and `\n` has no line/column address and clamps to the ending's start, in both helpers alike. The same fix is offered upstream as [kimtg/DrClojure-Neo#1](https://github.com/kimtg/DrClojure-Neo/pull/1) (2026-10-29, their suite green on the branch); once a pinned commit carries it, this delta retires.
- `src/jonquil/syntax.clj`: `compute-brackets-equal` is Jonquil's. It answers from `compute-brackets`'s cache when the cached text is equal, not just the same object, so `jonquil.parinfer-edit` reuses the painter's result (about 0.2 ms against an 80 ms scan on a 262k-char file).
- The editor side of Parinfer is Jonquil's own `jonquil.parinfer-edit`, not upstream's `setup-parinfer!`. Upstream runs Parinfer on the whole buffer after each edit. Jonquil runs it on the top-level forms the edit touched, skips forms over 300 lines, fixes a form's indentation with Paren mode before Smart or Indent mode first touch it, and, since 2026-10-29, works incrementally between keystrokes in one form (the editor feeds the changed span and reuses its own last text).
- `src/jonquil/syntax.clj`: `special-forms` and `core-builtins` read the Jolt dialect profile (`(:special-forms dialect/jolt)`, `(:builtins dialect/jolt)` in `src/jonquil/dialect.clj`), which holds upstream's two sets verbatim, so the file also requires `jonquil.dialect`. A re-sync that changes either set edits `src/jonquil/dialect.clj`.
- `src/jonquil/syntax.clj`: four clj-kondo cleanups that change no behavior. The unused `Matcher` import and two unused bindings are gone, `format-autocomplete-doc` builds its string with `str` where upstream ignores `conj!`'s return value, and `compute-line-indentations` has one `let` where upstream nests two. A re-sync that brings any of them back makes `jolt lint:strict` fail again.

## Not ported

From `7056c61`, the engine and its tests are lifted, and the editor side is
re-expressed rather than copied: `jonquil.parinfer-edit` plus the editor's
begin/end-user-action hook do what upstream's `setup-parinfer!`,
`update-document-diff!` and the Parinfer branches of `handle-smart-enter!`
and the auto-bracket handlers do, per form instead of whole-buffer (see
"Code deltas"). `test-format-all-and-selection-robustness` in
`test/DrClojure/parinfer_test.clj` stays behind: it drives Swing.

## Syncing with upstream

To stay current with upstream developments:

1. Run `jolt upstream:diff` to list upstream commits that touch `src/` or `test/` since the pin.
2. Review each commit; port what matters to Jonquil.
3. Bump `UPSTREAM_PIN` to the new commit SHA in the same commit that lands the port.

For the Parinfer engine, a sync is mechanical:

1. `diff <(git -C <clone> show <pin>:src/DrClojure/parinfer.clj) src/jonquil/parinfer.clj`
   must show exactly the deltas listed above (the ns line, `array-slice`,
   `line-col->offset`), and nothing else.
2. `cmp` each `test-resources/parinfer/*.json` against upstream's copy;
   the fixtures are byte-identical on purpose.
3. Run `jolt test jonquil.upstream.parinfer-test`: `run-case` is upstream's
   check, and the strict checks beside it compare tab stops, paren trails
   and error positions wherever a fixture states them.
4. The `array-slice` delta leaves when Jonquil's minimum Jolt reaches a
   release carrying jolt-lang/jolt#1437 (`.subList`).

This keeps Jonquil's provenance transparent and drift visible.

# Upstream: clojure-cli.repl

**Repository:** https://github.com/clojure/clojure-cli.repl  
**Pinned commit:** [`775e858`](https://github.com/clojure/clojure-cli.repl/commit/775e8587abd65d9f202b15865eae21e834b57903) (2026-09-28)  
**Licence:** EPL-1.0

## Lifted code

Jonquil ports the inspector's view code: the pure functions that turn a value into the panel's rows, paths and table cells, with the nREPL middleware wiring left behind.

| Jonquil file | Upstream source | How |
|---|---|---|
| `src/jonquil/inspect/view.clj` | `server/src/clojure_cli/repl/inspect.clj` | Lifted |

## Code deltas from upstream

- The ns becomes `jonquil.inspect.view`; the `:require` list drops the three `nrepl.*` requires (`nrepl.middleware`, `nrepl.misc`, `nrepl.transport`), which existed only to wire the nREPL middleware.
- The global `(def view (atom nil))` is gone. Every lifted function (`render`, `respond`, `start`, `toggle-table`, `in`, `out`, `page`, `toggle-meta`, `def-as`) takes `view` as its first argument instead and passes it on at every internal call; the atom is now owned by the caller.
- `sessions`, `remember-session`, `session-binding`, `op-fns`, `middleware` and the `set-descriptor!` call are gone along with the nREPL glue they served.
- `start`'s signature becomes `[view value rows cols]`, with the frame `{:label "*1" :value (datafy value)}` in place of upstream's `(session-binding session-id #'clojure.core/*1)` lookup.
- `def-as`'s signature becomes `[view target-ns var-name]`; `target-ns` is passed in directly in place of upstream's `(session-binding session-id #'clojure.core/*ns*)` lookup.
- Every other line inside the lifted functions is unchanged from upstream.
- Jonquil adds one function of its own, `run-op`, at the end of the file: a dispatch over the lifted ops that turns a throw into the current view plus `:note`, the way the server's `middleware` answered errors.
