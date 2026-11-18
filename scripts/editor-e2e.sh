#!/usr/bin/env bash
# Editor e2e: the real page, driven in headless Chromium against the jolt
# launcher on a fixture file of known lines — caret geometry, clicks,
# arrow/Home/End/Page movement, selection, the fast-typing race,
# auto-pair, smart enter, line joining, a paredit op, find, select-all
# (test/page/editor_e2e.cjs says each check).
# Needs node with the `playwright` package and its Chromium on this
# machine (NODE_PATH names where, when it is not node's own).
# Usage: editor-e2e.sh       (screenshots land in $E2E_SHOTS, else the
#                             run's data dir, which is kept)
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd)
source "$ROOT/scripts/hosts.sh"
export TMPDIR=${TMPDIR:-/tmp}
mkdir -p "$TMPDIR"
DATA=$(mktemp -d "$TMPDIR/narcis-editor-e2e.XXXXXX")
PORT=${E2E_PORT:-8396}
SHOTS=${E2E_SHOTS:-$DATA/shots}
mkdir -p "$DATA/proj" "$SHOTS"
cat > "$DATA/proj/demo.clj" <<'CLJ'
0123456789
abc
abcdefghij
(defn greet [name] (str "Hi " name))
x
(foo) (bar)
CLJ

JOLT_TIMEOUT=600 setsid bash "$ROOT/scripts/jolt-run.sh" narcis.launcher \
  --dir "$DATA/db" --port $PORT --file "$DATA/proj/demo.clj" --web-dir "$ROOT/web" \
  > "$DATA/server.log" 2>&1 &
SRV=$!
deadline=$(( $(date +%s) + 240 ))
until curl -fsS "http://127.0.0.1:$PORT/api/state" -o /dev/null 2>/dev/null; do
  if [ "$(date +%s)" -ge $deadline ]; then
    echo "E2E server-up FAIL"; tail -5 "$DATA/server.log"; kill -9 -"$SRV" 2>/dev/null; exit 1
  fi
  sleep 1
done
echo "== editor e2e data=$DATA port=$PORT shots=$SHOTS"
node "$ROOT/test/page/editor_e2e.cjs" "http://127.0.0.1:$PORT/" "$SHOTS"
RC=$?
kill -9 -"$SRV" 2>/dev/null
exit $RC
