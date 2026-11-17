#!/usr/bin/env bash
# Buffer gate (adapted from scripts/store-gate.sh): three phases —
#   walk  (one process: N edits, undo/redo walk with text equality)
#   write (process 1: build editor state, persist)
#   read  (process 2: reopen by name, state identical, undo/redo walk
#          continues across the restart)
# Usage: buffer-gate.sh jolt|jvm   (jolt must report STORE impl: :eve)
set -u
HOST=${1:-jolt}
ROOT=$(cd "$(dirname "$0")/.." && pwd)
source "$ROOT/scripts/hosts.sh"
export TMPDIR=${TMPDIR:-$HOME/workspace/narcis-s2-lane/tmp}
mkdir -p "$TMPDIR"
DATA=$(mktemp -d "$TMPDIR/narcis-buffer-gate.XXXXXX")

run_phase() {
  case "$HOST" in
    jvm)  timeout 300 bash -c "run_jvm -M:test -m narcis.buffer-test $1 $DATA/db" ;;
    jolt) JOLT_TIMEOUT=300 bash "$ROOT/scripts/jolt-run.sh" narcis.buffer-test "$1" "$DATA/db" ;;
  esac
}

echo "== buffer gate host=$HOST data=$DATA"
run_phase walk
run_phase write
run_phase read
echo "== data dir kept at $DATA"
