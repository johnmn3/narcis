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
