#!/usr/bin/env bash
# bigfile-launch-e2e.sh — the launch-path e2e for big files.
#
# Proves, end to end, the way a user actually runs Narcis:
# a FRESH clone of the branch, launched with `jolt narcis`
# (bb.edn's task, which resolves Eve from deps.edn's pin —
# never a sibling checkout, never EVE_RELEASE), opening a
# multi-GB file, and the load FINISHING with the document
# exact (head / middle / last lines checked against the
# file itself). One leg per worker count in $LEGS; the
# verdict requires every leg exact and the widest leg's
# speedup over the 1-worker leg at or past
# $E2E_MIN_SPEEDUP — the Nx-cores bar: N workers on real
# cores must finish in roughly 1/N the time, not the
# same time.
#
#   FILE=/path/to/big.txt LEGS="1 3" bash scripts/bigfile-launch-e2e.sh
#
# Knobs: BRANCH (default fix/loader-v3), HEAP (default 4g),
# DEADLINE_S per leg (default 3600), BASE_PORT (default 8420),
# E2E_WORK (default ~/workspace/e2e-narcis/run-<time>),
# E2E_MIN_SPEEDUP (default 2.0).
set -u
FILE=${FILE:?set FILE to the big file}
LEGS=${LEGS:-"1 3"}
BRANCH=${BRANCH:-fix/loader-v3}
HEAP=${HEAP:-4g}
DEADLINE_S=${DEADLINE_S:-3600}
BASE_PORT=${BASE_PORT:-8420}
MIN_SPEEDUP=${E2E_MIN_SPEEDUP:-2.0}
WORK=${E2E_WORK:-$HOME/workspace/e2e-narcis/run-$(date +%s)}
JOLT=${JOLT:-$HOME/.local/bin/jolt}
mkdir -p "$WORK"

# The clone must NOT sit next to an Eve checkout: bb.edn's
# eve-dir prefers ../eve over the pinned git dep, and a
# sibling would silently swap the user's Eve for a dev one.
if [ -e "$WORK/eve" ]; then
  echo "BIGFILE-LAUNCH-E2E FAIL work dir has a sibling eve checkout: $WORK" ; exit 1
fi

echo "cloning $BRANCH -> $WORK/clone"
git clone -q --branch "$BRANCH" https://github.com/johnmn3/narcis.git "$WORK/clone" || {
  echo "BIGFILE-LAUNCH-E2E FAIL clone" ; exit 1; }
(cd "$WORK/clone" && git log --oneline -1)

port_pid() { ss -tlnp 2>/dev/null | grep ":$1 " | grep -o 'pid=[0-9]*' | head -1 | cut -d= -f2; }

