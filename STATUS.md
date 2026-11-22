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
- First formatted-tree gate attempt (copy in /tmp + campaign running concurrently) is NOT a verdict: 1 timing-threshold FAIL (100 autocomplete queries 328ms vs a 250ms budget, under load) + 9 app-test ERRORs ("store is on the file arm — host cannot run Eve") + the process died mid-log. Root cause measured afterwards: **/tmp was 100% full** (512MB tmpfs; the suites' TMPDIR stores from repeated runs) — the Eve slab store cannot write, so the seam falls back to the file arm. Cleaned to 8% and re-ran solo from a worktree at b8c971f: **GREEN — TEST-SUMMARY {:test 256, :pass 1726, :fail 0, :error 0}**, identical to baseline (log /tmp/narcis-gate-fmt2.log). Format pass verified.

## Fix campaign — classes closed

- unused-import (1) `6d8fd37`; constant-condition (1) `4fa8777`; redefined-var (3 reports / 2 sites) `1bcf064`; unused-namespace (3) `b659110`; redundant-primitive-coercion (2) `3823f6a`; unused-value (8) `b9b55be` (incl. defect D1); unused-binding (9) `45f105f` + `3d0deaa`; unused-private-var (19 census + 25 cascade) `0794031` — 2 dead deleted, 42 scoped `#?(:clj ...)` after per-var usage analysis proved every use clj-only (kondo at this pin does not count uses inside reader-conditional branches; probe-banked); carve (31) `7ee9437` — 14 dead public vars + 8 cascade helpers deleted, 15 kept in `.carve/ignore`, cascades absorbed (whole-clause conditional requires in launcher/store).
- Environment incident (Nov 22 evening): a platform restore wiped /tmp (killing a gate run mid-flight, verdict re-earned at 7ee9437) and rolled the clj-paren-repair launchers back to their raw bbin form (plain `bb` + /root paths — the timbre/Maven failures recurred). Launchers rewritten to the proven design (bb-mcp + durable gitlibs root + `:mvn/local-repo` pin, with a warning header); Eve pin re-cloned durably to ~/workspace/syntax-formalization/eve-pinned (EVE_RELEASE for gates).
- Pipeline discipline held throughout: clj-replace (whole forms) → clj-paren-repair → clj-kondo per file; `cljfmt check` agreed with repair output on every edited file so far. Binding-vector/destructure edits that are not whole forms were surgical text edits followed by repair + kondo (the steer's pipeline covers whole-form edits).
- Mishap banked: commit `9436f9d` deleted a binding line together with the vector's closing bracket; parinfer re-nested the body wrongly and it was committed before the kondo delta was read. Restored from `45f105f`, redone as an extraction (`3d0deaa`), structure verified by parse + diff before commit. Lesson recorded for RETRO: after any repair pass, read the kondo delta AND the diff before committing — repair follows indentation, so a bracket slip becomes a silent re-nesting.

## Named exceptions (suppressions / disables, each with its reason)

- syntax.cljc ×7 `#_{:clj-kondo/ignore [:unused-value]}` (lines 124, 173, 181, 182, 186, 190, 475; commits b9b55be + 7ee9437): transient vector/set mutation statements — measured benign on clj+cljs (discarded conj!/pop! on transient vectors retain 29/30 after a pop on both; set conj! 20/20 both). The same linter class caught a REAL defect at two sibling sites (next section) — the ignores are only where the probe proves the discarded return is the same object. (The RegExp `set!` site from the census was NOT ignored in the end: it was restructured to `(let [_ (set! ...)] ...)` in 7ee9437.)
- `.carve/ignore` (7ee9437): narcis.store-eve ×6 — live API reached by `(resolve '…)` from narcis.store's facade, invisible to static analysis; eve.text ×9 — vendored Eve text engine whose public API is kept whole to track upstream. Carve 0.3.5's CLI never merges `.carve/config.edn` into its opts (source-verified in carve's api.clj); `.carve/ignore` is the working mechanism — `bb carve-report` exits 0.
- `.clj-kondo/config.edn` `:lint-as` for `eve.alpha/deftype` + `eve.deftype/deftype` → `clojure.core/deftype` (6de857c): Eve ships no clj-kondo exports; the mapping is structural fact, recorded in the config header itself. RETRO proposes the permanent homes (Eve exports / fleet template).
- `#_{:clj-kondo/ignore [:unresolved-var]}` ×7 forms (6de857c): eve.alpha interns its public API from a quoted [name source] table, so eve/atom, eve/aget, eve/make-array & friends are invisible to static analysis by construction; the call sites (store_eve ×5 forms, buffer ×1, test_util ×1) are verified real API (Eve alpha.cljc's api table at the pin).
- `#_{:clj-kondo/ignore [:unresolved-symbol]}` ×13 + `[:unresolved-namespace]` ×6 (6de857c): clj-host facilities (StringBuilder, Exception, class, the-ns, ns-resolve, all-ns, remove-ns, promise/deliver, requiring-resolve, Character., Thread., jolt.host/*) inside host-bound code paths of the REPL/debugger/docs machinery — they manipulate host namespaces, threads, promises and Java strings by design; the cljs pass has no such facilities. jolt.host is the host's API namespace, not a source dependency, so it can never resolve from source.

## Defects found by the campaign (re-derived before any fix)

- **D1 — bracket matches lost past 8 pairs on CLJS** (FIXED, b9b55be). compute-brackets* discarded assoc! returns on a (transient {}) inside a doseq; array-map promotion drops the rest. Probes at pin: CLJ 8/20, CLJS 8/20, jolt 20/20 — jolt's mutating assoc! is why the jolt-only corpus never saw it; the browser build would drop bracket matches past the first 8 pairs in any real file. Fix: volatile-boxed transient + vswap!.

## Integrated-pipeline trial (step 6, per steer)

Pipeline: clj-replace → clj-paren-repair → fleet `cljfmt fix` → clj-kondo, dogfooded for every whole-form edit in the campaign.

- (a) clj-replace exit codes: 0 on every well-formed match (dozens of edits; e.g. the compute-line-indentations case fix in 149b8f0 — exit 0, 1 node). Exit 4 exercised in vivo: the four duplicate if-same-truthy forms in syntax.cljc are textually identical, so clj-replace refused the ambiguous match and the file was left untouched until the sites were handled another way (see the corruption note below — the workaround chosen then was itself the campaign's one bad tool, now replaced by strict node transforms). Wrong-text calls also fail closed (no match, no write).
- (b) repair output vs `cljfmt fix` output: on pristine files (buffer, syntax, eval) and on a representative post-edit file (syntax, after a clj-replace rewrite of line-number-at-offset) the outputs are byte-identical. BUT in the f12be49 batch, repair left 8 files whose bytes differed from fleet `cljfmt fix` — clojure-mcp-light 0.2.2 formats internally with cljfmt 0.15.5, the fleet pins 0.16.6, and the two diverge on edited, indent-heavy regions of this codebase. Disagreement recorded; mitigation adopted mid-campaign and proposed for the templates (RETRO item 2): the fleet `cljfmt fix` is always the final format step.
- (c) Second repair pass: byte no-op in every measurement (3 pristine files + the post-edit file).
- (d) Kondo before/after: the tree was at 0 errors / 0 warnings before the fix campaign's kondo classes closed and returned to 0/0 after every subsequent commit; per-file kondo was run after each pipeline edit batch. Two cascades were caught exactly this way (apply-str's missing requires; nav's orphaned binding).

## Splint campaign — classes closed (census 153 -> 0 pending final recount)

- Singles (8 rules/8 sites) `f12be49`. Small classes (24 sites: into-literal, first-first, filter-vec-filterv, if-else-nil, let-if, let-when, if-same-truthy) `f9a14b9`. Buffer's segment-assembler loop+when+tail-recur reshaped to `while` (loop-empty-when, created by the let-when fix) `4057cd7`.
- identical-branches (9) `cac71be` + binding correction `a070814`: cond-pair merges by node transform (comments between clauses preserved by hand). Nav's three always-`s` clauses collapsed to `:else`, orphaning binding `e` -> `_e`; the rename first hit the wrong of two identical destructures and was corrected in a070814 (the bad intermediate was pushed and is owned in that commit's message, not rewritten).
- catch-throwable (8) `a15c97b` — NAMED EXCEPTION, no code change: all sites are the debugger/breakpoint test harness (intercept-any-throwable contract; capture helpers; jolt.debug probe). Disabled per-form via `#_{:splint/disable [lint/catch-throwable]}` on the 7 enclosing forms in breakpoints_test.clj / debugger_test.clj, reason written at the top of both files. Mechanism evidence: splint 1.25.0's runner threads the disable down from the annotated form only — an annotation on the ns form does NOT cover the file (source-read + recount).
- eq-zero (14) + fn-wrapper (13) `cec2599`; apply-str (28) `c510e16` + require cascade fix `bd81678` (3 files used clojure.string/join without requiring clojure.string — a linter's fully-qualified suggestion is not require-free); prefer-clj-string (22) `24a0cc6`; prefer-method-values (26) `9ffa4db` (each target's class verified from its construction; splint's suggestion prints a literal CLASS placeholder); format-drift cleanup `38f5698`.
- Splint autocorrect reliability (measured, for RETRO): per-file autocorrect verifiably worked for assoc-fn, eq-true, into-literal, first-first. It SILENTLY SKIPS identical-branches (reports 0 warnings, changes nothing), batch directory runs report errors while applying partially, and its re-parse dies with [splint/parsing-error] on files its own report mode reads fine (syntax.cljc, console_test.clj — map-literal complaints). Every autocorrect in this campaign was verified by diff + report-mode recount; the rest were done with strict rewrite-clj transforms using splint's printed suggestions.
- PIPELINE FINDING (also in f12be49): clj-paren-repair's internal cljfmt is 0.15.5, the fleet's is 0.16.6; on indent-heavy files their outputs DIVERGE (8 files drifted during the singles batch). Pipeline order as run since: clj-replace -> clj-paren-repair -> fleet `cljfmt fix` -> clj-kondo. The trial section below carries the measurements.

## Corruption found and corrected (149b8f0) — read this before the identical-branches line above

The f9a14b9 small-classes batch used a one-off replace-all script for the four duplicate if-same-truthy forms in compute-line-indentations; it wrote the bare token `or` where splint's `(or test else)` suggestion belonged (`:vector or`, `:map or`, two inner-cond values). Jolt's analyzer rejects a macro name as a value; clj-kondo does not flag it, splint counted the sites as fixed, and no gate ran between f9a14b9 and the splint campaign's end — so the corruption was invisible to every lint. The first gate at the splint-final tree failed to load syntax.cljc; the identical-branches "exception" in db2621a was a misdiagnosis built on the corrupted text and is WITHDRAWN. Bisected by 1-second jolt load probes (7ee9437 file loads, tip didn't; defn swap reproduced it; annotation exonerated), the four sites were fixed with the real forms in 149b8f0, the case branches combined as `(:vector :map)`, and the subsumed cond clause dropped. The other if-same-truthy sites (ops, def-target) were verified against the f9a14b9 diff to have taken the correct `(or …)` form. Lesson: a lint-clean tree is not a compiling tree — gate after every batch, and a bespoke text-replacement script is not the pipeline.

## Gates on (step 6)

- `.githooks/pre-commit` (d8ea9e4): cljfmt check + clj-kondo on staged Clojure files; enable with `git config core.hooksPath .githooks` (instructions in the hook header). Proof-run: a zero-indent staged file fails the hook (exit 1, cljfmt); a formatted, valid file passes (exit 0); a ns/path mismatch is blocked by kondo.
- CI: this repo has NO CI home (no .github/ or other CI config at the base tip) — nothing was invented. The gates are `bb lint`, the pre-commit hook, and `jolt app:test`.

## Docs (step 7)

- dprint 0.65.0; pinned `dprint.json` at the repo root, plugin line exactly: `npm:@dprint/markdown@0.29.0` (resolved by `dprint config add markdown`), includes `**/*.md`. Docs format pass is its own commit (6bf2ec8); `dprint check` exits 0. RETRO.md (14b8313) is written to the same pin.

## Exit verification (branch tip)

- `bb lint` exit 0 (kondo 0/0, splint 0, carve clean, fmt check clean), run with the bb-mcp PATH shim first on PATH (the splint leg shells `bb splint`; bb 1.3.190 cannot run Splint 1.25.0 — RETRO item 4).
- `jolt app:test` at 149b8f0 (EVE_RELEASE = the pinned Eve checkout): **TEST-SUMMARY {:test 256, :pass 1726, :fail 0, :error 0}** — identical counts to the pre-campaign baseline; the autocomplete timing test passed this run. Log: /tmp/narcis-gate-v2.log. (One mid-run assist: the suite's own temp stores filled /tmp to 89%; the oldest completed-scenario store dirs were deleted to protect the run, which then finished clean — the /tmp hazard is environmental, not a code signal.)
- `bb fmt` run twice at the tip: second run a no-op (no file changed by either run).
- Census → zero accounting:
  - clj-kondo 135 (72 errors / 61 warnings / 2 info) → 0 / 0 / 0. Every class fixed in code except the named exceptions in the section above (7 eve/* unresolved-var forms, 13+6 host-symbol sites, 7 transient-statement unused-value sites, the `:lint-as` deftype entry) — each with its reason.
  - Splint 153 → 0. Fixed in code: 145. Named exception: catch-throwable ×8 (2 files, per-form disables, reason at the top of both files). The identical-branches "exception" was withdrawn (corruption, above); the class closed in code.
  - Carve 31 → 0 reported: 14 dead public vars + 8 cascade helpers deleted (zero-reference verified repo-wide), 15 kept in `.carve/ignore` with reasons (store-eve resolve-facade API ×6, vendored eve.text API ×9).
  - cljfmt 51/52 unformatted → 0 (fleet check clean).
  - Defect D1 fixed (bracket matches past 8 pairs on CLJS).
- RETRO.md banked (14b8313): 6 proposed template changes, 4 guidance items, 3 keep-with-evidence items.
