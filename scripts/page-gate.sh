#!/usr/bin/env bash
# Page gate: the real page, driven in headless Chromium against the jolt
# launcher on a small project — the window's chrome, the menus, typing,
# Run and Stop, the Interactions console, completion, the file tree,
# tabs and themes (test/page/page_gate.cjs says each check).
# Needs node with the `playwright` package and its Chromium on this
# machine (NODE_PATH names where, when it is not node's own).
# Usage: page-gate.sh        (screenshots land in $PAGE_GATE_SHOTS, else the
#                             run's data dir, which is kept)
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd)
source "$ROOT/scripts/hosts.sh"
export TMPDIR=${TMPDIR:-/tmp}
mkdir -p "$TMPDIR"
DATA=$(mktemp -d "$TMPDIR/narcis-page-gate.XXXXXX")
PORT=${PAGE_GATE_PORT:-8395}
SHOTS=${PAGE_GATE_SHOTS:-$DATA/shots}
mkdir -p "$DATA/proj/src" "$SHOTS"
cat > "$DATA/proj/demo.clj" <<'CLJ'
;; The simplest Narcis program: prints a line, then leaves a value for Run
;; to show underneath as the result. Press Run (or F5) to try it.
(println "Hello, Narcis!")
(+ 1 2)
CLJ
printf '(ns core)\n\n(defn hello [] "hi")\n' > "$DATA/proj/src/core.clj"

JOLT_TIMEOUT=600 setsid bash "$ROOT/scripts/jolt-run.sh" narcis.launcher \
  --dir "$DATA/db" --port $PORT --file "$DATA/proj/demo.clj" --web-dir "$ROOT/web" \
  > "$DATA/server.log" 2>&1 &
SRV=$!
deadline=$(( $(date +%s) + 240 ))
until curl -fsS "http://127.0.0.1:$PORT/api/state" -o /dev/null 2>/dev/null; do
  if [ "$(date +%s)" -ge $deadline ]; then
    echo "PAGE-GATE server-up FAIL"; tail -5 "$DATA/server.log"; kill -9 -"$SRV" 2>/dev/null; exit 1
  fi
  sleep 1
done
echo "== page gate data=$DATA port=$PORT shots=$SHOTS"
node "$ROOT/test/page/page_gate.cjs" "http://127.0.0.1:$PORT/" "$SHOTS"
RC=$?
kill -9 -"$SRV" 2>/dev/null
exit $RC
