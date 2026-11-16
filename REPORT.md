# Narcis S0+S1 spike — REPORT

Lane: lane/narcis-s0s1, johnmn3/narcis. Date: 2026-11-16.
Plan of record: SPIKE-PLAN.md (S0 scaffold, S1 rope core) in the
coordinator's goal workspace. No merges; landing is the coordinator's.

## Verdicts

| Gate | Verdict |
|---|---|
| S0 scaffold commit pushed (write-access proof) | PASS — branch created on origin with the scaffold commit |
| S0 page served + auto-open attempted | PASS on bb, jolt, JVM |
| S0 disk atom created + re-opened by name, 2nd process | PASS — real Eve disk atom on JVM; file arm on bb/jolt (Eve walls below) |
| S1 property tests vs string model, headless | PASS on bb, jolt, JVM |
| S1 scale probe 1e6 lines + ops at depth | PASS on JVM and jolt |
| S1 synthetic ≥1e8-line ingest + ops at depth | PASS on JVM (1.0e8 lines) |
| S1 reopen-from-disk equality | PASS on JVM (Eve atom, 2 processes) |
| S1 versions: old root reads old text | PASS (property tests all hosts + Eve store versions on JVM) |

## Numbers

Rope property suite (fixed cases + 800 randomized insert/delete/
split-concat ops against a plain-string model, *leaf-size* bound to 48
to stress splitting; text, char count, line count, line-at,
line-start-offset and offset↔line-col compared after every op):
PASS on all three hosts, exit 0.

