#!/usr/bin/env bash
# Debugger e2e: the real page, driven in headless Chromium against the
# jolt launcher — a gutter breakpoint, the pause with its locals,
# evaluating in the paused frame, Continue, and Break on error
# (test/page/debugger_e2e.cjs says each check). The debugger needs a
# Jolt with jolt.debug: run with BIN pointing at such a Jolt (the
# nightly); on a Jolt without it the suite prints SKIP and exits 0.
# Needs node with the `playwright` package and its Chromium on this
# machine (NODE_PATH names where, when it is not node's own).
# Usage: debugger-e2e.sh     (screenshots land in $E2E_SHOTS, else the
#                             run's data dir, which is kept)
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd)
source "$ROOT/scripts/hosts.sh"
export TMPDIR=${TMPDIR:-/tmp}
mkdir -p "$TMPDIR"
DATA=$(mktemp -d "$TMPDIR/narcis-debugger-e2e.XXXXXX")
PORT=${DBG_PORT:-8397}
SHOTS=${E2E_SHOTS:-$DATA/shots}
mkdir -p "$DATA/proj" "$SHOTS"
cat > "$DATA/proj/demo.clj" <<'CLJ'
(defn f [x]
  (let [y (+ x 1)]
    (* y 2)))
(println (f 41))
CLJ

JOLT_TIMEOUT=600 setsid bash "$ROOT/scripts/jolt-run.sh" narcis.launcher \
  --dir "$DATA/db" --port $PORT --file "$DATA/proj/demo.clj" --web-dir "$ROOT/web" \
  > "$DATA/server.log" 2>&1 &
SRV=$!
deadline=$(( $(date +%s) + 240 ))
until curl -fsS "http://127.0.0.1:$PORT/api/state" -o /dev/null 2>/dev/null; do
  if [ "$(date +%s)" -ge $deadline ]; then
    echo "DBG server-up FAIL"; tail -5 "$DATA/server.log"; kill -9 -"$SRV" 2>/dev/null; exit 1
  fi
  sleep 1
done
echo "== debugger e2e data=$DATA port=$PORT shots=$SHOTS"
node "$ROOT/test/page/debugger_e2e.cjs" "http://127.0.0.1:$PORT/" "$SHOTS"
RC=$?
kill -9 -"$SRV" 2>/dev/null
exit $RC
