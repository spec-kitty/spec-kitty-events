# Data Model: Mission-lifecycle event reconciliation

The three payloads this mission adds schema+fixture (and, for the third, model) coverage for.
All models are frozen with `extra="forbid"`, matching their peers.

## MissionReopened (model exists in `lifecycle.py`; add schema + fixtures)
| Field | Type | Required | Notes |
|---|---|---|---|
| mission_id | str (min 1) | yes | Canonical ULID; lookup key |
| mission_slug | str (min 1) | yes | Human handle |
| reason | str (min 1) | yes | Audit reason for the re-open |
| reopened_by | str (min 1) | yes | Detected actor |
| reopened_at | str (min 1) | yes | ISO-8601 UTC |
| cleared_merge | dict \| null | no | Snapshot of cleared `merged_*` fields; null when none |

Invalid-fixture angles: missing a required field; empty `reason`; wrong type for `cleared_merge`; unknown extra key.

## FollowUpRecorded (model exists in `lifecycle.py`; add schema + fixtures)
| Field | Type | Required | Notes |
|---|---|---|---|
| mission_id | str (min 1) | yes | Canonical ULID; attribution key |
| mission_slug | str (min 1) | yes | Human handle |
| follow_up_type | "commit" \| "pr" | yes | Discriminator |
| commit_sha | str (min 1) | conditional | Required iff `follow_up_type == "commit"` |
| pr_number | int (>=1) | conditional | Required iff `follow_up_type == "pr"` |
| recorded_by | str (min 1) | yes | Detected actor |
| recorded_at | str (min 1) | yes | ISO-8601 UTC |

Invalid-fixture angles: `follow_up_type == "commit"` without `commit_sha`; `"pr"` without `pr_number`; `pr_number < 1`; unknown extra key.

## ReviewerSelfApproval (NEW model in `project_lifecycle`; mirror producer-contract row; add schema + fixtures)
| Field | Type | Required | Notes |
|---|---|---|---|
| mission_slug | str (min 1) | yes | Human handle |
| wp_id | str (min 1) | yes | Work-package id (e.g. "WP01") |
| implementing_actor | str (min 1) | yes | Actor who implemented |
| intended_reviewer | str (min 1) | yes | Reviewer who was unavailable |
| failure_reason | str (min 1) | yes | Why self-approval fell back |
| fallback_approved | bool | yes | Whether fallback self-approval was taken |
| timestamp | str (min 1) | yes | ISO-8601 UTC |

Source of truth for the field set: `src/spec_kitty_events/producer_contract/fixtures/rows/valid/wp.reviewer.selfapproved.v1.json` (`data` object) + core's `emit` site. Invalid-fixture angles: missing a required field; wrong type for `fallback_approved`; unknown extra key.
