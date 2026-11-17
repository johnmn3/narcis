# Narcis rope re-home onto eve/deftype — REPORT

Lane: lane/narcis-rope-deftype, johnmn3/narcis, off lane/narcis-s2
@54c856e. Eve pin: release/eve-0.9.3.2 @84bfc7c8. Date: 2026-11-16.
Coordinator (Toby) took the lane over mid-flight at John's order and
ran the gates + perf investigation firsthand; the census and code
below are the lane's, the verdicts are re-derived.

John's law for this work (2026-11-16 ~20:02 ET): "an eve based editor
will involve swapping on eve atoms with eve deftyped data."

## What the rope is now

- `narcis.rope`: nodes are `RopeNode` eve.deftype values in Eve slab
  storage — leaf `{:h 0 :text <string> :chars :lines}`, internal
  `{:h :kids <EveArray of RopeNode> :chars :lines}`. Public API
  unchanged from S1/S2. Construction follows Eve's measured law: a
  node is built in the current domain — inside a swap! that is the
  atom's own domain; outside, the root's own domain; standalone, a
  heap domain. Cross-domain roots enter an atom only by `rebuild`
  inside its swap (Eve refuses them otherwise, and says so itself).
- `narcis.buffer`: one named Eve atom per buffer in the store's disk
  domain, holding a `BufferState` eve.deftype AT THE ROOT (root rope
  + cursor + sel + undo/redo EveArrays of roots); a second named atom
  holds the editor's plain data (tabs/active). Every mutation is ONE
  swap! on the relevant atom and the rope op runs INSIDE the swap fn,
  so new nodes are constructed in the atom's storage. No mirror, no
  persist step — the swap is the persistence; undo is a root swap and
  works across a process restart. BufferState sits at the root
  because a deftype with a by-reference field cannot be nested inside
  a collection value in Eve @84bfc7c8 (freeze packs its plain field
  view and refuses the reference — probes B/C2; Eve's own Dataset is
  the at-root precedent). The file arm can no longer carry the
  editor (EDN cannot hold deftype values); open-editor says so —
  the file arm remains for the launcher's text buffers on hosts
  where Eve cannot run (bb, W2).
- `narcis.store`: reduced to open-by-name over a disk domain of named
  Eve atoms (+ the bb file arm). Store-level versioned buffers use a
  `Versioned` deftype at the atom root the same way.

## Gates (coordinator-run, jolt v0.8.23, Eve @84bfc7c8)

| Gate | Verdict |
|---|---|
| Rope property tests (800 randomized ops vs string model) | PASS |
| Buffer gate (walk/write/read; undo walk + undo-to-v0 across restart) | PASS |
| Store gate (reopen-by-name; current + version-0 texts) | PASS |
| Jonquil corpus + S2 suite (run-s2-tests) | PASS — 38 tests / 644 assertions / 0F / 0E |
| S0 gate (page/buffer served, reopen, impl :eve both processes) | PASS |

## The perf wall (named, measured, Eve-side)

Correctness is green everywhere; speed is not, and the regression is
Eve's storage paths, not this design. Numbers at the pin (jolt;
S2 plain-value numbers in parentheses):

- scale1m bench: build 1e6 lines 46,027 ms (991); 2,000 ops at 1e6
  56,386 ms (333); window-50 1,498 ms (1.8). JVM same bench: build
  93,078 ms, ops 109,603 ms, window 12.6 ms — host-independent.
- Micro-probes (lane-local probe/, narcis.probe2–5): warm reads are
  fast (slice-string 0.015 ms, line-at on a leaf 0.006 ms, to-string
  of a 200-line tree 0.14 ms). Construction of a deftype leaf costs
  ~0.85 ms per KB of text warm (empty leaf 0.12 ms, 30 chars 0.30 ms)
  — a per-byte term of roughly 0.5 µs/B in the serialized-field
  storage path, although eve.codec's string encode itself is bulk
  (utf8-bytes + arraycopy). Per-keystroke edits stay ~1–3 ms.
- A fresh Eve atom storing KB-scale plain string values is far
  worse: 65–99 ms per swap! over 300 swaps (probe4), while a
  pre-warmed atom stores 700-char strings at ~1.8 ms/op (probe5) —
  a cold/growth transient in Eve's out-of-line value storage that
  Narcis's design avoids (buffer values are deftypes at atom roots,
  swapped by reference) but bulk ingest still pays the field-encode
  term on every leaf.

Disposition: the fix belongs in Eve's shared storage code (the
serialized-field write path and the out-of-line value path), not in
Narcis — no Narcis-side workaround is taken. A measured package with
these probes goes to the eve-release seat. Until Eve's paths improve,
file-open at editor scale (≤10k lines ≈ 0.5 s) is fine; the 1e8-line
ingest number regresses against S1's plain-value 65.8 s and is NOT
claimed here.

## Named walls carried

- W2 (unchanged): bb cannot run the Eve store arm (SCI ByteBuffer
  block); the editor requires the Eve arm and says so when opened on
  a file store.
- Nesting gap (above): deftypes with by-reference fields must sit at
  an atom's root. Per-buffer atoms are the shape that follows.
