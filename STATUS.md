# Status — lane/narcis-s0s1

S0 + S1 spike COMPLETE (gates below; full provenance in REPORT.md).
Branch: lane/narcis-s0s1. No merges.

## Gate verdicts
- S0 scaffold: pushed (write access proven). deps.edn pins Eve
  release/eve-0.9.3.2 @ e7363e7d + cohost @ 99dc255d.
- S0 launcher (page served + auto-open attempted + store reopened by
  name in a 2nd process): PASS on bb and jolt (file store arm — Eve
  does not load on jolt, see walls) and on JVM (real Eve disk atom).
- S1 rope property tests vs plain-string model (fixed cases + 800
  randomized ops, leaf-size 48): PASS on bb, jolt, JVM.
- S1 scale: 1e6-line build 2.0 s JVM / 6.1 s jolt; 2000 ops at depth
  1.18 s JVM (cold) / 0.20 s jolt; 1e8-line ingest 65.8 s JVM
  (1.39 GB text, tree height 4), ops + line-at verified at depth.
- S1 persistence: rope roots stored as versions in the Eve disk atom
  on JVM — reopened by name in a second process, current root and
  version-0 root both read their texts back exactly. Old roots keep
  reading old text (property-tested on all three hosts).

## Named walls
- Eve does not load on jolt v0.8.23 (both Eve main @ c7dc390a and
  release @ e7363e7d): no :jolt/provides for
  java.lang.invoke.MethodHandles. Narcis's store seam falls back to a
  file arm there; rope + launcher run host-clean on jolt.
- Eve on bb (release @ e7363e7d, bb 1.12.218): storing vector-bearing
  values hits SCI's block on java.nio.ByteBufferAsIntBufferL.put
  (eve/vec.cljc:101). Rope roots contain vectors, so the Eve store arm
  is JVM-only today; bb uses the file arm.
- Rope nodes are Eve *values*, not eve/deftype slab objects — named
  divergence, rationale in REPORT.md / rope.cljc docstring.
