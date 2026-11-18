#!/usr/bin/env bash
# S3 editor gate: a real editing session over HTTP against the jolt
# launcher on a 12,000-line file — type with auto-pair, smart-enter,
# paredit-move; then kill -9 the launcher mid-session and prove from a
# SEPARATE process (narcis.editor-probe, straight against the store)
# that the edits and undo history live in the Eve disk atom; relaunch
# and undo across the restart.
# Usage: editor-gate.sh   (expects STORE impl :eve on jolt)
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd)
source "$ROOT/scripts/hosts.sh"
export TMPDIR=${TMPDIR:-/tmp}
mkdir -p "$TMPDIR"
DATA=$(mktemp -d "$TMPDIR/narcis-editor-gate.XXXXXX")
PORT=${EDITOR_GATE_PORT:-8391}
FIXTURE="$DATA/fixture.clj"
python3 - "$FIXTURE" <<'EOF'
import sys
with open(sys.argv[1], "w") as f:
    for i in range(12000):
        f.write("(defn fn-%d [x] (+ x %d)) ;; comment %d\n" % (i, i, i))
EOF
ORIG_LINE0='(defn fn-0 [x] (+ x 0)) ;; comment 0'

PASS=0; FAIL=0
ok() { if [ "$1" = 0 ]; then echo "EDITOR-GATE $2 OK"; PASS=$((PASS+1)); else echo "EDITOR-GATE $2 FAIL"; FAIL=$((FAIL+1)); fi }
contains() { case "$1" in *"$2"*) return 0;; *) return 1;; esac; }

start_server() {
  JOLT_TIMEOUT=1200 setsid bash "$ROOT/scripts/jolt-run.sh" narcis.launcher \
    --dir "$DATA/db" --port $PORT --file "$FIXTURE" --web-dir "$ROOT/web" \
    > "$DATA/server$1.log" 2>&1 &
  echo $!
}
wait_up() {
  local deadline=$(( $(date +%s) + 420 ))
  while [ "$(date +%s)" -lt $deadline ]; do
    if curl -fsS "http://127.0.0.1:$PORT/api/state" -o "$DATA/state.out" 2>/dev/null; then return 0; fi
    sleep 2
  done
  return 1
}
wait_loaded() {
  # opens are progressive: the head is the buffer at once and the
  # tail arrives in the background — editing waits for the load,
  # exactly as the page waits for its Loading note to clear
  local deadline=$(( $(date +%s) + 180 ))
  while [ "$(date +%s)" -lt $deadline ]; do
    if curl -fsS "http://127.0.0.1:$PORT/api/state" -o "$DATA/state.out" 2>/dev/null \
       && grep -q ':loading {}' "$DATA/state.out"; then return 0; fi
    sleep 1
  done
  return 1
}
op() { curl -fsS -X POST "http://127.0.0.1:$PORT/api/op" -d "$1" 2>/dev/null; }
window0() { curl -fsS "http://127.0.0.1:$PORT/api/window?path=$(python3 -c "import urllib.parse,sys; print(urllib.parse.quote(sys.argv[1]))" "$FIXTURE")&start=0&count=5" 2>/dev/null; }
line0() { window0 | python3 -c "import sys,re; m=re.search(r':text \"((?:[^\"\\\\]|\\\\.)*)\"', sys.stdin.read()); print(m.group(1) if m else '')"; }
field() { python3 -c "import sys,re; m=re.search(r':$2 ([0-9]+)', sys.stdin.read()); print(m.group(1) if m else '-1')" <<< "$1"; }

echo "== editor gate data=$DATA port=$PORT"
SRV=$(start_server 1)
wait_up; ok $? "server-up"
STATE=$(cat "$DATA/state.out")
contains "$STATE" "$FIXTURE"; ok $? "fixture-open"
wait_loaded; ok $? "fixture-loaded"
STATE=$(cat "$DATA/state.out")
LINES=$(field "$STATE" lines)
[ "${LINES:-0}" -ge 12000 ]; ok $? "fixture-lines-$LINES"
[ "$(line0)" = "$ORIG_LINE0" ]; ok $? "line0-original"

