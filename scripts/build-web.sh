#!/usr/bin/env bash
# Build the browser client: web/narcis/web.cljs -> web/app.js
# (ClojureScript :simple, one file; the launcher serves it prebuilt —
# no build step on the user's machine, media-cohost fast-start shape).
set -eu
ROOT=$(cd "$(dirname "$0")/.." && pwd)
source "$ROOT/scripts/hosts.sh"
export PATH="$BIN:$PATH"
cd "$ROOT"
CLJ_CONFIG="$CLJ_CONFIG" clojure -M:web -m cljs.main \
  -co '{:output-to "web/app.js" :output-dir "web/out" :optimizations :simple}' \
  -c narcis.web
echo "built web/app.js ($(wc -c < web/app.js) bytes)"
