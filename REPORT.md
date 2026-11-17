# Narcis S3 — the jonquil editor, joined onto the Eve rope — REPORT

Branch: lane/narcis-s3-joined, johnmn3/narcis — the S3 editor work
(lane/narcis-s3: theme/paint/view/nav/ops, the editor launcher, the
cljs client) composed onto lane/narcis-rope-deftype @9667c12 (the
eve/deftype rope + Eve-atom buffer of REPORT-ROPE-DEFTYPE.md).
Eve pin: release/eve-0.9.3.2 @84bfc7c8. Date: 2026-11-16.
Coordinator (Toby) closed both lanes at John's order, did the join,
and ran every gate below firsthand. No merges.

## The composition

One buffer, one store: S3's editor surface runs entirely on the
rope-deftype line's model — buffer state in Eve atoms, edits as
swap!s, rope nodes as eve.deftype data (John's law). The join needed
exactly one adaptation: the rope buffer gained `rev`, a revision
counter in the editor atom bumped by every swap (buffer swaps
included), which S3's ops/launcher expose to clients as a sync
token. Everything else — view, ops, nav, launcher, the cljs client —
consumes the buffer's public API unchanged. S3's own single-atom
buffer design is superseded and not carried.

## Gates (coordinator-run, jolt v0.8.23, Eve @84bfc7c8)

| Gate | Verdict |
|---|---|
| Editor gate (HTTP session on a 12,001-line file: auto-pair, typing, smart-enter, paredit move, undo×3, kill -9, separate-process probe of the atom, relaunch, undo across restart) | PASS — 17/17 |
| View tests (window payload == jonquil.syntax spans; 1e6-line window bounded) | PASS — 4 tests / 15 assertions |
| Ops tests (typing session: auto-pair, smart-enter, sexp motion, undo) | PASS — 2 tests / 26 assertions |
| Jonquil corpus + S2 suite | PASS — 38 tests / 644 assertions / 0F / 0E |
| Buffer gate (undo walk + undo-to-v0 across restart) | PASS |
| Store gate, S0 gate, rope property tests | PASS (rope lane, same tree) |
| Viewport gate (1M-line file through HTTP; 60-line window payload < 32,768 bytes) | RUNNING at report time with an enlarged server-up budget — see below |

Two contaminated runs are recorded for honesty: the first joined
battery raced itself (a kill aimed at it hit the wrong process, so
two batteries shared ports 8391/8392 and produced interleaved
failures), and its corpus run dropped one wall-clock assertion
under load. Both were re-run clean; the verdicts above are the
clean runs.

## The open perf item (unchanged, Eve-side)

File-open latency is the one unclosed number. Measured tonight:
200k-line ingest = 50.3 s in the standalone heap domain (probe8);
into a disk-domain buffer atom it crawls IO-bound at ~10–20% CPU —
neither domain sizing (now applied in narcis.store: editor-scale
capacities + 262,144 coalescing descriptors) nor :journal? false
moves it (probes 6/7). A 1M-line open therefore takes minutes to
tens of minutes at this pin, which is why the viewport gate runs
with a 2,400 s server-up budget (an env knob; the default stays
600 s). The mechanism hunt and the fix belong to Eve's storage
paths; the measured package (with the journal and sizing
eliminations) is with the eve-release seat, which has it recorded
as a named open item on the release line. Editing itself is
unaffected: per-keystroke ops run ~1–3 ms, and the 12k-line editor
gate passes with wide margins.

## Running it

The launcher runs on **Eve's own jolt seat classpath**, never through
deps.edn: a bare `jolt run` in this clone resolves Eve via gitlibs,
which puts the JVM region adapters on jolt's classpath and dies with
"No dependency provides java.lang.invoke.MethodHandles" (the W1
invocation artifact). You need an Eve checkout on
release/eve-0.9.3.2 (any recent tip; Narcis is verified at 84bfc7c8):

```
git clone https://github.com/johnmn3/narcis.git ~/narcis-app
cd ~/narcis-app && git switch lane/narcis-s3-joined
EVE_RELEASE=<eve-checkout> bash scripts/jolt-run.sh narcis.launcher \
  --dir ~/narcis-app/data --port 8377 --file <path>
```

— the page that opens is the actual editor now (caret, selection,
structural editing), its state in the Eve disk atom.

## App surface (2026-11-17, coordinator)

The editor page is now the whole app surface, not just an editing pane:

- **Menus** — a menu bar (File / Edit / View / Search / Theme / Help)
  whose items are one command registry shared with the palette; every
  item runs a real op. Jonquil's menu shape, only the commands Narcis
  has (Run stays out until the S4 evaluator lands).
- **Theme selection** — the Theme menu lists all of jonquil.theme's
  choices including Auto (resolved against the OS light/dark setting,
  followed live). The choice is stored server-side in the editor atom
  (one swap), so it persists across restarts and is shared by every
  client; Ctrl+Shift+M still cycles. New: buffer :theme,
  ops :theme/use, GET /api/themes.
- **Command palette** — Ctrl+Shift+P / Ctrl+K: every command plus
  "Use theme: X" entries (theme/palette-entries, now consumed).
- **Find bar** — Ctrl+F, Ctrl+G / Ctrl+Shift+G step; new ops
  :find-next/:find-prev select the match server-side (bounded by the
  same exact-text limit as rename).
- **Tabs** — a × on every tab; File > New Tab (new op :new) and
  Save As… (new op :save-as) round out the file commands.
- **File panel** — View > File Tree: a directory browser over the new
  GET /api/list; click a file to open it.

Gates (jolt, Eve @84bfc7c8): editor gate extended with 10 app-surface
checks over HTTP — **27/27** (themes endpoint, :theme/use, theme in
state, theme still set after kill -9 + relaunch, find-next, list,
new tab, save-as writes the file); new test/narcis/app_test.clj
**3 tests / 34 assertions** (theme persistence incl. :auto resolution,
new/save-as, find wrap both directions); view 4/15, ops 2/26,
corpus 38/644, buffer gate — all green. web/app.js rebuilt
(1,724,905 bytes).

## Page interaction fixes (2026-11-17, coordinator)

John's Mac test of the app surface found the page unusable in three
ways the HTTP gates could not see (they never drove the page itself).
All three reproduced and fixed against the real page in headless
Chromium (CDP probe: real mouse events, real key events, op traffic
and DOM read back):

- **Clicking killed editing.** on-mousedown focused the hidden input
  but never preventDefault'd, so the browser's own mousedown default
  moved focus to the clicked (non-focusable) element after the
  handler ran — the caret moved, then typing went nowhere and the
  caret hid. Fix: preventDefault in on-mousedown (and on drag
  mousemove). Verified: focus stays on the input, caret visible.
- **Fast typing inserted in reverse.** Ops were fired concurrently,
  each carrying the caret read at send time; three quick keystrokes
  all carried the same caret and landed in reverse order ("zyx").
  Fix: op! is serialized through a promise chain and reads the
  caret/sel when the op actually runs. Verified: xyz types as "xyz",
  caret advancing 29→30→31.
- **Menus rendered transparent over the text.** The dropdown panel
  never received its .menu-panel class, so the themed background
  rule never applied. Verified: computed background is the theme's.
- Also: apply-theme! replaced #app's whole cssText with just colors,
  wiping the flex layout (page fell back to one tall block, scroll
  area unbounded) — now sets background/color as properties; and
  op failures were invisible (after-op! dropped them, and the
  status atom's "" init shadowed the status line) — failures now
  land in the status bar, init is nil.

web/app.js rebuilt (1,725,867 bytes).

## Paredit set (2026-11-17, coordinator)

Jonquil's structural editing is in, whole: `narcis.structural` is
jonquil.structural ported namespace-for-namespace (slurp/barf ×4,
raise, splice, wrap ×5, rewrap ×4, copy/kill-form, kill-list,
kill-right/left, splice-killing ×2, convolute, drag ×2, transpose,
join, split, forward/backward/up/down by form, forward-up,
backward-down, list-start/end, expand/shrink/select by form — 42
ops), over `narcis.forms`, which is jonquil.commands' form-tree
extracted (jonquil.commands itself stays behind; it carries the
console/Run machinery). Jonquil's own structural suite ports with
it: **21 tests / 154 assertions, all green**.

Ops: one dispatch arm in narcis.ops applies a structural op's
{:edits :caret :sel :copy} over the rope as ONE undo step, driven
over the same windowed text as the nav ops (whole buffer under the
exact limit, else ±400 lines). kill/copy return :copy, which the
page puts on the clipboard. Op-level gate: ops-test grows a
structural deftest (slurp/barf round-trip as two undo steps, wrap,
kill-form's :copy, expand-selection, inapplicable → not-ok) —
**3 tests / 40 assertions**.

Page: every command is in the palette and the Edit menu; chords are
jonquil's Calva keyset adapted where Narcis already had a binding
(Ctrl+Alt+arrows stay forward/backward-sexp, so slurp/barf forward
are Ctrl+Alt+. / Ctrl+Alt+,). Verified in the real page: clicking a
form and pressing Ctrl+Alt+Shift+P wraps it. web/app.js rebuilt
(1,743,677 bytes).

## Completion + doc lookup (2026-11-17, coordinator)

The last piece of jonquil's editing surface that was still missing:
`narcis.docs` is jonquil.docs ported whole (symbol-doc memoised,
resolved in the buffer's (ns ...) namespace with `user` fallback;
candidates in that namespace) over narcis.syntax's
get-autocomplete-candidates / get-symbol-doc / format-autocomplete-doc,
which were already in the S2 corpus. Jonquil's docs suite ports with
it: **11 tests / 28 assertions, all green**. Before S4 there is no
user program running, so lookups resolve against clojure.core (real
docs from the server's runtime) and buffer definitions — exactly
what the ops gate pins: :doc "map" gives clojure.core/map's doc,
:doc "hello" gives the buffer def with its arglists.

Ops: :complete (candidates for the prefix at the caret, with the
replacement range) and :doc (formatted doc for the symbol at the
caret or a named symbol); both whole-buffer under the exact limit,
like rename. Ops gate grows to **4 tests / 49 assertions**.

Page: typing a symbol char (or Ctrl+Space) opens the completion
popup at the caret — candidates, Up/Down, Enter/Tab accepts by
replacing the prefix range, Esc closes — with the highlighted
candidate's documentation beside the list; F1 (Help > Documentation
for Symbol) shows the doc in a pane. One real bug found by the page
probe along the way: op! copied :caret/:sel out of EVERY ok result,
so a read-only op's caret-less answer erased the client's caret —
the popup's doc pane was its first visible victim. op! now only
adopts a caret the result actually carries. Verified in the real
page: click after "(hel", type "l", popup offers hello with its
doc, Enter completes to (hello. web/app.js rebuilt.

## S4: Run/Stop + console (2026-11-17, coordinator)

The DrRacket loop. `narcis.eval` is jonquil.eval ported whole —
eval-string under jolt's run-interruptible, captured *out*/*err*,
the stdin queue, *1/*2/*3/*e carried across evaluations, and Stop
via jolt.host/interrupt! + thread interrupt under the context lock.
Jonquil's eval suite ports with it: **34 tests / 160 assertions,
all green**. `narcis.console` (jonquil's pure console decisions)
ports too: **9 tests / 44 assertions**.

`narcis.session` is new and deliberately thin: jonquil.session's
reset semantics (a Run removes the namespaces its session's
evaluations created, plus `user`, then evaluates into a fresh
`user`; libraries are never removed) and its prompt rule (the REPL
continues in the namespace the last evaluation left), without
jonquil's debugger/trace/world runtime stack — that port is the
debugger step, still to come. Sessions live server-side, not in
the Eve atom: a running evaluation and its half-printed output are
process state, not document state.

Ops: :run (the buffer's whole text, with the file as source path),
:stop, :repl, :stdin (for a program blocked on read-line), :console
(entries since an index + status + prompt namespace). The ops gate
drives the whole loop headlessly, including a runaway (loop)
interrupted by :stop and a second Run resetting the first run's
definitions away: **5 tests / 59 assertions**.

Page: a Run menu (Run F5, Stop Shift+F5), a View > Console toggle,
and the Interactions pane — output streamed by polling :console,
the prompt showing the session namespace, REPL input with history
(Up/Down), and stdin submission when the program is waiting for
input. Verified in the real page end to end: F5 prints the run's
output, (hello "Page") at the prompt answers "Hi Page", a runaway
(loop [] (recur)) shows Running… and Shift+F5 lands "Evaluation
interrupted". web/app.js rebuilt (1,774,018 bytes).

## The eve.text rope, reads in place, Jonquil's window (2026-11-17, session 018km5Ww)

Asked: finish the app in this lane, with the fast rope in it, looking like
Jonquil. While this was being done the lane also received the three
sections above (paredit, completion, S4) from the coordinator's seat, built
on the old rope; the two lines of work were merged, and where both had
built the same thing one was kept — said below.

### What the lane's page and server cost before

Measured on this lane at `0a1f758`, Jolt v0.8.23, Eve @84bfc7c8, one 4-core
box, a 4,000-line file over HTTP (medians): a 100-line window 981 ms; a
keystroke 1.8 s and growing with every edit; a keystroke with its repaint
7.8 s; Enter 9.9 s; `/api/state` 3.1 s. Its gates were green (editor 27/27,
corpus 38/644) — they checked answers, not time. Four causes, each
measured:

1. **A deref copies.** At this Eve pin `@atom` hands back the value as a
   flat object that owns its bytes — for a buffer, the whole document.
   Every question asked of a buffer (its caret, its undo depth, its window)
   paid for a copy of it: 100 ms at 4,000 lines, 200 ms at 12,000. Inside a
   read region (`eve.alpha/$`) the value is read where it is: 0.2 ms.
   `narcis.buffer/read` is now the one door.
2. **The undo stack was read whole** (an Eve array turned into a vector)
   for every one of those questions, and copied on every edit. It is a
   linked history now: a step makes or drops one cell.
3. **The painter filtered the whole plan once per line** of the window
   (spans × lines), and the whole text was materialized and tokenized for
   every window fetch and again for every edit op. Now: one analysis per
   text, remembered under the buffer's text key and shared by the painter
   and the ops; a window's spans found by binary search; buffers over
   24,000 chars analysed a window at a time from the top-level form above.
4. **The rope re-encoded a leaf's whole text** on every path copy
   (REPORT-ROPE-DEFTYPE.md's 0.85 ms/KB). `eve.text` writes only the lines
   an edit made; the rest of a touched leaf moves as slot references.

And one of Eve's: with the journal (Eve's default for a disk domain) 600
keystrokes took 72.9 s — 73 of them over 50 ms, the worst 1.6 s; with
`:journal? false` (durable commits by sweep + root force) 5.5 s, none over
40 ms; `:durable? false` the same 5.5 s. The store opens with
`:journal? false`. The journal's cost on Jolt is the release seat's, with
these runs.

### What is there now

- `eve.text` (johnmn3/atom.broker `libs/eve-text` @`03f5277a9`) is the
  buffer's text, carried unchanged (UPSTREAM.md has the hashes): made to
  load on this Eve line, with a builder (a file read in chunks, never held
  whole — `narcis.text/ingest-file`), a window read (`text-window`) and
  node identity. At this pin its own suite: Jolt 44 tests / 647 assertions,
  JVM the same, 0 failures. `narcis.rope` is deleted.
- `narcis.buffer`: `BufferState` at the atom's root (document, caret,
  selection, undo and redo histories, the file's line ending, revisions);
  `edit!` is one swap with the op inside it; `read` is the read region.
- The window is Jonquil's (docs/images): header bar, Definitions over
  Interactions, status bar, file tree, tabs; its menus are Jonquil's
  `menus.edn` narrowed by `narcis.menus`.
- Run, Run Form, Run Selection, Stop, Reset on Run, the prompt, history,
  stdin: `narcis.interactions` over `narcis.eval`. The transcript is in an
  Eve atom, read by position, there after a restart.
- Structural editing (42 ops), the formatter, completion and docs.

### How the two lines of work were joined

Both seats lifted `jonquil.eval`, `jonquil.console` and
`jonquil.structural` from the same commit; the copies differed by a word
or a namespace name, and one of each is kept. Kept from the coordinator's
seat: `narcis.docs` (jonquil.docs: lookups resolved in the buffer's
namespace, memoised) and its tests, now what `:complete` and `:doc` call;
the structural chords (Ctrl+Alt+. slurp, Ctrl+Alt+Shift+P wrap, …);
Reset on Run's rule (a Run removes the namespaces the console's
evaluations made, and `user`); Shift+F5 and F1; the `-main` of the ported
test namespaces. Not kept, and why:

- `narcis.session`: its console was a vector in the server's memory; the
  transcript is state and lives in an Eve atom (`narcis.interactions`).
  Its Run cleared the console and showed no value; Jonquil's Run keeps the
  transcript, prints `; --- Running <name> ---` and the last form's value.
  Its reset rule is in `narcis.interactions`.
- `narcis.forms` (jonquil.commands' form-tree, extracted):
  `narcis.commands` is jonquil.commands lifted whole, form-tree with it,
  and brings Run Form, Run Selection, the bracket check before Run and the
  formatter.
- Their arms of `narcis.ops` and their page: written against `narcis.rope`
  and the old buffer. The ops they added exist here under the same names
  (`:complete`, `:doc`; a structural command by its own name or as
  `{:op :structural :name n}`); Run, Stop and the prompt are the console's
  messages (`/api/console`), not ops. Their three ops tests are covered by
  `narcis.features-test`.

### Gates and measurements

See STATUS.md for the table as last run (it is the ledger). Two things the
numbers do not say: the page gate is new — the earlier gates never drove
the page, which is how the menu drawn over the text and a caret sync that
misplaced fast typing got through — and the viewport gate's 1,000,000-line
open is no longer walled on the rope (the line ingest is Eve's per-line
string write: see STATUS.md's row for what it took here).
