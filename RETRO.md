# RETRO — narcis, Phase 1 syntax-formalization experiment

What the fleet templates and tooling got wrong (or right) on this
codebase, as proposed changes for the coordinator, who owns the
templates. Every item carries its evidence from this lane. Census and
per-commit detail: CENSUS.md, STATUS.md.

## Proposed template changes

1. **Eve forms need a fleet kondo home.** The census opened with 72
   kondo errors; the bulk (49 unresolved-symbol + 23 invalid-arity)
   traced to one gap: clj-kondo has no model of `eve/deftype`. One
   entry — `:lint-as {eve.alpha/deftype clojure.core/deftype,
   eve.deftype/deftype clojure.core/deftype}` — zeroed most of them.
   Proposal, in order of preference: (a) Eve ships clj-kondo exports
   from the Eve repo so every consumer inherits the model; (b) until
   then, the fleet `clj-kondo-config.edn` carries the `:lint-as`
   pair with a header comment naming it as the Eve entry. This repo
   carries it as a named exception in `.clj-kondo/config.edn`.

2. **The pipeline's last format step must be the fleet cljfmt, by
   name.** clojure-mcp-light 0.2.2 formats internally with cljfmt
   0.15.5; the fleet pins cljfmt 0.16.6. On this codebase's
   deep-indent style the two diverge: one campaign batch left 8
   files where clj-paren-repair's output was not `cljfmt fix`'s
   output (commit f12be49). On pristine files and on a single
   representative edit they agree byte-for-byte, and repair is
   idempotent — the divergence needs edited, indent-heavy regions
   to show. Proposal: the templates README's pipeline section names
   the order clj-replace → clj-paren-repair (structure) → fleet
   `cljfmt fix` (the format of record) → clj-kondo, and notes the
   version skew as the reason the third step is not optional.

3. **Carve: standardize on `.carve/ignore`; do not promise
   `.carve/config.edn`.** Carve 0.3.5's CLI never merges
   `.carve/config.edn` into its options (source-verified in carve's
   api.clj — the README overpromises). `.carve/ignore` is the
   mechanism that works. Proposal: fleet instructions and the
   bb-tasks comments say so.

4. **State the babashka floor.** Splint 1.25.0 and
   clojure-mcp-light 0.2.2 both fail under this box's pinned
   babashka 1.3.190 (Splint's deps need a newer sci; mcp-light
   calls timbre's `set-config!`, absent from 1.3.190's bundled
   timbre). Both run under babashka 1.13.230 side-by-side.
   Proposal: the fleet bb-tasks state the minimum bb version the
   splint task needs, so the next repo doesn't rediscover it.

5. **The template `lint` task omits the docs leg the PLAN names.**
   PLAN.md puts dprint on docs, but the fleet bb-tasks' `lint`
   depends only on kondo + splint + carve + fmt-check. This repo
   followed the template verbatim (and has a pinned dprint.json).
   Proposal: add a `lint-docs` task (`dprint check`) to the
   template's `lint` depends, or amend the PLAN to make docs a
   separate gate — one of the two, so they agree.

6. **Tool installs must land on durable paths, with explicit
   mains.** bbin installs on this box baked the exec user's home
   (/root, ephemeral) into launcher scripts and picked the wrong
   main namespace for cljfmt (its CLI main is `cljfmt.main`).
   After a platform restore, the launchers broke in three distinct
   ways. Proposal: the fleet setup notes say installs go under the
   durable home, launchers pin their main namespace and deps root
   explicitly, and a restore-drill (delete the shims, re-derive)
   is part of adoption.

## Fleet guidance worth writing down (no template change)

7. **Splint autocorrect is a draft, never a result.** Measured on
   1.25.0: it silently skips lint/identical-branches (reports 0
   warnings, changes nothing); batch directory runs error while
   applying partially; its re-parse dies with
   `[splint/parsing-error]` (map-literal complaints) on files its
   own report mode reads fine (syntax.cljc, console_test.clj).
   Per-file runs worked for assoc-fn, eq-true, into-literal,
   first-first. Guidance: autocorrect per file only, and every run
   is verified by diff + report-mode recount before it counts.

8. **Splint's disable threads down from the annotated form only.**
   `#_{:splint/disable [rule]}` on the ns form does NOT cover the
   file (runner source + recount); it covers the annotated form's
   subtree. File-level exceptions annotate each enclosing form —
   see the catch-throwable exception in the two debugger test
   files. And lint/prefer-method-values prints a literal `CLASS`
   placeholder in its suggestion: budget it as hand work (each
   target's class from its construction), and verify the host
   reader accepts `Class/.member` (jolt does — gate-verified here).

9. **Kondo at 2026.10.26 does not count uses inside
   reader-conditional branches** for unused-private-var /
   unused-binding in the other platform's pass (probe-proven).
   On a .cljc codebase with platform-specific helpers this class
   dominates (42 private vars here). The fix that keeps the linter
   honest is scoping the defs (`#?(:clj (defn- …))`), not ignores.
   Guidance: say so in the fleet notes so the next .cljc repo
   budgets for it.

10. **A linter's fully-qualified suggestion is not require-free.**
    Splint's apply-str fix writes `clojure.string/join`; three
    files here didn't require clojure.string and kondo flagged
    them (on cljs an unrequired fully-qualified use is a real
    break). Guidance: after any suggestion-driven batch, the
    kondo recount is part of the same commit, not the next one.

## Where the template was right (keep, with evidence)

11. **`:unused-value` at the default level caught the campaign's
    one real defect.** Two discarded `(assoc! matches …)`
    statements in the bracket tokenizer were a genuine bug:
    transient array-map promotion drops entries past 8, so on
    CLJS the browser build would lose bracket matches past the
    first 8 pairs in any file (probe: 8/20 entries survive on clj
    and cljs, 20/20 on jolt, whose mutating assoc! hid it from
    the whole jolt-only test corpus). The other 8 sites of the
    class were probe-proven benign statement idioms. Do not
    demote this rule fleet-wide; budget per-site probes instead.

12. **The sync law held.** Byte-identical config copies, one
    source of truth, every consumer reading the repo copy — no
    drift incident in the whole campaign. The one local config
    deviation (item 1's `:lint-as`) is exactly the shape the law
    provides for: named, reasoned, in the file's header.

13. **cljfmt + parinfer as the edit loop is fast enough to be
    invisible.** Whole-tree `cljfmt fix` ≈ seconds; repair passes
    are sub-second per file; the format pass over 51 files was a
    single pure commit and the full test suite (256 tests, 1726
    assertions) passed unchanged after it.
