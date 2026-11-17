#!/usr/bin/env bash
# Run a narcis namespace's -main on jolt over Eve's own jolt seat
# classpath — the shape the W1b lane proved for the store gate
# (w1b repro/narcis-jolt-w1b.sh): CP comes from Eve's bb.edn jolt task,
# never from a deps.edn local/root of the whole Eve tree (that puts the
# JVM region adapters on jolt's classpath — the W1 invocation artifact).
# Usage: jolt-run.sh <namespace> [args...]
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd)
source "$ROOT/scripts/hosts.sh"
export PATH="$BIN:$PATH"
export TMPDIR=${TMPDIR:-/tmp}
mkdir -p "$TMPDIR"
CP=$(cd "$EVE_RELEASE" && timeout 120 bb classpath jolt 2>/dev/null | tail -n 1)
if [ -z "$CP" ]; then echo "jolt-run: empty Eve jolt classpath" >&2; exit 1; fi
exec timeout ${JOLT_TIMEOUT:-600} jolt -Scp "$CP:$ROOT/src:$ROOT/test:$ROOT/bench" -m "$@"
