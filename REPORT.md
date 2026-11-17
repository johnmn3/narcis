# Narcis S2 — REPORT (lane/narcis-s2)

S2 COMPLETE. Buffer + edit on the disk atom: editor state lives in the
real Eve disk atom on jolt, jonquil's syntax/edit surface runs over
rope slices, undo/redo walk persistent roots across process restarts.
No merges. Branch: lane/narcis-s2 (off lane/narcis-s0s1 @93e3841a).
The S0+S1 report is preserved beside this file as REPORT-S0S1.md.

## Provenance
- Narcis base: lane/narcis-s0s1 @93e3841a (S0+S1, see REPORT-S0S1.md).
- Eve pin: johnmn3/atom.broker release/eve-0.9.3.2 @84bfc7c817075498fbab3c45811ced5e8cf3f0f7
  (W1b collated; verified by git fetch + rev-parse in the lane's Eve worktree).
- Jonquil lift source: b12n-oss/jonquil @0ac909914bd5e355967c1acab7022014d1e97711 (v0.4.4).
- Tools (PATH=/home/hatch/.local/bin): jolt v0.8.23, bb v1.3.190,
  Clojure CLI (JVM cross-check leg), Java 21.
- Jolt runs use Eve's own jolt seat classpath (`bb classpath jolt` in
  the Eve tree — scripts/jolt-run.sh, the W1b-proved shape), never a
  deps.edn local/root of the whole Eve tree. TMPDIR redirected to lane
  scratch throughout (box /tmp is a 512 MB tmpfs). Logs in run-s2/.

## What landed
1. **Re-pin + store default.** deps.edn Eve sha → 84bfc7c8. On jolt the
   store seam (narcis.store) now defaults to the REAL Eve disk atom:
   the S0 launcher and every gate on jolt report `STORE impl: :eve`.
   The EDN file arm remains only for bb (W2 below), and narcis.store's
   docstring is the one place that says so.
2. **narcis.buffer.** Editor state as ONE store value under the name
   "editor": {:buffers {path {:root :cursor :sel :undo :redo}},
   :tabs, :active}. Every mutation persists through the store. Undo is
   a root swap — the undo/redo stacks are vectors of prior rope roots,
   durable in the atom like everything else.
3. **narcis.syntax + narcis.edit.** Lifted from jonquil @0ac90991
   (EPL-2.0; LICENSE, NOTICE and UPSTREAM.md carried into this repo,
   with a Narcis lift table in UPSTREAM.md). Syntax delta, the only
   one: jonquil.syntax's dependency on jonquil.dialect is inlined as
   data — the Jolt profile's :special-forms and :builtins sets, the
   only two values it read. Edit ops are verbatim; appended is Narcis's
   rope layer: snapshots are built from rope slices (scope :all, or
   :form = the enclosing top-level form via syntax's bracket matches),
   and an op's :edits are applied to the rope in one pass as ONE new
   root (one undo step in narcis.buffer).

## Gate verdicts (all on jolt unless noted)
- **S0+S1 gates at the new pin — PASS.** Rope property tests (fixed +
  800 randomized ops vs string model): PASS. Store gate: impl :eve,
  reopen-by-name with current + version-0 texts verified across two
  processes: PASS. S0 gate (page served, buffer served, reopen in a
  second process): PASS, store-impl :eve in both processes (S1: :file).
  1e6-line sanity: build 991 ms, 2,000 ops at depth 333 ms, window-50
  1.8 ms.
- **Jonquil corpus, adapted — PASS on jolt: 38 tests, 644 assertions,
  0 failures, 0 errors.** Upstream-vs-ported counts, counted with grep
  on both trees: jonquil has 34 deftests for syntax+edit
  (syntax_test 6, upstream/syntax_test 13, edit_test 15; `is` forms
  9/216/103); the ported files carry identical counts — **zero tests
  omitted or adapted beyond the namespace rename**. The remaining 4
  deftests are Narcis's own (narcis.edit-rope-test: op battery vs the
  text oracle, 60 random edit sets through apply-edits-to-rope, form
  scoping, buffer integration).
- **Undo/redo walks roots — PASS** (scripts/buffer-gate.sh, impl :eve):
  in-process, N edits undo step-by-step with text equality at every
  step and redo back to the tip; across a process restart (write →
  read in a new process) the editor state is identical — tabs, active
  buffer, both buffers' texts, cursor, selection, undo/redo depths —
  and undo continues across the restart back to version-0, redo
  re-applies.
- **Editor-state restart proof — PASS** (same gate's read phase).

## Named walls / notes
- **W2 (pre-existing, Eve-side, unchanged):** bb still cannot run the
  Eve store arm (SCI blocks `java.nio.ByteBufferAsIntBufferL.put` in
  eve/vec.cljc for vector-bearing values), so bb keeps the EDN file
  arm. Not touched by this lane; no Narcis-side workaround added.
- **JVM corpus cross-check — 14 failures, all emoji offsets (named,
  not a defect of this lane):** the same corpus on the JVM passes 630
  assertions; the 14 failures are exactly the cases with an emoji
  before the caret. Measured discriminator: jolt counts string offsets
  in code points ((count "😀") = 1 on jolt v0.8.23), JVM Clojure in
  UTF-16 code units (2), so caret arithmetic in the lifted jonquil ops
  differs by one per emoji on the JVM. Jonquil's own suite only ever
  runs on jolt; the S2 gate (jolt) is green. Recorded here so nobody
  re-derives it; fixing it would mean diverging from upstream over a
  host string-semantics question, which is not Narcis's call to make
  silently.
- **Snapshot materialization (spike limitation, named):** the rope
  layer's :form scope currently finds the enclosing form over the
  rope's materialized text, and :all scope (rename) materializes the
  whole rope. Correct at editor scale; the windowed variant (bracket
  index over rope leaves, viewport slices only) belongs with S3's
  rendering work, where the viewport discipline is built anyway.
- No new Eve walls were hit: at @84bfc7c8 the disk atom, flat handles
  and deref behaved on jolt exactly as on the JVM in every gate above.

## Files
- src/narcis/buffer.cljc (new), src/narcis/syntax.clj (lift),
  src/narcis/edit.clj (lift + rope layer), src/narcis/store.cljc
  (docstring/default note), deps.edn (Eve pin), scripts/jolt-run.sh
  (new), scripts/buffer-gate.sh (new), scripts/store-gate.sh (+jolt),
  scripts/s0-gate.sh (jolt arm on the seat classpath), scripts/hosts.sh
  (EVE_RELEASE → the pinned worktree), test/narcis/{syntax_test,
  upstream_syntax_test, edit_test, edit_rope_test, buffer_test,
  run_s2_tests}.clj, LICENSE, NOTICE, UPSTREAM.md, STATUS.md,
  REPORT-S0S1.md (preserved).
