# Status — lane/narcis-s3-joined

Narcis: Jonquil on Eve. The window is Jonquil's, the buffer is an `eve.text`
document in an Eve disk atom, the evaluator is Jonquil's on Jolt.
Eve pin: `release/eve-0.9.3.2` @`84bfc7c8`. Jolt v0.8.23. Jonquil pin
`0ac90991` (v0.4.4). eve.text pin: johnmn3/atom.broker `03f5277a9`.

## What is in (2026-11-17)

- **The rope**: `eve.text` (carried unchanged, `UPSTREAM.md`) read through
  `narcis.text`; `narcis.rope` is deleted. A file is read in through the
  library's builder; its line ending is kept and written back on save.
- **The buffer**: `BufferState` at the root of the buffer's atom; every edit is
  one `swap!` with the text op inside it; undo/redo are linked histories of
  roots; every read is in place (Eve's `$`).
- **The window**: header bar, Definitions over Interactions, status bar, file
  tree, tabs, Jonquil's menus (narrowed to what Narcis has), dialogs for
  Open / Save As / Open Folder, command palette, find bar, themes, zoom.
- **Editing**: Jonquil's edit ops (auto-pair, smart enter, comment, indent,
  rename), structural editing (42 ops), the formatter, find at any size.
- **Running**: Run, Run Form, Run Selection, Stop, Reset on Run, the prompt
  with history, stdin for a program that reads, the transcript in an Eve atom.
- **Help**: completion with a doc preview, docs for the symbol — looked up in
  the buffer's namespace (`narcis.docs`).

## Gates (all on Jolt, this lane's tip, one 4-core box)

| Gate | Result |
|---|---|
| `narcis.run-all-tests` (lifted Jonquil corpus + Narcis's own) | 153 tests, 1,333 assertions, 0 failures, 0 errors; the text layer's 660 seeded edits and its file door PASS |
| `scripts/buffer-gate.sh jolt` (undo/redo walk; state and history across a restart) | walk / write / read PASS |
| `scripts/store-gate.sh jolt` | write / read PASS |
| `scripts/editor-gate.sh` (HTTP session on 12,001 lines, `kill -9`, relaunch, undo across it) | 27/27 |
| `scripts/page-gate.sh` (the real page in headless Chromium) | 44/44 |
| `scripts/viewport-gate.sh` (1,000,000-line file through HTTP) | 3/3 — 1,000,001 lines opened, a 60-line window from the middle served in 15,517 bytes; the whole gate (write the 31.8 MB file, launch, open, fetch) took 60 s. The store is 181 MB for that file |

## Latency (Jolt, over HTTP, median)

| | 4,000 lines before | 4,000 lines now | 12,000 lines now |
|---|---|---|---|
| launch + open the file | 1.8 s | 1.6 s | 2.1 s |
| a window of 100 lines | 981 ms | 26 ms | 23–32 ms |
| a keystroke | 1,795 ms, growing | 8.1 ms | 8.5 ms |
| a keystroke and its repaint | 7,770 ms | 33 ms | 32 ms |
| Enter (smart enter) and its repaint | 9,942 ms | 55 ms | 53 ms |
| undo and its repaint | 9,865 ms | 31 ms | 31 ms |

"Before" is this lane at `0a1f758` on the same box; at 12,000 lines its run
was stopped (a keystroke already took 8–10 s at 4,000). `REPORT.md` says where
the time went.

## Not in

Parinfer, find-and-replace, jump to definition and Locations, the inspector,
the debugger, tracing and line counts, Run Tests, dialects other than Jolt,
out-of-process Jolt, plugins, MCP, the terminal, change sets, Save World,
preferences and editable keys, the guide pages. (`README.md`.)

## Open, named

- **Eve, release line**: the journal's per-commit force spikes on Jolt (600
  keystrokes: 73 s with it, 5.5 s without), so the store opens with
  `:journal? false`; a plain deref copies the whole value (hence `$`
  everywhere); `=` on two handles of one eve-type block answers false; on the
  JVM the slot codec's byte helpers are untagged (reflection). Each is
  measured in `REPORT.md` / the eve-text report, for the release seat.
- **Large buffers** (over 24,000 chars) are painted a window at a time from
  the top-level form above the window: a bracket opened before that form's
  line is not seen by the window's colouring.
- **Store size**: a line is a block of its own, so the store is several
  times the file (181 MB for a 31.8 MB, 1,000,000-line file).
- **Offsets** are the host's string units (code points on Jolt).
- Only Jolt runs the app: `narcis.eval` uses Jolt's interrupt token.
