#!/usr/bin/env bash
# Shared host/classpath setup for the gate scripts.
# Local checkouts stand in for the git deps in deps.edn (proxy git
# resolution of atom.broker is slow); the pins in deps.edn are the record.
# Every path is overridable from the environment:
#   EVE_RELEASE  an Eve release-line checkout (release/eve-0.9.3.2)
#   BIN          a directory holding bb/jolt, prepended to PATH
#   BB_NEW       the bb binary the bb gate legs run
EVE_RELEASE=${EVE_RELEASE:-$HOME/src/atom.broker}
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
