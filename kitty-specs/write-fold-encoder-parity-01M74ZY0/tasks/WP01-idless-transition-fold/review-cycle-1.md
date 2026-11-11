---
affected_files: []
cycle_number: 1
mission_slug: write-fold-encoder-parity-01M74ZY0
reproduction_command:
reviewed_at: '2026-11-11T11:46:51Z'
reviewer_agent: claude-reviewer
wp_id: WP01
---

# WP01 review, cycle 1: changes requested

The production change is correct. `TransitionId` follows M2-R1: a sealed type, both constructors private, built only through `TransitionId.of`. The four data-model invariants hold in `TransitionFold.setterOf`, `EventOrdering.dedup`, the carry maps and `StateProjections`. `./gradlew :command-cli:test --rerun` gives 1667 tests, 0 failures. One FR-003 MUST is not met, and the committed notes say it cannot be met. The notes are wrong.

## Issue 1 (blocking, FR-003): the "old fold acknowledged what core drops" direction is missing, and the notes claim it is impossible

FR-003: "Differential fold cases MUST show FR-002 in both directions: one where the old fold acknowledged what core drops, one where it refused what core accepts."

The notes of `idless-setter-claimed-to-in-progress` (scripts/conformance/generate_fold_differential.py, the `notes` string of that case) say: "The opposite direction (Java applies, core drops) cannot be built ... with an id-less setter core always applies and the pre-fix fold could only drop more."

That holds for one event, but not across a log. Once the old fold drops an event that core applies, the two folds have different lanes and different setters. Core then drops a later event that the old fold applies. Example, and it matches #231's own "claimed -> in_progress" wording:

```
1. event_id null, WP01 planned    -> claimed     at :01
2. ULID_A,        WP01 for_review -> in_review   at :02
3. ULID_B,        WP01 claimed    -> in_progress at :03
```

- Package reducer (`_expected`): `{"WP01": "in_review"}`. Row 2 applies because there is no setter. Row 3 is then dropped on continuity: setter ULID_A, `claimed != in_review`.
- Pre-fix Java fold, checked by running `FoldDifferentialTest` with this case against a scratch worktree at 69d35546: `expected: IN_REVIEW but was: IN_PROGRESS`. Row 2 was dropped against the id-less row as setter, so row 3 continued `claimed` and was **applied**. The old fold acknowledged a claimed->in_progress that core drops.
- With a ULID on row 1, the reducer gives `in_progress`. That gives a matching identified control.

On the write path, the same thing happens when rows 1 and 2 are the log and row 3 is the posted unit: the old service acks a claim->in_progress that core rejects.

Fix:
- Add this case, or an equivalent one, to the generator, for example `idless-setter-cascade-acknowledged-core-drops`. Optionally add its identified control.
- Correct the impossibility paragraph in the `idless-setter-claimed-to-in-progress` notes.
- Regenerate, and confirm `--check` and `FoldDifferentialTest` stay green. The new fold already gives `in_review`, so no production change should be needed.
- Record the correction in the WP activity log, which also asked for the direction analysis.

## Non-blocking (fix if cheap, otherwise follow-up)

- Out-of-map edit: `StateProjections.java` changed (it was forced by removing `Transition.idKey()`, and the change is semantically neutral). The WP asks for a one-line rationale in the activity log, and there is none.
- `FoldDifferentialTest` (domain package) imports the adapter `adapter.out.statuslog.EventLogRowCodec`. The WP asked for this ("decode rows the way the log codec does") and it is test-only, so it is acceptable. Note it in the activity log.
- `_expected` turns `ValueError` into `{"unreducible": ...}` as well as `TypeError`. A badly written case that the reducer rejects as malformed would then become a silent "both refuse" expectation. Consider catching only `TypeError`, or asserting the exception type the case expects. The Java side checks only `UnreducibleLog` and ignores the recorded type name.
- The `{"unreducible": ...}` expected shape extends data-model.md's `{ "<wp_id>": "<lane>" }`. The README documents it. Mention it in data-model.md, or in the activity log, so the extension is recorded.
