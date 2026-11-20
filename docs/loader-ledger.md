# Loader ledger (fix/loader-v3)

Measurements and verdicts for the big-file loader, newest first. Each
entry carries its provenance: tree, tools, fresh-repro result. All
times America/New_York.

Seat for every entry below unless it says otherwise: x86_64 Linux, 4
cores, 15 GB RAM; Jolt v0.8.28 release binary built from source
(`make jolt-release`); Eve pinned at a14775a via `~/.jolt/gitlibs`,
`EVE_RELEASE` unset (or pointed at that same checkout for
`scripts/jolt-run.sh` probes); fresh store per run. Not the Mac.

## 2026-11-20 (afternoon): the white page while scrolling a loading file

- **Reproduced in the real page.** Headless Chromium, wheel scrolling
  in bursts with pauses and jumps, a frame every 2 s over a 2 GB load,
  at 8188ed7 on Jolt v0.8.28. Of 725 frames during the load, 13 showed
  a full editor; 407 were partly white and 305 blank. The cause is the
  window's position on the page, not slow fetches: every fetch was a
  200 within 1.7 s. A byte-fetched window ends at the viewport's
  bottom byte but was placed by its start byte, so the block hung
  below the viewport.
- **Fixed in 8681ef0.** The window is now placed by the byte it was
  fetched for (`:anchor-byte`). With the same load and the same
  scrolling: 351 of 379 settled frames full, 0 partly white, 28 blank
  (the longest blank run was 6 s, during the scan). The status now
  reads "Loading from file N%" over all built bytes, then "Whole
  document". Page gate 44/44, editor e2e 38/38.
- Mac provenance (from the user): tree 8188ed7, Eve a14775a via
  gitlibs, no `../eve`, `EVE_RELEASE` unset. Jolt was v0.8.23, and the
  user upgraded to v0.8.28 with Homebrew. Jolt's changelog lists
  concurrency fixes after 0.8.23, one of them arm64-only.

## 2026-11-20

### Issue 1: scroll mid-load

- **Mac failure: not reproduced at BASE (ba24c46).** These four runs
  all loaded exact, with no `stopped:` line in the log:
  1. a v500k-shaped file (500,000 1BRC lines, 8 MB), with 442 deep
     `/api/window` requests;
  2. a `kill -9` mid-load followed by a resume on the same store
     (546 windows);
  3. the real page in Chromium, dragging and dwelling deep (205
     fetches);
  4. a 60 MB file under the real page for a 4-minute load (1,817
     fetches).

  The loader catches carried a local `printStackTrace` patch
  (uncommitted). Open: the Mac's own tree, Eve dir and Jolt version,
  and a stack trace from it.
- **H1, measured and fixed (14f0084).** `buffer/extents` hands an
  extent's `:doc` out of its `$` region as an unpinned slab handle, and
  `launcher/beyond-payload` rendered windows from it.
  `bench/narcis/probe_escape.clj` measures it on the pinned Eve with a
  fresh domain per arm, in both orders: the escaped shape reads
  another document's lines in 30 of 30 rounds; the in-region read
  (`buffer/read-extent`) and a rooted control are clean 30/30.
- **H3, audited, not reproduced.** The scratch ids `seg-<n>`,
  `seg-tail` and `seg-tail-slice` are shared across runs and paths.
  In the test, a 1 MB file was opened while a 2 GB file's tail was
  parked and its scan was still running. The 2 GB file's end lines
  were exact both after the noting and after assembly (541 s), with no
  `stopped:`. Not fixed. Eve has 256 atom slots per domain
  (`MAX_ATOM_SLOTS`), so any fix must lease a fixed pool of ids rather
  than mint one id per run or per path.

### Issue 2: pace, the deciding experiment

`bench/narcis/probe_domscale.clj` (its docstring has the tables).

- Eve's build scales near-linearly to 3 threads at about 2.2 MB/s per
  thread (lines of about 95 characters).
- Atoms in one domain build as fast as atoms in separate domains.
- 64 MB builds run at the 4 MB rate.

**Verdict: the wall is the loader's shape, not Eve's shared domain or
swap path.**

Per-line note: the build ran at about 29k lines/s per thread. At
1BRC's ~14 bytes per line that is about 0.41 MB/s, the production
figure. On short-line files the build is bounded per line (one Eve
string block per line, `eve.text.store/make-leaf`). `eve.text`'s
source (`atom.broker/libs/eve-text`, per UPSTREAM.md) is in neither
the Eve pin nor Eve `main` (abba331).

### Issue 4: 2 GB open (`bench/gen_bigfile.py` 2048 → 22,196,162 lines)

- Run at a 10 GB heap ceiling: the load is whole at 542 s. RSS was
  5.2 GB during the scan and peaked at 10.2 GB in the build; it fell
  back to 3.9 GB at the end.
- Run at Jolt's default ceiling (25% of RAM, so about 3.9 GB here):
  the load is whole at 536 s; RSS peaked at 4.9 GB; no OOM.
- The reported OOM ceiling, 2,079,974,400 bytes, is 25% of about
  8.3 GB. The memory is not accumulating. It is the per-builder
  working set: 3 workers plus the tail, each holding a 64 MB segment
  as line maps, a joined string and a re-split vector, at 4 bytes per
  character.
- Middle seek (`byte=50%`): HTTP 200 every time, and the window ends on
  the middle line. The first seek, during the scan, took 30.5 s; later
  seeks took 0.0 s, except one 36.7 s stall exactly when the load went
  whole.

### Issue 5: find during a load

`zqx11000000` was issued 2 s after serving, mid-scan, on the 2 GB
file. HTTP 200 in 14.0 s, landing on the right line (10,999,999,
0-based). The earlier "no landing in 250 s" does not reproduce on this
tree and seat.

### Gates at 14f0084

- page gate: 44/44
- editor e2e: 38/38
- launch e2e (`scripts/bigfile-launch-e2e.sh`, fresh clone, pinned Eve,
  v500k shape, 3 workers): PASS, 25.1 s
- corpus (run against the pinned Eve, not a dev sibling): 255 tests,
  1,766 assertions, 8 failures. All 8 are in
  `debugger-on-a-jolt-without-jolt-debug`, which assumes a Jolt
  without `jolt.debug`; v0.8.28 ships it, and the unfixed tree fails
  the same 8.
