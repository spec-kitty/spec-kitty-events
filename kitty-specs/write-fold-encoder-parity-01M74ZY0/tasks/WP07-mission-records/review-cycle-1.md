---
affected_files: []
cycle_number: 1
mission_slug: write-fold-encoder-parity-01M74ZY0
reproduction_command:
reviewed_at: '2026-11-11T12:46:13Z'
reviewer_agent: claude-reviewer
wp_id: WP07
---

# WP07 review, cycle 1: changes requested

Reviewer: claude-reviewer. Commit reviewed: bb8115be.

## Blocking

1. **`issue-matrix.json` has no row for `#48`, which the new research.md section cites.**
   - Where: `research.md` line 106 ("force-exempt by contract" text for review-ref-gated rewinds (#48)), added by this commit.
   - Check: `grep -ohE "#[0-9]{2,5}"` over spec, plan, research, tasks, data-model and tasks/*.md gives `#48 #170 #230 #231 #236 #237 #247 #248 #6195 #6268`. The matrix has every one of them except `#48`.
   - Why it blocks: the WP's Definition of Done is that the matrix covers every cited issue. WP07 created the gap itself.
   - Fix: add a `#48` row, verdict `not-applicable`, with a one-line reason. It is spec-kitty-events#48, the review-ref-gated rewind family (see `status.py` line 179 and `diary.py` line 1140), whose contract text DM-01M758AR0XV801HVGA8ZPQWATR rewrote. Set `source_file` to `research.md`.

## Non-blocking

- `#6514` (core, the force-insurmountable review_ref rule) is cited in the decision record `decisions/DM-01M758AR0XV801HVGA8ZPQWATR.md` but has no row. Add a `not-applicable` row with `repo` `spec-kitty/spec-kitty`, or name it in the `#48` evidence.
- The `#237` row's `wp` and `evidence_ref` list WP01 to WP06 and not WP07, which owns the records. Optional.

## Verified (no action needed)
All other claims in the new research.md section match their sources:
- Commit SHAs: 37214341, b6cff598, 55c9c4d6, 74be464b, daed87d5, 1f17c1d7, 0f0321ad, ca91d3df and d96e0902 all exist, and their subjects match the matrix evidence.
- WP01: 9 fold-differential cases with the names cited, 2 identified controls, and the `unreducible` case shape.
- WP01: the `idless-and-string-none` and `idless-setter-stale-forward-applies` notes give the stated directions. The non-finite id test asserts a 500.
- WP02: the `nanoClock` bean, and "3 of 5 new tests fail" (review note).
- WP03: the digit-count fix at 74be464b, and the 3.10.20, 3.11.15 and 3.13.18 hashes (review cycle 1).
- WP04: the seven removed edges; three fixtures changed; COMPATIBILITY 11.5.0.
- WP04: no drift in `allowed_targets()` bodies between 512567ee and core HEAD. The diff of `wp_state.py` touches only `REVIEW_REF_REQUIRED_EDGES` and `check_transition` and the guards, not any `allowed_targets` return set.
- WP05: 91 ack cases (86 + 5), 89 run over HTTP, and the two `ACK_EXCLUSIONS` entries with their refusal codes (`WP_NOT_FOUND`, `REQUEST_MALFORMED`) in `ProtocolCorpusTest`. FR-018 of the earlier mission exists.
- WP06: the UV_PYTHON positive control, the planted `ProcessBuilder` creator that fails the rule, and the planted `ProcessHandle` pid reader that passes it.
- The matrix JSON parses, and its other verdicts and `repo` fields are sensible.
