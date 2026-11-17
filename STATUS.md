# Status — lane/narcis-rope-deftype

Rope re-home onto eve/deftype + the swap-on-atom editing model
(John's law, 2026-11-16 ~20:02 ET: "an eve based editor will involve
swapping on eve atoms with eve deftyped data").
Branch: lane/narcis-rope-deftype off lane/narcis-s2 @54c856e.
Eve pin: release/eve-0.9.3.2 @84bfc7c8. No merges.
(S3 is in flight off the same base on web/launcher files; this lane
stays out of those. If S3 lands first, rebase onto it.)

## Step 1 — census at the pin (DONE; probes A–D on jolt v0.8.23,
## Eve's jolt seat classpath, lane-local probe/ — outputs in REPORT.md)

- eve/deftype at @84bfc7c8 is a real slab-backed user type macro for
  every host (the S1 "slab-offset machinery, :clj/:cljs arms" claim is
  dead at this pin). Fields: primitives, ^:Type refs (offsets),
  serialized any-value fields, ^:host. Instances are atom-storable.
- Construction law, measured: ->Type outside a domain yields the FLAT
  SISTER (RopeNode__Flat); inside swap! (or a *domain-sio* binding) it
  allocates in that domain. eve.deftype.array/make-array has no flat
  form — bare it throws :no-domain-context.
- swap! runs its fn under the atom's domain binding
  (eve.atom/swap-attempt!): deftype nodes constructed inside a swap
  allocate in the atom's own storage; a swap path-copies the tree
  there. THE model maps directly.
- Cross-domain persistent values are refused by Eve itself ("build it
  inside swap! (or with the atom's domain sio, eve.atom/atom-sio)").
  No freeze transfer for deftype instances.
- THE GAP (measured, probe B/C2): a deftype with a by-reference field
  (a kids EveArray) cannot be NESTED inside a collection value of an
  atom — freeze packs its plain field view and dies: "flat chunk: a
  value held by reference cannot be packed"
  (:eve.flat.chunk/unwritable). At an atom's ROOT it is owned and
  works (probe C1/D; eve.dataset is Eve's own precedent). Flat sisters
  likewise cannot be stored through persistent EveArray slots.
  => Design: one atom per buffer, value = a BufferState deftype at
  root (root ref + undo/redo EveArrays + cursor/sel); a separate
  plain-data editor atom for tabs/active. No Eve fix is required for
  Narcis; the gap is named with repros in REPORT.md.
- Probe D proved the whole shape on jolt: BufferState at root, edit
  and undo as swaps, close, reopen in a second process, undo across
  the restart. All texts/fields exact.

## Step 2 — the swap (IN PROGRESS)
- [ ] narcis.rope: nodes are RopeNode eve.deftype values (leaf text +
      EveArray children + counts); unchanged public API; construction
      context = caller's binding, else the root's own domain, else a
      standalone heap domain.
- [ ] narcis.buffer: editor state lives in Eve atoms (per-buffer atom
      + editor atom); every edit is a swap!; the local mirror atom and
      persist! go away. Public API unchanged.
- [ ] narcis.store: reduced to open-by-name (+ the bb file arm, W2);
      put! re-instantiates a foreign-domain root inside the swap (the
      only lawful cross-domain path); in-domain roots stored by ref.
- [ ] Gates: rope property tests; S1 scale sanity vs 991/333 ms;
      store gate; buffer gate; corpus 38/644/0F/0E; persistence.
