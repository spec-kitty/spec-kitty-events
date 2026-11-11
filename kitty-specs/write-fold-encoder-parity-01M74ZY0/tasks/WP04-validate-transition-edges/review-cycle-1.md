---
affected_files: []
cycle_number: 1
mission_slug: write-fold-encoder-parity-01M74ZY0
reproduction_command:
reviewed_at: '2026-11-11T12:25:42Z'
reviewer_agent: claude-reviewer
wp_id: WP04
---

# WP04 review, cycle 1: changes requested

Reviewer: claude-reviewer. Commits reviewed: 0f0321ad (implementation) and 5f706e8f (decision record), base 15b8c834.

The code change is correct and I found nothing wrong with it. One blocking defect is in the compatibility record. Fix it, plus the small items below, and this is approvable.

## What I verified (no action needed)

- **Edge graph.** I read core `src/specify_cli/status/wp_state.py` at `512567ee` statically (`allowed_targets()` for every state) and compared it with `_ALLOWED_TRANSITIONS`. They are equal, plus `(None, planned)`.
- **Old vs new edges.** I computed the old effective unforced set at 15b8c834 (matrix ∪ `to_blocked` ∪ `to_canceled`) and compared it with the new set. Seven edges are removed: `for_review->approved|done|in_progress|planned`, `in_review->for_review`, `genesis->blocked` and `blocked->blocked`. None are added. The COMPATIBILITY and fragment lists are complete.
- **Force and guard logic.** Unchanged: the diff only replaces the matrix and the blocked/canceled rule with a set lookup.
- **Fixtures.** Both forced valid fixtures keep their ids. The two invalid twins have `min_version` 11.5.0, and no existing `min_version` changed. Rerouting `zeitgeist_attrs/valid/wp_status_changed_done_drops_evidence.json` to `in_review -> done` is the right way to keep its intent. It stays an unforced terminal transition whose evidence is dropped, while `wp_status_changed_forced_reason_stays_local` already covers the forced shape. Forcing it would have changed the codec projection under test.
- **Other places the old edges could live.** I scanned every JSON and JSONL file under src/, tests/, scripts/, contracts/ and service test resources. No other unforced removed edge is asserted as valid. The `status_transition_parity` cases already treat these edges as illegal. The Java binding and service have no transition validator.

## Blocking

1. **The COMPATIBILITY.md 11.5.0 section and the changelog fragment leave out a changed packaged conformance fixture.**
   - Where: `COMPATIBILITY.md` (section `## 11.5.0`, list "Packaged conformance fixtures changed to stay core-valid", and the "Conformance consumers: refresh the two fixtures above" bullet) and `docs/changelog/unreleased/237-validate-transition-core-edges.md`.
   - What is missing: commit 0f0321ad also changes the packaged codec fixture `wp_status_changed_done_drops_evidence` (`zeitgeist_attrs/valid/...`). Its `payload.from_lane` and `expected_attrs.from_lane` changed from `for_review` to `in_review`. Your own move-task note records this, but the release record does not.
   - Why it blocks: the charter requires "deliberate compatibility review" for any change to conformance fixtures. Zeitgeist consumes this codec fixture, so any golden copy of `expected_attrs` will now differ. The section presents its fixture list as complete, and it is not.
   - Fix: add the fixture to the list as rerouted through `in_review` (same id, still valid, still unforced; `expected_attrs.from_lane` is now `in_review`). Change "refresh the two fixtures above" to cover all three. Add a matching clause to the fragment.

## Non-blocking (fix in this cycle if convenient)

- `src/spec_kitty_events/status.py:657`: the comment still says "Review-ref-gated, force-exempt rewinds". That contradicts the rewritten docstring section, because `for_review -> in_progress` now needs force. Drop "force-exempt" or qualify it.
- `tests/unit/test_status.py:1141`: the `test_happy_path_full_lifecycle` docstring still says `for_review -> approved -> done`. The test now goes through `in_review`.
- `src/spec_kitty_events/diary.py:1145-1152` (outside WP04's owned files, note only): the docstring ends "That is why the contract keeps the family force-exempt". For `for_review -> in_progress` that is no longer true. The status.py Rationale section also dropped the diary-continuity argument that this sentence leans on. Leave the edit to WP07 or a follow-up if you prefer, but record it.

## Anti-pattern checklist

1. Dead code: N/A (no new public symbols).
2. Synthetic-fixture test: PASS. Every test calls `validate_transition` or `reduce_status_events`.
3. Silent empty return: PASS. There are none.
4. FR coverage: PASS. FR-011 is covered by the exhaustive matrix and removed-edge tests. FR-013 is covered by `test_compatibility_doc`, which checks the version. FR-008 and FR-012 are docs requirements, which I checked by reading them.
5. Frozen surface: PASS.
6. Locked decision: PASS. Force and guard semantics are unchanged.
7. Shared-file ownership: PASS. The fixture and manifest edits outside the owned files are covered by DM-01M758AR0XV801HVGA8ZPQWATR and the move-task note.
8. Production fragility: PASS. No new raises.
