# Narcis upstream: Jonquil

**Repository:** https://github.com/b12n-oss/jonquil
**Pinned commit:** `0ac909914bd5e355967c1acab7022014d1e97711` (v0.4.4, 2026-11-16)

Narcis lifts Jonquil's pure core — text analysis, editing and
structural ops, the formatter, the console's decisions, the evaluator,
the menus as data — and runs it over an Eve document in a browser page. Jonquil itself ports DrClojure-Neo; Jonquil's
upstream record for that lineage follows the eve.text section,
scoped to the files Narcis carries.

The `src/narcis` files are `.cljc`: the lifted code is shared as it
stands, and each host seam it stands on (a blocking queue, a
`java.io` reader or writer, a thread, a socket) is written once per
file behind a reader conditional, as that file's own header or the
seam's comment says. The test files stay `.clj` (they run on Jolt).

| Narcis file | Jonquil source | How |
|---|---|---|
| `src/narcis/syntax.cljc` | `src/jonquil/syntax.clj` | Lifted verbatim; delta: the `jonquil.dialect` dependency is inlined as data (the Jolt profile's `:special-forms` / `:builtins` sets — the only two values read from it) |
| `src/narcis/edit.cljc` | `src/jonquil/edit.clj` | Lifted verbatim apart from the namespace; Narcis's document layer is appended (snapshots from the eve.text document, `:edits` applied to it as one new root) |
| `test/narcis/syntax_test.clj` | `test/jonquil/syntax_test.clj` | Ported (namespace only) |
| `test/narcis/upstream_syntax_test.clj` | `test/jonquil/upstream/syntax_test.clj` | Ported (namespace only; DrClojure's tests via Jonquil) |
| `test/narcis/edit_test.clj` | `test/jonquil/edit_test.clj` | Ported (namespace only; the `apply-edits` oracle is unchanged) |
| `test/narcis/edit_doc_test.clj` | — | Narcis's own: the same ops driven through the document layer (`narcis.text`), checked against the text oracle |
| `src/narcis/paint.cljc`, `src/narcis/theme.cljc` | `src/jonquil/paint.clj`, `src/jonquil/theme.clj` | Lifted (S3); their own headers name the deltas |
| `src/narcis/eval.cljc` | `src/jonquil/eval.clj` | Lifted verbatim apart from the namespace: Run, stdin, output and Stop over Jolt's interrupt token (`jolt.host`) |
| `test/narcis/eval_test.clj` | `test/jonquil/eval_test.clj` | Ported (namespace only) |
| `src/narcis/console.cljc` | `src/jonquil/console.clj` | Lifted verbatim apart from the namespaces it requires: what Return does, how a result reads, the console's styles |
| `test/narcis/console_test.clj` | `test/jonquil/console_test.clj` | Ported (namespace only) |
| `src/narcis/commands.cljc` | `src/jonquil/commands.clj` | Lifted verbatim apart from the namespaces: Run Selection, Run Form, the bracket check before Run, the formatter's ops, find and replace over a snapshot, zoom steps |
| `src/narcis/structural.cljc` | `src/jonquil/structural.clj` | Lifted verbatim apart from the namespaces: slurp and barf, raise, splice, wrap, rewrap, drag, transpose, join, split, convolute, kill and copy a form, select and move by form |
| `test/narcis/structural_test.clj` | `test/jonquil/structural_test.clj` | Ported (namespaces only) |
| `src/narcis/menus.edn` | `src/jonquil/menus.edn` | Copied unchanged apart from the namespace named in its header comment; `narcis.menus` narrows it to the commands Narcis has |
| `src/narcis/docs.cljc` | `src/jonquil/docs.clj` | Lifted verbatim apart from the namespace: documentation and completion lookups resolved in the buffer's namespace |
| `src/narcis/interactions.cljc` | `src/jonquil/session.clj` | Re-expresses Jonquil's session — Run's reset rule and the prompt's namespace rule — over `narcis.eval` and an Eve-atom transcript; the debugger wiring (the break hook, arming, the pause the page serves) is composed here, as Jonquil composes it in its session |
| `src/narcis/debugger.cljc` | `src/jonquil/debugger.clj` | Lifted apart from the namespaces: the frames and locals of an error or a pause, the breakpoint pause and the stepping machinery, all over the host's `jolt.debug`; the frame filter names Narcis's own harness namespaces first and keeps Jonquil's, so the lifted tests' fake frames filter the same way |
| `src/narcis/breakpoints.cljc` | `src/jonquil/breakpoints.clj` | Lifted apart from the namespaces: where in a buffer's text a breakpoint form can go, and the instrumentation of a Run (breakpoints and step points as inserted forms) |
| `src/narcis/changed.cljc` | `src/jonquil/changed.clj` | Lifted apart from the namespaces: which top-level forms changed since they last ran (a `narcis.breakpoints` dependency) |
| `src/narcis/instrument_lock.cljcc` | `src/jonquil/instrument-lock.clj` | Lifted verbatim apart from the namespace: the lock around the host's instrumentation-mode read-then-set |
| `test/narcis/debugger_test.clj` | `test/jonquil/debugger_test.clj` | Ported (namespaces only) |
| `test/narcis/breakpoints_test.clj` | `test/jonquil/breakpoints_test.clj` | Ported (namespaces only), with one test-only delta: where the Jolt running it has no `jolt.debug`, the file stands in the one macro its instrumentation checks expand (`with-break-handler`, catch-all semantics) so the placement corpus still runs, and the live sections take their "not available" path |
| `test/narcis/debugger_wiring_test.clj`, `test/narcis/debugger_live_test.clj`, `test/page/debugger_e2e.cjs` | — | Narcis's own: the console wiring on a Jolt without `jolt.debug` (arming fails clean, a marked Run still runs), the live pause/step/eval flow on one with it, and the same flow driven through the real page |

Everything else — `narcis.buffer`, `narcis.store`, `narcis.text`,
`narcis.view`, `narcis.nav`, `narcis.ops`, `narcis.launcher` and the
browser page (`web/narcis/web.cljs`) — is Narcis's own, written for
this repo against Eve.

Jonquil is distributed under the Eclipse Public License 2.0 (see
LICENSE in this repo — Jonquil's own text, unchanged — and NOTICE).

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

`src/narcis/text.cljc` is Narcis's own: the document read and edited in
Narcis's conventions (0-based lines, the file's line ending kept beside the
document), and the file door (a file read in through eve.text's builder).

---

# Upstream: DrClojure-Neo

**Repository:** https://github.com/kimtg/DrClojure-Neo  
**Pinned commit:** [`7056c61`](https://github.com/kimtg/DrClojure-Neo/commit/7056c61e09f8e9ad3c63fc4b1bd9ac35d15ad3ed) (2026-09-28)

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

## Code deltas from upstream

- `src/jonquil/syntax.clj`: `compute-smart-indent` takes upstream `7056c61`'s rule (no dedent before a closer while a bracket before the caret is open) but not its method. Upstream scans the text before the caret on every Enter, which measured 82 ms at the end of a 4574-line file. Jonquil's private `prefix-unclosed?` answers the same question from the painter's cached whole-buffer `compute-brackets` result when the cached text is equal (about 0.2 ms there). Otherwise it scans the text before the caret, uncached, as upstream does. That happens on an Enter within about 120 ms of the last edit, before the painter has caught up, and on an Enter that replaces a selection. It's only checked when Enter is right before a closer and the line before the caret opened no form. A test checks it against upstream's prefix scan at every caret position. The rule also means the dedent before a closer now applies only when nothing before the caret is open, so in well-formed code a new line before `))` keeps the body's indent. That's upstream's intent, so don't restore the old dedent.
- `test/jonquil/upstream/syntax_test.clj`: in `get-autocomplete-candidates-test` the `deftest` closed one form early, so its last three `testing` forms (blank-prefix ordering, user definitions first, and the 100-query timing) ran at load time, outside clojure.test, and a failure there was printed but never counted. They are inside the `deftest` now (8 assertions that count), and the timing budget is 250 ms where upstream's is 50 ms, which measured 65 to 71 ms on loaded machines (2026-11-13).
- `src/jonquil/syntax.clj`: `compute-brackets-equal` is Jonquil's. It answers from `compute-brackets`'s cache when the cached text is equal, not just the same object, so callers reuse the painter's result (about 0.2 ms against an 80 ms scan on a 262k-char file).
- `src/jonquil/syntax.clj`: `special-forms` and `core-builtins` read the Jolt dialect profile (`(:special-forms dialect/jolt)`, `(:builtins dialect/jolt)` in `src/jonquil/dialect.clj`), which holds upstream's two sets verbatim, so the file also requires `jonquil.dialect`. A re-sync that changes either set edits `src/jonquil/dialect.clj`.
- `src/jonquil/syntax.clj`: four clj-kondo cleanups that change no behavior. The unused `Matcher` import and two unused bindings are gone, `format-autocomplete-doc` builds its string with `str` where upstream ignores `conj!`'s return value, and `compute-line-indentations` has one `let` where upstream nests two. A re-sync that brings any of them back makes `jolt lint:strict` fail again.

## What of this is in Narcis

Narcis carries the DrClojure-descended files listed in the table
above, through their Jonquil forms in the lift table at the top of
this file. Jonquil's Parinfer engine and its fixtures are not ported,
and neither is the inspector (Jonquil's clojure-cli.repl lift); their
sections of Jonquil's upstream record are omitted here. Jonquil's own
UPSTREAM.md is the record for staying in sync with DrClojure; Narcis
re-pins by re-lifting from Jonquil and updating the table above.