#!/usr/bin/env bash
# Shared host/classpath setup for the gate scripts.
# A local Eve checkout stands in for the git dep in deps.edn; the pin
# in deps.edn is the record. Every path is overridable from the
# environment:
#   EVE_RELEASE  an Eve checkout (default: ../eve beside this repo)
#   BIN          a directory holding bb/jolt, prepended to PATH
#   BB_NEW       the bb binary the bb gate legs run
EVE_RELEASE=${EVE_RELEASE:-$PWD/../eve}
BIN=${BIN:-$HOME/.local/bin}
BB_NEW=${BB_NEW:-bb}
export CLJ_CONFIG=${CLJ_CONFIG:-$HOME/.clojure}
export EVE_RELEASE BIN BB_NEW

eve_bb_classpath() {
  (cd "$EVE_RELEASE" && PATH="$BIN:$PATH" bb classpath bb)
}

# JVM Clojure with Eve as a local root.
run_jvm() {
  CLJ_CONFIG="$CLJ_CONFIG" PATH="$BIN:$PATH" \
    clojure -Sdeps "{:deps {eve/eve {:local/root \"$EVE_RELEASE\"}}}" "$@"
}
export -f run_jvm
