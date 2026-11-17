#!/usr/bin/env bash
# S3 viewport gate: a 1,000,000-line file opened in the launcher on
# jolt; a 60-line window fetched from the middle must come back with
# only the window in it — payload bytes measured and bounded (named
# bound: 32768 bytes for 60 lines of ~30 chars, the same bound the
# headless view test asserts).
# Usage: viewport-gate.sh
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd)
source "$ROOT/scripts/hosts.sh"
export TMPDIR=${TMPDIR:-$HOME/workspace/narcis-s3-lane/tmp}
mkdir -p "$TMPDIR"
DATA=$(mktemp -d "$TMPDIR/narcis-viewport-gate.XXXXXX")
PORT=${VIEWPORT_GATE_PORT:-8392}
BIG="$DATA/big.clj"
python3 - "$BIG" <<'EOF'
import sys
with open(sys.argv[1], "w") as f:
    for i in range(1000000):
        f.write("(defn f%d [x] (+ x %d))\n" % (i, i))
EOF

PASS=0; FAIL=0
ok() { if [ "$1" = 0 ]; then echo "VIEWPORT-GATE $2 OK"; PASS=$((PASS+1)); else echo "VIEWPORT-GATE $2 FAIL"; FAIL=$((FAIL+1)); fi }

echo "== viewport gate data=$DATA port=$PORT big=$(wc -c < "$BIG") bytes"
JOLT_TIMEOUT=1200 setsid bash "$ROOT/scripts/jolt-run.sh" narcis.launcher \
  --dir "$DATA/db" --port $PORT --file "$BIG" --web-dir "$ROOT/web" \
  > "$DATA/server.log" 2>&1 &
SRV=$!
deadline=$(( $(date +%s) + 600 ))
UP=1
while [ "$(date +%s)" -lt $deadline ]; do
  if curl -fsS "http://127.0.0.1:$PORT/api/state" -o "$DATA/state.out" 2>/dev/null; then UP=0; break; fi
  sleep 3
done
ok $UP "server-up"

ENC=$(python3 -c "import urllib.parse,sys; print(urllib.parse.quote(sys.argv[1]))" "$BIG")
curl -fsS "http://127.0.0.1:$PORT/api/window?path=$ENC&start=500000&count=60" -o "$DATA/win.out" 2>/dev/null
BYTES=$(wc -c < "$DATA/win.out")
echo "VIEWPORT-GATE window payload: $BYTES bytes (bound 32768)"
[ "$BYTES" -lt 32768 ]; ok $? "payload-bounded"
python3 - "$DATA/win.out" <<'EOF'
import sys, re
body = open(sys.argv[1]).read()
total = int(re.search(r':total-lines ([0-9]+)', body).group(1))
firstn = int(re.search(r':n ([0-9]+)', body).group(1))
nlines = len(re.findall(r':text "', body))
mode = re.search(r':spans-mode :([a-z]+)', body).group(1)
assert total >= 1000000, total
assert firstn == 500000, firstn
assert nlines == 60, nlines
print("VIEWPORT-GATE window-shape OK total-lines=%d first=%d lines=%d spans-mode=%s" % (total, firstn, nlines, mode))
EOF
ok $? "window-shape"
kill -9 -"$SRV" 2>/dev/null
echo "VIEWPORT-GATE SUMMARY pass=$PASS fail=$FAIL data=$DATA"
[ "$FAIL" = 0 ]
