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

Same dedicated-clone shape as S2: clone johnmn3/narcis, switch to
lane/narcis-s3-joined, then
`jolt run -m narcis.launcher --dir <data> --port 8377 --file <path>`
— the page that opens is the actual editor now (caret, selection,
structural editing), its state in the Eve disk atom.
