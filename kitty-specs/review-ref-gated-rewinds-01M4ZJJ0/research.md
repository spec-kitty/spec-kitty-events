# Research: Review-ref-gated rewinds

## R1: Which edges carry the review_ref guard without force?

- **Decision**: three edges: `for_review -> in_progress`, `in_review -> in_progress` and `approved -> in_progress`.
- **Rationale**: in `status.py` `validate_transition`, `review_ref_required` covers `from ∈ {for_review, in_review, approved}` and `to ∈ {in_progress, planned}`, excluding forced review-rejection pairs. All three `-> in_progress` pairs are in `_ALLOWED_TRANSITIONS` as "Review rollback", with no force requirement. #48 named only two; the third is the same family.
- **Alternatives considered**: documenting only the two edges in #48. Rejected, because it would misdescribe the validator.

## R2: Force-required versus force-exempt (operator decision)

- **Decision**: force-exempt (option b). Decision Moment `01M4ZJJFKTDBRFBK4B8M5WAYWA`.
- **Rationale**: option (a) rejects envelopes that are valid today, which is a major bump. It also breaks the CLI's unforced emission. And in the diary, a forced `approved -> in_progress` skips continuity, so a stale reopen overrides `done` (reproduced in pre-spec grounding).
- **Alternatives considered**: option (a), rejected for the reasons above.

## R3: Release level

- **Decision**: patch 10.5.2.
- **Rationale**: docs, fixtures and an internal constant only, with no change in accept/reject outcomes (`contracts/versioning-and-compatibility.md`).
