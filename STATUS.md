# narcis — syntax/formalization-1 lane STATUS

Branch: `syntax/formalization-1` from origin/main `2a0c9eeec8dac1b81e6076184ed5cec69a948603` (ls-remote verified at step 0).
Charter: ~/workspace/syntax-formalization/PLAN.md. Templates are coordinator-owned; this lane proposes changes in RETRO.md, never forks them.

## Tools (installed this lane, all pinned)
- clj-kondo **v2026.10.26** — GitHub release zip → ~/.local/bin/clj-kondo. Exact pinned version exists; verified `clj-kondo --version`.
- cljfmt **0.16.6** (git tag 0.16.6, sha baab5008) — bbin's git install derives the wrong main ns (`weavejester.cljfmt`; the real CLI main is `cljfmt.main`) and bakes /root paths, so ~/.local/bin/cljfmt is a hand-rolled launcher pinning the same tag/sha, --deps-root at the durable gitlibs copy, CLJ_CONFIG pinned. Maven deps curl-seeded into /home/hatch/m2repo (tools.cli 1.2.245, tools.reader 1.5.2, diffutils 1.3.0, text-diff 0.1.0, rewrite-clj 1.2.50, clojure 1.11.4 + spec/core.specs).
- carve **v0.3.5** (latest tag) — bbin install + shim path repaired to the durable gitlibs copy. Smoke-tested: reports unused vars file:line:col.
- clojure-mcp-light **v0.2.2** (sha d341c239) — bbin installed the shims, but the tool CANNOT run under this box's pinned bb v1.3.190: its bundled timbre has no `set-config!` (probed: `(contains? (ns-publics 'taoensso.timbre) 'set-config!)` → false), which clojure-mcp-light.hook calls at load (hook.clj:584); the Maven timbre cannot shadow bb's bundled one (proven by trial). Fix: side-by-side **bb-mcp = babashka v1.13.230** at ~/.local/bin/bb-mcp (probe: set-config! → true); only the mcp-light launchers exec it. The box's `bb` stays v1.3.190. clj-paren-repair smoke-tested: fixed a missing-paren file, exit 0.
- Splint **1.25.0** — wired as the repo's `bb splint` task (:extra-deps pin); artifacts curl-seeded into /home/hatch/m2repo (splint, data.json, clojure 1.12.5, tools.cli 1.4.256, rewrite-clj 1.2.55, ordered, fipp, edamame, camel-snake-kebab + transitives).
- Java: ~/.local/bin/java was a dead symlink (JRE wiped). Restored Temurin **21.0.13.1+2** into ~/.local/jre21 (the symlink's target).
- Box quirk (affects every bbin install here): exec runs as root and bb resolves user.home from the passwd entry → /root, which is ephemeral. All bbin shims were path-repaired to durable /home/hatch/.gitlibs copies.

## Step 1 — templates in place (in progress)
- .cljfmt.edn, .clj-kondo/config.edn, .splint.edn copied byte-identical from the fleet templates (diff-verified).
- bb.edn: fleet tasks merged (splint pin task, lint-kondo, lint-splint, carve-report, fmt-check, fmt, lint); existing :requires/:init and narcis/app:run/app:test tasks preserved verbatim.
- Note: the fleet template's `lint` task covers kondo+splint+carve+fmt-check; the PLAN's uniform-interface text also names a dprint check in `bb lint`. Template followed verbatim (templates are coordinator-owned); discrepancy flagged for RETRO.
- clj-kondo cache generation over the full classpath (incl. Eve git dep a14775a7) running detached; log /tmp/kondo-cachegen.log.

## Census — DONE (committed untouched, 90a63c1)
- clj-kondo: 135 findings (72 errors / 61 warnings / 2 info), 52 files. Largest: unresolved-symbol 49, invalid-arity 23, unused-private-var 19.
- Splint: 153 warnings, 51 files, 21 rules. Largest: style/apply-str 28, lint/prefer-method-values 26, style/prefer-clj-string 22.
- Carve: 31 vars reported (triage pending — public API vs dead, verified per var against repo-wide usage before any deletion).
- cljfmt: 51/52 files unformatted (only src/narcis/menus.edn clean).
- Unresolved cluster root-caused (detail in CENSUS working notes): eve/deftype type/field/factory symbols (kondo has no Eve macro config; Eve ships no clj-kondo exports), host-interop symbols in reader-conditional branches, eve/* vars (kondo cache reflects Eve's JVM classpath, narcis compiles against Eve's jolt roots — the known Eve deps-shape gap).

## Step 3 — format pass
- Baseline gate (`jolt app:test`, EVE_RELEASE = the pinned Eve checkout) BEFORE formatting: **GREEN — 256 tests, 1726 assertions, 0 failures, 0 errors** (log /tmp/narcis-gate-baseline.log).
- Format pass committed pure (b8c971f, 51 files).
- First formatted-tree gate attempt (copy in /tmp + campaign running concurrently) is NOT a verdict: 1 timing-threshold FAIL (100 autocomplete queries 328ms vs a 250ms budget, under load) + 9 app-test ERRORs ("store is on the file arm — host cannot run Eve") + the process died mid-log. Root cause measured afterwards: **/tmp was 100% full** (512MB tmpfs; the suites' TMPDIR stores from repeated runs) — the Eve slab store cannot write, so the seam falls back to the file arm. Cleaned to 8% and re-running solo from a worktree at b8c971f: log /tmp/narcis-gate-fmt2.log.

## Named exceptions (suppressions / disables, each with its reason)
- syntax.cljc ×7 `#_{:clj-kondo/ignore [:unused-value]}` (commit b9b55be): transient vector/set mutation statements and one `set!` — measured benign on clj+cljs (discarded conj!/pop! on transient vectors retain 29/30 after a pop on both; set conj! 20/20 both; set! is a statement form). The same linter class caught a REAL defect at two sibling sites (next section) — the ignores are only where the probe proves the discarded return is the same object.

## Defects found by the campaign (re-derived before any fix)
- **D1 — bracket matches lost past 8 pairs on CLJS** (FIXED, b9b55be). compute-brackets* discarded assoc! returns on a (transient {}) inside a doseq; array-map promotion drops the rest. Probes at pin: CLJ 8/20, CLJS 8/20, jolt 20/20 — jolt's mutating assoc! is why the jolt-only corpus never saw it; the browser build would drop bracket matches past the first 8 pairs in any real file. Fix: volatile-boxed transient + vswap!.

## Step 3 — format pass (cont.)

## Integrated-pipeline trial (step 6, per steer)
(not yet)
