#!/usr/bin/env bash
# Shared host/classpath setup for the spike's gate scripts.
# Local checkouts stand in for the git deps in deps.edn (proxy git
# resolution of atom.broker is slow); the pins in deps.edn are the record.
EVE_RELEASE=${EVE_RELEASE:-/home/hatch/workspace/git/atom.broker-eve}
COHOST=${COHOST:-/home/hatch/src/cohost}
BIN=${BIN:-/home/hatch/.local/bin}
BB_NEW=${BB_NEW:-$HOME/workspace/narcis-lane/tools/bb}
export CLJ_CONFIG=${CLJ_CONFIG:-/home/hatch/workspace/eve-env/cljconfig}

eve_bb_classpath() {
  (cd "$EVE_RELEASE" && PATH="$BIN:$PATH" bb classpath bb)
}

# JVM Clojure with Eve + cohost as local roots and the seeded m2 repo.
run_jvm() {
  CLJ_CONFIG="$CLJ_CONFIG" PATH="$BIN:$PATH" \
    clojure -Sdeps "{:deps {eve/eve {:local/root \"$EVE_RELEASE\"} cohost/cohost {:local/root \"$COHOST\"}}}" "$@"
}
