#!/usr/bin/env bash
# Store gate: two processes — write rope versions, then reopen BY NAME
# and verify current + version-0 texts. Usage: store-gate.sh jvm|bb-eve
set -u
HOST=${1:-jvm}
ROOT=$(cd "$(dirname "$0")/.." && pwd)
source "$ROOT/scripts/hosts.sh"
DATA=$(mktemp -d /tmp/narcis-store-gate.XXXXXX)

run_phase() {
  case "$HOST" in
    jvm)    timeout 300 bash -c "run_jvm -M:test -m narcis.store-test $1 $DATA/db" ;;
    bb-eve) PATH="$BIN:$PATH" timeout 300 "$BB_NEW" -cp "$(eve_bb_classpath):$ROOT/src:$ROOT/test" -m narcis.store-test "$1" "$DATA/db" ;;
  esac
}

echo "== store gate host=$HOST data=$DATA"
run_phase write
run_phase read
ls "$DATA/db" 2>/dev/null | head
echo "== data dir kept at $DATA"
