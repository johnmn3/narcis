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
- [x] S0+S1 gates re-run at the new pin on jolt — ALL GREEN:
      rope property tests PASS; store gate jolt STORE impl: :eve,
      reopen-by-name + version-0 verified; S0 launcher on jolt reports
      store-impl: :eve (was :file in S1); 1e6-line sanity: build 991 ms,
      2000 ops 333 ms, window-50 1.8 ms (logs in run-s2/)
- [x] narcis.buffer: editor state as ONE store value {buffers
      {path {:root :cursor :sel :undo :redo}}, tabs, active}; undo =
      root swap, history durable in the atom. Gate (scripts/buffer-gate.sh
      jolt, STORE impl: :eve): in-process N-edit undo/redo walk with
      text equality at every step; write/read across two processes —
      state identical after reopen (tabs, active, texts, cursor,
      selection, undo/redo depths) and undo walks back to version-0
      across the restart. PASS
- [ ] narcis.syntax + narcis.edit (jonquil @0ac90991 lift) + adapted corpus
- [ ] Undo/redo across restart gate
