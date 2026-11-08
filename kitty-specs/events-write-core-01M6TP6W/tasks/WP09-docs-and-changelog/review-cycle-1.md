---
affected_files: []
cycle_number: 1
mission_slug: events-write-core-01M6TP6W
reproduction_command:
reviewed_at: '2026-11-08T21:07:57Z'
reviewer_agent: claude:claude-sonnet-5-5:reviewer
wp_id: WP09
---

# WP09 review cycle 1: REJECT (Sonnet)

Every gate is green, no file path is wrong, and nothing is forward-looking. The README's FR-026 order, durability, idempotency, budget and refusal table match the code, as do the R15–R31 table and the benchmark and native-access notes. Four small MAJORs and five MINORs remain. Fix all nine, using the exact text where it is given.

## MAJOR
1. `contracts/mission-write/schemas/WriteRefusalCode.yaml:26`, `:28`, `:42`: in all three descriptions, replace `Provisional: ADR-0007 is Proposed.` with `Provisional until migration step 4 serves the surface.` These are contract description edits only, so add nothing to the CHANGELOG beyond the existing bullet. Re-run the contract tests and `:contracts:check`.
2. `docs/glossary/README.md:134-139` ("Fail-closed write gate"): replace the paragraph with:
   "The write service's request gate: a write is refused unless the whole gate contract holds (Host and Origin guard, bearer token, protocol header, size cap, body shape), and a refusal writes nothing. The domain checks and the durable append follow the gate (#160, #161); see [reading a refusal](/guides/service-protocol-and-releases/)."
   Check that the link target exists and matches how other glossary links are written. Bump `updated` to 2026-11-08.
3. `service/command-cli/README.md`, Durability section: replace the false rollback sentence ("If the write, the sync or the size re-check fails, the log is truncated back…") with:
   "If the write or the sync fails (a short write included), the log is truncated back to the size it had before the write and forced, then the answer is `500 STATUS_EVENT_PERSISTENCE_VERIFICATION_FAILED`. If the log's size is not the one read under the lock, nothing is written and the answer is `500` as well."
   Verify against `LockedStatusLogSession.appendAndSync` in `service/event-log`.
4. Change both of these to "…because no client transport exists yet, and `coordination_ref_commit`, the surface that changes, is refused until migration step 4":
   - `docs/adr/0007-write-service-commits-coordination-status-by-ref.md:266` ("… and every write still fails closed; a protocol major…"): keep the rest of the sentence grammatical.
   - `contracts/mission-write/CHANGELOG.md` Compatibility ("Accepted because no client transport exists yet and every write still fails closed (ADR-0007 D-7).").

## MINOR
5. `src/spec_kitty_events/conformance/README.md:11` links `../../../../kitty-specs/...`. Drop the hyperlink and write "defined by the eight-class table below". Link a file in `src/` only if one exists.
6. `docs/changelog/unreleased/160-write-core.md`:
   - change "keeping it in drift with the pinned core SHA" to "keeping it free of drift against the pinned core SHA";
   - make `conformance/status_transition_parity/` repo-root relative: `src/spec_kitty_events/conformance/status_transition_parity/`;
   - add a link to `service/command-cli/README.md` for the divergence list.
7. `docs/changelog/unreleased/7284-coordination-ref-write-adrs.md:4-5`: change "ADR-0007 (Proposed)" and "ADR-0008 (Proposed)" to "Accepted". This is a declared out-of-map edit.
8. `service/command-cli/README.md`: where it says `MISSION_NOT_FOUND` answers when no `meta.json` has the `mission_id`, add "or more than one" (see `FilesystemMissionCatalog.find`).
9. `docs/changelog/unreleased/7284-coordination-ref-write-contract-breaking.md:14`: change "writes stay fail-closed until migration step 4" to "`coordination_ref_commit` stays refused until migration step 4". This is a declared out-of-map edit.

Optional: in the contract CHANGELOG, extend the `stale_from_lane` bullet with "and `WriteRefusal` binds its `type` to `TRANSITION_REJECTED`".

After the fixes, grep `docs/`, `service/`, `contracts/` and `src/spec_kitty_events/conformance/` for `fail-closed`, `fails closed`, `Proposed` and `kitty-specs/`, and classify every remaining hit.
