# Status — lane/narcis-s2

S2 in progress: buffer + edit on the disk atom. Branch: lane/narcis-s2
off lane/narcis-s0s1 @93e3841a. No merges.

## Milestones
- [x] Branch created; Eve re-pinned to release/eve-0.9.3.2 @84bfc7c8
      (W1b collated; flat handles fixed in Eve shared code, "plain"
      deref machinery removed). Store seam: jolt defaults to the real
      Eve disk atom (impl :eve) via Eve's jolt seat classpath
      (scripts/jolt-run.sh); the EDN file arm remains only for bb (W2),
      stated in narcis.store's docstring — the one seam place.
- [ ] S0+S1 gates re-run at the new pin on jolt
- [ ] narcis.buffer (editor state in the disk atom, restart proof)
- [ ] narcis.syntax + narcis.edit (jonquil @0ac90991 lift) + adapted corpus
- [ ] Undo/redo across restart gate
