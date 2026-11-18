#!/usr/bin/env bash
# Big-file e2e: generate a lorem-ipsum file of hundreds of megabytes,
# load it in the real editor, exercise interactions over it in
# headless Chromium (find to the end, typing, undo/redo, a mid-file
# edit, page-downs), then close the server, relaunch it on the same
# store, and measure how long bring-up takes and whether the edits
# and the undo history survived. Timings print as PERF lines.
#
#   BIGFILE_MB=200 bash scripts/bigfile-e2e.sh
#
# The run's data (project, store, logs, screenshots) is kept under
# $BIGFILE_DATA or ~/workspace/rope-deftype-lane/bigfile-runs/ —
# never /tmp: the file and its store are gigabytes between them.
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT"
source "$ROOT/scripts/hosts.sh"
MB=${BIGFILE_MB:-200}
PORT=${E2E_PORT:-8398}
STAMP=$(date +%Y%m%d-%H%M%S)
DATA=${BIGFILE_DATA:-$HOME/workspace/rope-deftype-lane/bigfile-runs/$STAMP-${MB}mb}
mkdir -p "$DATA/proj" "$DATA/shots1" "$DATA/shots2"
export NODE_PATH=${NODE_PATH:-/home/hatch/workspace/veil-click/node_modules}

echo "== bigfile e2e mb=$MB data=$DATA port=$PORT"
GEN=$(python3 "$ROOT/bench/gen_bigfile.py" "$DATA/proj/big.txt" "$MB")
echo "$GEN" | sed 's/^/GEN /'
LINES=$(echo "$GEN" | sed -n 's/^LINES=//p')
MID=$(( (LINES / 2 / 50000) * 50000 ))
echo "== lines=$LINES mid-marker-line=$MID"

boot() { # $1 = log name; sets BOOT_MS and SRV in this shell
  local t0 t1
  t0=$(date +%s%N)
  JOLT_TIMEOUT=3600 setsid bash "$ROOT/scripts/jolt-run.sh" narcis.launcher \
    --dir "$DATA/db" --port $PORT --file "$DATA/proj/big.txt" --web-dir "$ROOT/web" \
    > "$DATA/$1" 2>&1 &
  SRV=$!
  local deadline=$(( $(date +%s) + 1800 ))
  until curl -fsS "http://127.0.0.1:$PORT/api/state" -o /dev/null 2>/dev/null; do
    if [ "$(date +%s)" -ge $deadline ]; then
      echo "E2E server-up FAIL"; tail -5 "$DATA/$1"; kill -9 -"$SRV" 2>/dev/null; exit 1
    fi
    sleep 1
  done
  t1=$(date +%s%N)
  BOOT_MS=$(( (t1 - t0) / 1000000 ))
}

boot server1.log
echo "PERF server-cold-up-includes-ingest ${BOOT_MS}ms"
node "$ROOT/test/page/bigfile_e2e.cjs" "http://127.0.0.1:$PORT/" "$DATA/shots1" edit "$LINES" "$MID"
RC1=$?
echo "== store size after edits: $(du -sm "$DATA/db" | cut -f1) MB (file: $(du -sm "$DATA/proj/big.txt" | cut -f1) MB)"
kill -9 -"$SRV" 2>/dev/null
sleep 2

boot server2.log
echo "PERF server-relaunch-up-after-edits ${BOOT_MS}ms"
node "$ROOT/test/page/bigfile_e2e.cjs" "http://127.0.0.1:$PORT/" "$DATA/shots2" reopen "$LINES" "$MID"
RC2=$?
kill -9 -"$SRV" 2>/dev/null

echo "== bigfile e2e done rc1=$RC1 rc2=$RC2 data=$DATA"
[ "$RC1" = 0 ] && [ "$RC2" = 0 ]
