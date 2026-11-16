# Narcis

A DrRacket-style Clojure IDE ported from [jonquil](https://github.com/b12n-oss/jonquil)
to idiomatic Eve: no host-specific app code, runnable on every Eve host,
rendered as cljs into an auto-opening HTML viewer, with editor and buffer
state living in Eve disk atoms.

The buffer is a new Eve rope (`narcis.rope`): a fanout-32 B-tree of lines,
dual-indexed by character offset and line number, whose roots live in a
disk atom. Every edit is an O(log n) path-copy to a new persistent root —
buffer versions are durable, and a billion-line file opens by chunk-ingest
rather than by loading.

Status: S0+S1 spike (jolt first). See STATUS.md and REPORT.md.