Scale (bench/narcis/bench.clj; synthetic lines "line-<i> lorem ipsum
dolor sit amet" for 1e6, "line-<i>" for 1e8):

| measurement | JVM | jolt |
|---|---|---|
| build 1e6 lines | 1,987 ms | 6,095 ms |
| 2,000 random ops at 1e6 (height 4) | 1,180 ms (0.59/op, cold JVM) | 201 ms (0.10/op) |
| lines-window 50 at 1e6 | 3.9 ms | 1.8 ms |
| ingest 1e8 lines (1,388,888,890 chars, height 4, leaf 8192) | 65,832 ms | not run |
| 200 random ops at 1e8 | 104 ms (0.52/op) | not run |
| lines-window 50 at 1e8 | 4.4 ms | not run |

Op cost is flat between 1e6 and 1e8 (height 4 in both) — depth-bound,
not size-bound, as designed. line-at asserted exact at lines 50,000,000
and 99,999,999 of the 1e8 rope.

Persistence (scripts/store-gate.sh, two processes): process 1 stores
rope root v1, edits to v2, stores v2 (impl :eve). Process 2 reopens
{:id :narcis/buffers :persistent <dir>} by name: current root reads
"alpha\nINSERTED\nbeta\ngamma\n", version-0 root reads
"alpha\nbeta\ngamma\n" — reopen-from-disk equality and version
retention both hold in the real Eve disk atom on JVM.

S0 (scripts/s0-gate.sh, two processes per host): page HTML fetched
over HTTP and matched, /buffer served the stored text, second process
reopened by name with buffer-text-ok: true. Auto-open attempted on
every run (xdg-open on this Linux box; no browser present, attempt is
what the gate requires). Store impl per host: JVM :eve, bb :file,
jolt :file.

## Defects found by measurement (provenance: this lane, this pin)

1. Rope line index, v1: per-leaf segment counts (newlines+1) summed at
   internal nodes double-counted one line per leaf boundary
   (expected 201 lines, got 250 on 200×"abcdefghij\n", leaf 48), and
   line-at was leaf-local so lines spanning leaves read wrong
   (line 4 read "" instead of "abcdefghij"). Fixed by storing newline
   counts in nodes and reading lines by offsets
   (line-start-offset via nth-newline descent; line-at via slice).
   Re-derived and green on bb/jolt/JVM after the fix.
2. Lane's own store_eve.cljc: an edit orphaned a let binding
   (reader error) and put! shadowed clojure.core/atom. Both caught by
   the store gate falling back to :file with the error printed; fixed,
   gate re-run green at :eve.
3. Lane's own s0-gate.sh: curl poll gave up after 20 s, before a JVM
   process finished compiling Eve — reported PAGE FAIL while the
   launcher was healthy (REOPEN OK in the same run). Fixed to poll
   for the process's whole life; re-run green.

## Named walls (provenance: fresh repro, this box, 2026-11-16)

W1. Eve does not load on jolt v0.8.23. Public Eve main @ c7dc390a35:
   requiring eve.atom dies in eve.mem — "No dependency provides
   java.lang.invoke.MethodHandles … :jolt/provides (RFC 0014)".
   Release Eve @ e7363e7db2 (release/eve-0.9.3.2): same wall in
   adapters/region/jvm-varhandle eve.region. So on the spike's target
   host the Eve disk atom cannot engage yet; narcis.store's seam
   falls back to an EDN file arm with identical value shape, and the
   failure is printed (eve-error) rather than hidden. The rope and
   launcher themselves run host-clean on jolt (tests + bench green).
W2. Eve on bb: public main @ c7dc390a35 fails its own example
   (`bb run atom-swap`, bb 1.3.190): SCI analysis error,
   ser/register-header-tag-reader! unresolved at
   eve.deftype-proto.alloc:607. Release Eve requires bb ≥ 1.12.214
   (its host-completion says so); with bb 1.12.218 (installed
   lane-local) the release Eve atom opens and reports impl :eve, but
   storing a vector-bearing value dies: "Method put on class
   java.nio.ByteBufferAsIntBufferL not allowed!" at eve/vec.cljc:101
   (SCI class block). Rope roots contain vectors (:node children,
   :versions), so the Eve store arm is JVM-only today; bb uses the
   file arm. Repro: scripts/store-gate.sh bb-eve.
W3. eve/deftype divergence (named in rope.cljc's docstring): the brief
   asked for the rope via eve/deftype. In both checked Eve trees
   eve.deftype is slab-offset machinery with :clj/:cljs arms, and per
   W1 Eve does not load on jolt at all — a slab-offset rope would run
   nowhere on the target host. The rope is therefore built from plain
   Eve *values* (maps/vectors/strings/longs — exactly what the disk
   atom stores), which keeps every op identical across hosts and
   makes the root directly atom-storable. Re-homing node storage
   onto eve/deftype is a storage swap behind the same API when
   Eve-on-jolt lands.

## Divergences / scope notes

- deps.edn declares Eve + cohost as git deps at the pins above; the
  gate scripts run against local checkouts of those exact trees
  (proxy git resolution of atom.broker is slow) — pins are the record.
- Ingest is streaming chunk ingest (reader → leaves → balanced
  subtrees, O(batch) memory); it is not mmap. The 1e8 gate input is
  generated lines, timed end-to-end including generation.
- The served page's JS is a hand-mirrored twin of web/narcis/web.cljs
  until the S3 cljs build; S0's gate is serve + auto-open, not cljs
  compilation.
- narcis.rope's ingest-reader/ingest-file use java.io (clj/bb/jolt);
  the pure rope core is cljs-clean, the IO door is the cljs seam for S3.
- Host seam inventory: exactly one seam namespace (narcis.store) with
  two arms (Eve disk atom / file). No other host-specific code exists
  in src/ — grep for reader conditionals: store.cljc, store_eve.cljc
  only.

## Provenance

- Trees: narcis @ this branch; Eve release clone
  ~/workspace/git/atom.broker-eve @ e7363e7db2c107a0e632d3330cd3c812b7b0a9ee
  (release/eve-0.9.3.2); Eve public ~/src/eve @ c7dc390a35 (main);
  cohost ~/src/cohost @ 99dc255d22997009f4b5d3d2a93469412e3010d8.
- Tools (PATH=/home/hatch/.local/bin): jolt v0.8.23, babashka
  v1.3.190 (system) + v1.12.218 (lane-local, for Eve's bb seat),
  Clojure CLI 1.12.6.1673, Clojure 1.12.0, CLJ_CONFIG=
  ~/workspace/eve-env/cljconfig (seeded /home/hatch/m2repo — direct
  Maven Central is blocked on this box).
- Commands: scripts/s0-gate.sh {bb,jolt,jvm}, scripts/store-gate.sh
  {jvm,bb-eve}, `bb|jolt|clojure -m narcis.run-tests`,
  `clojure -M:bench -m narcis.bench {scale1m,ingest100m}`,
  `jolt run -m narcis.bench scale1m`. Logs under run/ (gitignored).