op "{:op :set-caret :path \"$FIXTURE\" :offset 0}" > /dev/null
op "{:op :type-char :path \"$FIXTURE\" :ch \"(\"}" > /dev/null
[ "$(line0)" = "()$ORIG_LINE0" ]; ok $? "auto-pair-open"
op "{:op :type-char :path \"$FIXTURE\" :ch \"x\"}" > /dev/null
[ "$(line0)" = "(x)$ORIG_LINE0" ]; ok $? "type-inside-pair"
op "{:op :smart-enter :path \"$FIXTURE\"}" > /dev/null
STATE=$(curl -fsS "http://127.0.0.1:$PORT/api/state")
LINES2=$(field "$STATE" lines)
[ "${LINES2:-0}" -eq $((LINES + 1)) ]; ok $? "smart-enter-adds-line"
op "{:op :set-caret :path \"$FIXTURE\" :offset 0}" > /dev/null
RESP=$(op "{:op :forward-sexp :path \"$FIXTURE\"}")
CARET=$(field "$RESP" caret)
[ "${CARET:-0}" -gt 3 ]; ok $? "paredit-move-caret-$CARET"
op "{:op :undo :path \"$FIXTURE\"}" > /dev/null
op "{:op :undo :path \"$FIXTURE\"}" > /dev/null
op "{:op :undo :path \"$FIXTURE\"}" > /dev/null
[ "$(line0)" = "$ORIG_LINE0" ]; ok $? "undo-x3-restores"
STATE=$(curl -fsS "http://127.0.0.1:$PORT/api/state")
[ "$(field "$STATE" undo)" = "0" ]; ok $? "undo-depth-0"

op "{:op :set-caret :path \"$FIXTURE\" :offset 0}" > /dev/null
op "{:op :type-char :path \"$FIXTURE\" :ch \"z\"}" > /dev/null
[ "$(line0)" = "z$ORIG_LINE0" ]; ok $? "pre-kill-edit"

# app surface (menus/palette/find/tree drive these same endpoints+ops)
THEMES=$(curl -fsS "http://127.0.0.1:$PORT/api/themes")
contains "$THEMES" ':name "dracula"'; ok $? "themes-endpoint"
contains "$THEMES" ':name "auto"'; ok $? "themes-has-auto"
RESP=$(op "{:op :theme/use :name \"dracula\"}")
contains "$RESP" ':theme "dracula"'; ok $? "theme-use-op"
STATE=$(curl -fsS "http://127.0.0.1:$PORT/api/state")
contains "$STATE" ':theme "dracula"'; ok $? "state-theme-dracula"
RESP=$(op "{:op :find-next :path \"$FIXTURE\" :query \"fn-100\"}")
contains "$RESP" ":match"; ok $? "find-next-match"
LIST=$(curl -fsS "http://127.0.0.1:$PORT/api/list?dir=$DATA")
contains "$LIST" "fixture.clj"; ok $? "list-endpoint"
RESP=$(op "{:op :new}")
contains "$RESP" ':path "untitled"'; ok $? "new-tab"
RESP=$(op "{:op :save-as :path \"untitled\" :new-path \"$DATA/saved-as.clj\"}")
contains "$RESP" "saved-as.clj"; ok $? "save-as-op"
[ -f "$DATA/saved-as.clj" ]; ok $? "save-as-file-written"
op "{:op :close :path \"$DATA/saved-as.clj\"}" > /dev/null
op "{:op :activate :path \"$FIXTURE\"}" > /dev/null

kill -9 -"$SRV" 2>/dev/null; sleep 2
PROBE=$(JOLT_TIMEOUT=300 bash "$ROOT/scripts/jolt-run.sh" narcis.editor-probe "$DATA/db" "$FIXTURE" 2>/dev/null)
echo "$PROBE" | grep "^PROBE"
contains "$PROBE" "PROBE impl: :eve"; ok $? "probe-impl-eve"
contains "$PROBE" "PROBE line0: z$ORIG_LINE0"; ok $? "probe-edit-in-atom"
contains "$PROBE" "PROBE undo: 1"; ok $? "probe-undo-history-in-atom"

SRV=$(start_server 2)
wait_up; ok $? "relaunch-up"
STATE=$(cat "$DATA/state.out")
[ "$(field "$STATE" undo)" = "1" ]; ok $? "relaunch-undo-depth-1"
op "{:op :undo :path \"$FIXTURE\"}" > /dev/null
[ "$(line0)" = "$ORIG_LINE0" ]; ok $? "undo-across-restart"
STATE=$(curl -fsS "http://127.0.0.1:$PORT/api/state")
contains "$STATE" ':theme "dracula"'; ok $? "theme-persists-restart"
kill -9 -"$SRV" 2>/dev/null

echo "EDITOR-GATE SUMMARY pass=$PASS fail=$FAIL data=$DATA"
[ "$FAIL" = 0 ]
