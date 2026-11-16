# Status — lane/narcis-s0s1

- 2026-11-16: lane started. Tool versions on PATH (/home/hatch/.local/bin):
  jolt v0.8.23, babashka v1.3.190, Clojure CLI 1.12.6.1673.
- Probes (see REPORT.md for provenance):
  - Eve (SeniorCareMarket/eve main @ c7dc390a35) does NOT load on jolt
    v0.8.23: eve.mem needs java.lang.invoke.MethodHandles, no
    :jolt/provides declared.
  - Eve release/eve-0.9.3.2 @ e7363e7d does NOT load on jolt either:
    eve.region (jvm-varhandle adapter) hits the same MethodHandles wall.
  - Eve main @ c7dc390a35 fails its own bb example (`bb run atom-swap`):
    SCI analysis error, ser/register-header-tag-reader! unresolved in
    eve.deftype-proto.alloc. Release-tree bb probe pending.
- S0 scaffold: in progress.
