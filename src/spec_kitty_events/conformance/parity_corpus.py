"""Load and summarize the language-neutral status-transition parity corpus."""

from __future__ import annotations

import json
from dataclasses import dataclass
from importlib import resources
from typing import Any, cast

_REASON_PREFIX = "urn:spec-kitty:write-refusal:"
REASON_URNS = frozenset(
    _REASON_PREFIX + suffix
    for suffix in (
        "unknown_lane",
        "illegal_transition",
        "force_requires_actor_and_reason",
        "actor_required",
        "dependencies_unsatisfied",
        "workspace_context_required",
        "implementation_evidence_required",
        "reason_required",
        "review_ref_required",
        "reviewer_approval_required",
        "review_evidence_invalid",
        "force_note_required",
        "done_evidence_incomplete",
        "claim_held_by_other_actor",
        "start_rejected",
    )
)
WRITE_REFUSAL_CODES = frozenset(
    {
        "TRANSITION_REJECTED",
        "WP_ALREADY_CLAIMED",
        "DEPENDENCIES_NOT_SATISFIED",
        "WP_NOT_FOUND",
        "MISSION_NOT_FOUND",
        "STATUS_LOCK_HELD",
        "BOOKKEEPING_DOUBLE_EVENT_ID",
        "COORDINATION_REF_MOVED",
        "COORDINATION_REF_CHECKED_OUT",
        "PROTECTED_BRANCH_REFUSED",
        "DESTINATION_REF_NOT_FOUND",
        "DESTINATION_REF_INVALID_SHAPE",
        "DESTINATION_REF_NOT_LOCAL",
        "COORDINATION_BRANCH_DELETED",
        "OWNED_MISSION_PATH_REFUSED",
        "COORDINATION_LEDGER_UNREADABLE",
        "STATUS_EVENT_PERSISTENCE_VERIFICATION_FAILED",
        "BOOKKEEPING_COMMIT_FAILED",
        "WRITE_LOCATION_UNVERIFIED",
        "REQUEST_MALFORMED",
        "PROTOCOL_VERSION_UNSUPPORTED",
        "WRITE_TOKEN_INVALID",
        "REQUEST_REFUSED",
        "EVENT_ID_CONFLICT",
        "REQUEST_TOO_LARGE",
        "SERVICE_BUSY",
        "SERVICE_DRAINING",
    }
)

# Research §1.2 at core 512567ee. This is a coverage target, not an authority.
LEGAL_EDGES = frozenset(
    {
        ("genesis", "planned"),
        ("genesis", "canceled"),
        ("planned", "claimed"),
        ("planned", "blocked"),
        ("planned", "canceled"),
        ("claimed", "in_progress"),
        ("claimed", "blocked"),
        ("claimed", "canceled"),
        ("in_progress", "for_review"),
        ("in_progress", "approved"),
        ("in_progress", "planned"),
        ("in_progress", "blocked"),
        ("in_progress", "canceled"),
        ("for_review", "in_review"),
        ("for_review", "blocked"),
        ("for_review", "canceled"),
        ("in_review", "approved"),
        ("in_review", "done"),
        ("in_review", "in_progress"),
        ("in_review", "planned"),
        ("in_review", "blocked"),
        ("in_review", "canceled"),
        ("approved", "done"),
        ("approved", "in_progress"),
        ("approved", "planned"),
        ("approved", "blocked"),
        ("approved", "canceled"),
        ("blocked", "in_progress"),
        ("blocked", "canceled"),
    }
)


@dataclass(frozen=True)
class ParityCoverage:
    """The NFR-002 coverage dimensions present in a collection of cases."""

    accepted_unforced_edges: frozenset[tuple[str, str]]
    refused_reasons: frozenset[str]
    batch_count: int
    cross_wp_batch_count: int
    total: int


def _corpus_root() -> Any:
    return resources.files("spec_kitty_events.conformance").joinpath("status_transition_parity")


def _read_json(resource: Any) -> dict[str, Any]:
    document = json.loads(resource.read_text(encoding="utf-8"))
    if not isinstance(document, dict):
        raise ValueError(f"Parity corpus document must be an object: {resource}")
    return cast(dict[str, Any], document)


def load_parity_cases() -> list[dict[str, Any]]:
    """Load every case, sorted by id, without importing the core implementation."""

    case_dir = _corpus_root().joinpath("cases")
    entries = sorted(
        (entry for entry in case_dir.iterdir() if entry.name.endswith(".json")),
        key=lambda entry: entry.name,
    )
    cases = [_read_json(entry) for entry in entries]
    return sorted(cases, key=lambda case: str(case["id"]))


def load_unreachable() -> dict[str, str]:
    """Return each unreachable reason URN and its non-empty explanation."""

    document = _read_json(_corpus_root().joinpath("unreachable.json"))
    unreachable: dict[str, str] = {}
    for reason, cause in document.items():
        if not isinstance(reason, str) or not isinstance(cause, str):
            raise ValueError("unreachable.json must map reason URNs to strings")
        unreachable[reason] = cause
    return unreachable


def coverage(cases: list[dict[str, Any]]) -> ParityCoverage:
    """Compute the acceptance, refusal, batch and total coverage of ``cases``."""

    accepted_edges: set[tuple[str, str]] = set()
    refused_reasons: set[str] = set()
    batch_count = 0
    cross_wp_batch_count = 0
    for case in cases:
        expected = case["expected"]
        request = case["request"]
        units = request["units"]
        if request["kind"] == "batch" and len(units) > 1:
            batch_count += 1
            if len({unit["wpId"] for unit in units}) > 1:
                cross_wp_batch_count += 1
        if expected["result"] == "refusal":
            if expected["reason"] is not None:
                refused_reasons.add(expected["reason"])
            continue
        for unit in units:
            first_row = unit["rows"][0]
            if first_row.get("force") is False and first_row.get("kind") is None:
                accepted_edges.add((first_row["from_lane"], first_row["to_lane"]))
    return ParityCoverage(
        accepted_unforced_edges=frozenset(accepted_edges),
        refused_reasons=frozenset(refused_reasons),
        batch_count=batch_count,
        cross_wp_batch_count=cross_wp_batch_count,
        total=len(cases),
    )
