#!/usr/bin/env bash
# S0 gate: launcher in two separate processes on one data dir.
# Process 1 seeds the hello buffer and serves the page (we curl it).
# Process 2 must find the buffer by name (reopen) and report text ok.
# Usage: s0-gate.sh bb|jolt|jvm|bb-eve   (impl differs per host; see REPORT)
set -u
HOST=${1:-bb}
ROOT=$(cd "$(dirname "$0")/.." && pwd)
source "$ROOT/scripts/hosts.sh"
DATA=$(mktemp -d /tmp/narcis-s0-gate.XXXXXX)
PORT=8377

run_launcher() {
  case "$HOST" in
    bb)     PATH="$BIN:$PATH" timeout 60 bb -cp "$ROOT/src" -m narcis.launcher --dir "$DATA" --port $PORT --serve-secs "${SERVE_SECS:-12}" ;;
    bb-eve) PATH="$BIN:$PATH" timeout 120 "$BB_NEW" -cp "$(eve_bb_classpath):$ROOT/src" -m narcis.launcher --dir "$DATA" --port $PORT --serve-secs "${SERVE_SECS:-12}" ;;
    jvm)    timeout 120 bash -c "run_jvm -M -m narcis.launcher --dir $DATA --port $PORT --serve-secs ${SERVE_SECS:-12}" ;;
    jolt)   (cd "$ROOT/run/jolt" && PATH="$BIN:$PATH" timeout 60 jolt run -m narcis.launcher --dir "$DATA" --port $PORT --serve-secs "${SERVE_SECS:-12}") ;;
  esac
}

echo "== S0 gate host=$HOST data=$DATA"
run_launcher > "$DATA/run1.log" 2>&1 &
LPID=$!
# Poll for the whole life of the process: slow hosts (JVM compiling Eve)
# may take minutes before the serve window opens.
DEADLINE=$(( $(date +%s) + 420 ))
while kill -0 $LPID 2>/dev/null && [ "$(date +%s)" -lt $DEADLINE ]; do
  if curl -fsS "http://127.0.0.1:$PORT/" -o "$DATA/page.html" 2>/dev/null; then
    curl -fsS "http://127.0.0.1:$PORT/buffer" -o "$DATA/buffer.txt" 2>/dev/null && break
  fi
  sleep 1
done
wait $LPID
cat "$DATA/run1.log"
grep -q "<title>Narcis</title>" "$DATA/page.html" && echo "S0-PAGE OK" || echo "S0-PAGE FAIL"
grep -q "hello narcis" "$DATA/buffer.txt" && echo "S0-BUFFER-SERVED OK" || echo "S0-BUFFER-SERVED FAIL"

SERVE_SECS=1 run_launcher > "$DATA/run2.log" 2>&1
cat "$DATA/run2.log"
grep -q "buffer-text-ok: true" "$DATA/run2.log" && echo "S0-REOPEN OK" || echo "S0-REOPEN FAIL"
echo "== data dir kept at $DATA"
