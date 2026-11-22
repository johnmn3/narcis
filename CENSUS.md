# CENSUS — narcis, before any fixing

Baseline for the no-cheats audit. Measured on branch
`syntax/formalization-1` at the fleet-templates-v1 commit (tree =
origin/main 2a0c9ee + templates), with the fleet config files in place
(.cljfmt.edn, .clj-kondo/config.edn, .splint.edn — byte-identical to
~/workspace/syntax-formalization/templates/) and the clj-kondo cache
generated over the repo's full classpath (Eve git dep a14775a7 +
Clojure 1.12.0). Scope: `src test` (52 files).

Tool versions (on the actual PATH): clj-kondo v2026.10.26 ·
cljfmt 0.16.6 · Splint 1.25.0 (under babashka v1.13.230 — Splint
requires bb ≥ 1.12.205; the box's bb v1.3.190 cannot load it) ·
carve v0.3.5.

## clj-kondo — `clj-kondo --lint src test`, default fail level: exit 3

135 findings: **72 errors, 61 warnings, 2 info**.

| linter                       | count |
| ---------------------------- | ----- |
| unresolved-symbol            | 49    |
| invalid-arity                | 23    |
| unused-private-var           | 19    |
| unresolved-var               | 10    |
| unused-binding               | 9     |
| unused-value                 | 8     |
| unresolved-namespace         | 7     |
| unused-namespace             | 3     |
| redefined-var                | 3     |
| redundant-primitive-coercion | 2     |
| constant-condition           | 1     |
| unused-import                | 1     |

By file (files with zero findings omitted): buffer.cljc 41 ·
store_eve.cljc 15 · eve/text/store.cljc 15 · syntax.cljc 11 ·
eval.cljc 9 · launcher.cljc 8 · debugger.cljc 8 · interactions.cljc 7 ·
text.cljc 5 · docs.cljc 2 · run_s2_tests.clj 2 · store.cljc 2 ·
breakpoints.cljc 2 · breakpoints_test.clj 2 · edit.cljc 1 ·
eval_test.clj 1 · debugger_wiring_test.clj 1 · test_util.clj 1 ·
commands.cljc 1 · app_test.clj 1.

## Splint — `bb splint` (bb-mcp), .splint.edn = all default rules: exit 1

**153 style warnings**, 51 files checked.

| rule                       | count |
| -------------------------- | ----- |
| style/apply-str            | 28    |
| lint/prefer-method-values  | 26    |
| style/prefer-clj-string    | 22    |
| style/eq-zero              | 14    |
| lint/fn-wrapper            | 13    |
| lint/identical-branches    | 9     |
| lint/catch-throwable       | 8     |
| lint/if-same-truthy        | 6     |
| lint/let-when              | 5     |
| lint/let-if                | 4     |
| style/filter-vec-filterv   | 3     |
| lint/if-else-nil           | 3     |
| style/first-first          | 2     |
| lint/into-literal          | 2     |
| style/redundant-let        | 1     |
| style/prefer-condp         | 1     |
| style/pos-checks           | 1     |
| style/eq-true              | 1     |
| lint/if-not-both           | 1     |
| lint/if-nil-else           | 1     |
| lint/body-unquote-splicing | 1     |
| lint/assoc-fn              | 1     |

## Carve — report mode over src+test: exit 1

**31 unused vars reported** (raw list; each must be triaged against
repo-wide usage — web/, bench/, scripts/ — before anything is called
dead): eve.text/leaf?, eve.text/text-of, eve.text/text-append,
eve.text/text-builder-cr?, eve.text/text-depth, eve.text/text-children,
eve.text/text-same-node?, eve.text/text-shares-child?,
narcis.buffer/state, narcis.buffer/note-extent-doc!,
narcis.changed/last-run-after, narcis.edit/rename-doc,
narcis.launcher/now-ms, narcis.launcher/canonical-path,
narcis.paint/style-at, narcis.paint/plan-diff, narcis.store-eve/open,
narcis.store-eve/put!, narcis.store-eve/current,
narcis.store-eve/version, narcis.store-eve/version-count,
narcis.store-eve/close!, narcis.syntax/find-definitions,
narcis.text/text?, narcis.text/ingest-tail,
narcis.text/ingest-tail-close, narcis.text/ingest-tail-read,
narcis.theme/theme-names, narcis.theme/palette-entries,
narcis.theme/next-theme.

## cljfmt — `cljfmt check src test`: exit 1

**51 of 52 files** have incorrect formatting under the fleet
.cljfmt.edn (every .cljc/.clj in src and test; the one clean file is
src/narcis/menus.edn).