run_leg() {
  local w=$1 port=$2
  local store="$WORK/store-w$w" log="$WORK/server-w$w.log"
  rm -rf "$store"
  echo "--- leg workers=$w port=$port store=$store"
  (cd "$WORK/clone" && setsid nohup env -u EVE_RELEASE \
     NARCIS_SEGMENT_WORKERS=$w JOLT_MAX_HEAP=$HEAP \
     "$JOLT" narcis --dir "$store" --port "$port" --file "$FILE" \
     > "$log" 2>&1 &)
  python3 - "$port" "$FILE" "$log" "$DEADLINE_S" <<'PYEOF'
import json, re, sys, time, urllib.parse, urllib.request
port, path, log, deadline = sys.argv[1], sys.argv[2], sys.argv[3], int(sys.argv[4])

def window(line, count=1):
    url = (f"http://localhost:{port}/api/window?path={urllib.parse.quote(path)}"
           f"&line={line}&count={count}")
    with urllib.request.urlopen(url, timeout=20) as r:
        return json.loads(r.read().decode())

def field(d, name):
    return d.get(":" + name, d.get(name))

t0 = time.time(); serving = None; last_loaded = -1
while time.time() - t0 < deadline:
    try:
        with open(log, errors="replace") as f:
            txt = f.read()
        m = re.search(r"assembly of .* stopped: (.*)", txt)
        if m:
            print(f"LEG FAIL assembly stopped: {m.group(1)}"); sys.exit(3)
    except FileNotFoundError:
        pass
    try:
        with urllib.request.urlopen(f"http://localhost:{port}/api/state", timeout=8) as r:
            state = r.read().decode()
    except Exception:
        time.sleep(5); continue
    if serving is None:
        serving = time.time() - t0
        print(f"serving after {serving:.1f}s")
    lm = re.search(r':loading \{', state)
    loading = lm and (f'"{path}"' in state[lm.start():])
    if loading:
        m2 = re.search(r':loaded-lines (\d+)', state[lm.start():])
        if m2:
            cur = int(m2.group(1))
            if cur != last_loaded:
                last_loaded = cur
                print(f"  t+{time.time()-t0:.0f}s loaded-lines {cur}")
        time.sleep(5); continue
    # loading entry gone: the document must answer whole and exact
    try:
        win = window(0, 2)
    except Exception as e:
        print(f"  window not ready: {e}"); time.sleep(5); continue
    total = field(win, "total-lines")
    print(f"LEG DONE boot-to-finish {time.time()-t0:.1f}s total-lines {total}")
    # spot checks against the file itself: head, middle, last real line
    with open(path, "rb") as f:
        pieces = f.read().split(b"\n")
    last_real = len(pieces) - 2 if pieces[-1] == b"" else len(pieces) - 1
    ok = (total == len(pieces))
    if not ok:
        print(f"  total-lines mismatch: doc {total} vs file breaks+1 {len(pieces)}")
    for n in sorted({0, last_real // 2, last_real}):
        want = pieces[n].decode("utf-8", "replace")
        got = None
        for ln in field(window(n), "lines") or []:
            if field(ln, "n") == n:
                got = field(ln, "text")
        if got != want:
            ok = False
            print(f"  spot line {n} MISMATCH want {want[:40]!r} got {str(got)[:40]!r}")
        else:
            print(f"  spot line {n} OK")
    if not ok:
        print("LEG FAIL content"); sys.exit(4)
    print(f"RESULT {time.time()-t0:.3f} {total}")
    sys.exit(0)
print(f"LEG FAIL deadline {deadline}s exceeded (last loaded-lines {last_loaded})")
sys.exit(2)
PYEOF
  local rc=$?
  local pid; pid=$(port_pid "$port")
  [ -n "$pid" ] && kill "$pid" 2>/dev/null
  sleep 3
  return $rc
}

declare -A TIMES
fail=0
i=0
keys=($LEGS)
for w in $LEGS; do
  port=$((BASE_PORT + i)); i=$((i+1))
  out=$(run_leg "$w" "$port" 2>&1); rc=$?
  echo "$out"
  ms=$(echo "$out" | grep '^RESULT ' | awk '{print $2}')
  if [ $rc -ne 0 ] || [ -z "$ms" ]; then
    echo "leg workers=$w FAILED rc=$rc"; fail=1; break
  fi
  TIMES[$w]=$ms
done

if [ $fail -eq 0 ] && [ ${#keys[@]} -gt 1 ]; then
  base=${TIMES[${keys[0]}]}; last=${TIMES[${keys[-1]}]}
  echo "times: $(for k in "${keys[@]}"; do echo -n "w$k=${TIMES[$k]}s "; done)"
  sp=$(python3 -c "print(f'{$base / $last:.2f}')")
  echo "speedup w${keys[-1]} vs w${keys[0]}: ${sp}x (bar ${MIN_SPEEDUP}x)"
  python3 -c "exit(0 if $sp >= $MIN_SPEEDUP else 1)" || { echo "speedup below bar"; fail=1; }
fi

if [ $fail -eq 0 ]; then
  echo "BIGFILE-LAUNCH-E2E PASS work=$WORK"
else
  echo "BIGFILE-LAUNCH-E2E FAIL work=$WORK"
fi
exit $fail
