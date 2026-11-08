#!/usr/bin/env python3
"""Generate core-backed status-transition parity cases at the pinned core SHA.

Only this test-only script imports core. The package and its conformance tests
consume the generated, language-neutral JSON documents without core installed.
"""

from __future__ import annotations

import argparse
import copy
import json
import os
import subprocess
import sys
import tempfile
from collections import Counter
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any, Iterable, Mapping, Sequence

CORE_SHA = "512567eee71e31bb39f580c4f7acf8b865e9d1b2"
REASON_PREFIX = "urn:spec-kitty:write-refusal:"
ROW_CALL_KWARGS = {
    "transition": frozenset(
        {
            "force",
            "reason",
            "reason_source",
            "review_ref",
            "evidence",
            "review_result",
            "policy_metadata",
        }
    ),
    "start_implementation": frozenset({"force", "reason", "policy_metadata"}),
    "start_review": frozenset({"review_ref", "policy_metadata"}),
    "annotation": frozenset({"delta"}),
}
CORPUS_DIR = (
    Path(__file__).parents[2] / "src/spec_kitty_events/conformance/status_transition_parity"
)
MISSION = {
    "directory": "999-status-transition-parity",
    "mission_id": "01J00000000000000000000000",  # 26-character corpus identity
    "mission_slug": "999-status-transition-parity",
}
APPROVAL = {
    "reviewer": "reviewer-a",
    "verdict": "approved",
    "reference": "review/WP01/approved",
}
REWORK = {
    "reviewer": "reviewer-a",
    "verdict": "changes_requested",
    "reference": "review/WP01/rework",
}
STRUCTURED_ACTOR = {
    "role": "implementer",
    "profile": "python-pedro",
    "tool": "codex",
    "model": "kimi-k3",
}


class GenerationError(RuntimeError):
    """A core result cannot be represented without guessing its semantics."""


@dataclass(frozen=True)
class ReasonSource:
    """One reason's core source text and the contract code bound to it."""

    reason: str
    code: str
    source: str
    texts: tuple[str, ...]


@dataclass(frozen=True)
class Call:
    """An input to one real core entry point."""

    operation: str
    wp_id: str = "WP01"
    to_lane: str | None = None
    actor: Any = "implementer-a"
    kwargs: Mapping[str, Any] = field(default_factory=dict)
    request_from_lane: str | None = None


@dataclass(frozen=True)
class Intent:
    """A deterministic core scenario; outcomes are never specified here."""

    case_id: str
    area: str
    setup: tuple[Call, ...]
    calls: tuple[Call, ...]
    tasks: Mapping[str, str] = field(default_factory=dict)
    notes: str = ""
    log_edit: str | None = None


def map_exception_to_refusal(
    exception: Exception, sources: Sequence[ReasonSource]
) -> dict[str, str]:
    """Map only core's refusal exception family through its class and source text."""

    exception_name = type(exception).__name__
    refusal_classes = {"TransitionError", "WorkPackageClaimConflict", "WorkPackageStartRejected"}
    if exception_name not in refusal_classes:
        raise GenerationError(f"Unsupported core exception type: {exception_name}: {exception}")
    message = str(exception)
    class_reason = {
        "WorkPackageClaimConflict": "claim_held_by_other_actor",
        "WorkPackageStartRejected": "start_rejected",
    }.get(type(exception).__name__)
    if class_reason is not None:
        for source in sources:
            if source.reason == class_reason:
                return {
                    "code": source.code,
                    "reason": REASON_PREFIX + source.reason,
                    "message": message,
                }
    for source in sources:
        if any(text in message for text in source.texts):
            return {
                "code": source.code,
                "reason": REASON_PREFIX + source.reason,
                "message": message,
            }
    raise GenerationError(f"No known refusal reason matched core exception: {message}")


def _existing_core_ids(case_dir: Path) -> set[str]:
    ids: set[str] = set()
    if not case_dir.exists():
        return ids
    for path in case_dir.glob("*.json"):
        document = json.loads(path.read_text(encoding="utf-8"))
        if document.get("oracle") == "core":
            ids.add(path.stem)
    return ids


def diff_case_files(rendered: Mapping[str, str], case_dir: Path) -> list[str]:
    """Return sorted content, missing and stale-core drift ids without writing."""

    differences = {
        case_id
        for case_id, text in rendered.items()
        if not (case_dir / f"{case_id}.json").exists()
        or (case_dir / f"{case_id}.json").read_text(encoding="utf-8") != text
    }
    differences.update(_existing_core_ids(case_dir) - set(rendered))
    return sorted(differences)


def write_case_files(rendered: Mapping[str, str], case_dir: Path) -> None:
    """Write generated core files, removing stale core files but never protocols."""

    case_dir.mkdir(parents=True, exist_ok=True)
    for case_id in _existing_core_ids(case_dir) - set(rendered):
        (case_dir / f"{case_id}.json").unlink()
    for case_id, text in rendered.items():
        (case_dir / f"{case_id}.json").write_text(text, encoding="utf-8", newline="\n")


def task_text(wp_id: str = "WP01", *, dependencies: str = '"[]"', subtasks: str = "[]") -> str:
    return (
        "---\n"
        f"work_package_id: {wp_id}\n"
        f"title: {wp_id} parity\n"
        f"dependencies: {dependencies}\n"
        f"subtasks: {subtasks}\n"
        "---\n\n"
        f"# {wp_id}\n"
    )


def default_tasks() -> dict[str, str]:
    return {f"WP{number:02d}.md": task_text(f"WP{number:02d}") for number in range(1, 4)}


def _ensure_row_kwargs(operation: str, kwargs: Mapping[str, Any]) -> None:
    unsupported = sorted(set(kwargs) - ROW_CALL_KWARGS[operation])
    if unsupported:
        raise GenerationError(
            f"Core arguments are not derivable from {operation} rows: {', '.join(unsupported)}"
        )


def _call(
    operation: str,
    wp_id: str,
    to_lane: str | None,
    actor: Any,
    from_lane: str | None,
    kwargs: Mapping[str, Any],
) -> Call:
    """Build a call only from fields the language-neutral request can carry."""

    _ensure_row_kwargs(operation, kwargs)
    return Call(operation, wp_id, to_lane, actor, kwargs, from_lane)


def tr(
    to_lane: str,
    *,
    wp_id: str = "WP01",
    actor: Any = "implementer-a",
    from_lane: str | None = None,
    **kwargs: Any,
) -> Call:
    return _call("transition", wp_id, to_lane, actor, from_lane, kwargs)


def start_impl(
    *,
    wp_id: str = "WP01",
    actor: Any = "implementer-a",
    from_lane: str | None = None,
    **kwargs: Any,
) -> Call:
    return _call("start_implementation", wp_id, "in_progress", actor, from_lane, kwargs)


def start_review(
    *, wp_id: str = "WP01", actor: Any = "reviewer-b", from_lane: str | None = None, **kwargs: Any
) -> Call:
    return _call("start_review", wp_id, "in_review", actor, from_lane, kwargs)


def ann(delta: Mapping[str, Any], *, wp_id: str = "WP01", actor: Any = "implementer-a") -> Call:
    return Call("annotation", wp_id, None, actor, {"delta": dict(delta)})


def path_to(
    lane: str,
    *,
    wp_id: str = "WP01",
    actor: Any = "implementer-a",
    reviewer: Any = "reviewer-a",
    reason_source: str | None = None,
) -> tuple[Call, ...]:
    """Build a state through actual core transitions, never hand-seeded verdicts."""

    evidence = {"review": APPROVAL}
    if lane == "genesis":
        return ()
    calls = [tr("planned", wp_id=wp_id, actor=actor)]
    if lane == "planned":
        return tuple(calls)
    if lane == "blocked":
        return tuple(calls + [tr("blocked", wp_id=wp_id, actor=actor)])
    if lane == "canceled":
        cancel_kwargs = {"reason": "operator: canceled", "reason_source": reason_source}
        return tuple(calls + [tr("canceled", wp_id=wp_id, actor=actor, **cancel_kwargs)])
    calls.append(tr("claimed", wp_id=wp_id, actor=actor))
    if lane == "claimed":
        return tuple(calls)
    calls.append(tr("in_progress", wp_id=wp_id, actor=actor))
    if lane == "in_progress":
        return tuple(calls)
    calls.append(tr("for_review", wp_id=wp_id, actor=actor))
    if lane == "for_review":
        return tuple(calls)
    calls.append(tr("in_review", wp_id=wp_id, actor=reviewer, review_ref="review/WP01/claim"))
    if lane == "in_review":
        return tuple(calls)
    if lane in ("approved", "done"):
        calls.append(tr("approved", wp_id=wp_id, actor=reviewer, review_result=APPROVAL))
        if lane == "approved":
            return tuple(calls)
        calls.append(tr("done", wp_id=wp_id, actor=reviewer, evidence=evidence))
        return tuple(calls)
    raise ValueError(f"Unsupported setup lane: {lane}")


def _intent(
    case_id: str,
    area: str,
    setup: Iterable[Call],
    *calls: Call,
    tasks: Mapping[str, str] | None = None,
    log_edit: str | None = None,
) -> Intent:
    return Intent(
        case_id=case_id,
        area=area,
        setup=tuple(setup),
        calls=tuple(calls),
        tasks=default_tasks() if tasks is None else tasks,
        notes=f"Core entry-point parity for {case_id.replace('-', ' ')}.",
        log_edit=log_edit,
    )


def _legal_edge_intents() -> list[Intent]:
    scenarios = [
        ("genesis", "planned", {}),
        ("genesis", "canceled", {}),
        ("planned", "claimed", {}),
        ("planned", "blocked", {}),
        ("planned", "canceled", {}),
        ("claimed", "in_progress", {}),
        ("claimed", "blocked", {}),
        ("claimed", "canceled", {}),
        ("in_progress", "for_review", {}),
        ("in_progress", "approved", {"evidence": {"review": APPROVAL}}),
        ("in_progress", "planned", {"reason": "rework"}),
        ("in_progress", "blocked", {}),
        ("in_progress", "canceled", {}),
        ("for_review", "in_review", {"actor": "reviewer-b"}),
        ("for_review", "blocked", {}),
        ("for_review", "canceled", {}),
        ("in_review", "approved", {"actor": "reviewer-a", "review_result": APPROVAL}),
        ("in_review", "done", {"actor": "reviewer-a", "review_result": APPROVAL}),
        ("in_review", "in_progress", {"review_result": REWORK, "review_ref": REWORK["reference"]}),
        ("in_review", "planned", {"review_result": REWORK, "review_ref": REWORK["reference"]}),
        ("in_review", "blocked", {"review_result": REWORK}),
        ("in_review", "canceled", {"review_result": REWORK}),
        ("approved", "done", {"evidence": {"review": APPROVAL}}),
        ("approved", "in_progress", {"review_ref": "review/WP01/rework"}),
        ("approved", "planned", {"review_ref": "review/WP01/rework"}),
        ("approved", "blocked", {}),
        ("approved", "canceled", {}),
        ("blocked", "in_progress", {}),
        ("blocked", "canceled", {}),
    ]
    intents: list[Intent] = []
    for number, (source, target, kwargs) in enumerate(scenarios, 1):
        actor = kwargs.pop("actor", "implementer-a")
        intents.append(
            _intent(
                f"edge-{number:02d}-{source.replace('_', '-')}-to-{target.replace('_', '-')}",
                "lane_machine",
                path_to(source),
                tr(target, actor=actor, **kwargs),
            )
        )
    return intents


def _guard_intents() -> list[Intent]:
    incomplete_tasks = default_tasks()
    incomplete_tasks["WP01.md"] = task_text(subtasks="[T001]")
    malformed_done = [
        ("missing-review", "in_progress", "done", {}),
        (
            "missing-reviewer",
            "in_progress",
            "done",
            {"evidence": {"review": {"verdict": "approved", "reference": "r"}}},
        ),
        (
            "missing-verdict",
            "in_progress",
            "done",
            {"evidence": {"review": {"reviewer": "r", "reference": "r"}}},
        ),
        (
            "blank-reference",
            "in_progress",
            "done",
            {"evidence": {"review": {"reviewer": "r", "verdict": "approved", "reference": "  "}}},
        ),
        (
            "approved-missing-reviewer",
            "approved",
            "done",
            {"evidence": {"review": {"verdict": "approved", "reference": "r"}}},
        ),
        (
            "in-review-missing-reference",
            "in_review",
            "done",
            {"evidence": {"review": {"reviewer": "r", "verdict": "approved"}}},
        ),
        (
            "approved-rework-verdict",
            "approved",
            "done",
            {"evidence": {"review": REWORK}},
        ),
        (
            "in-review-rework-verdict",
            "in_review",
            "done",
            {"review_result": APPROVAL, "evidence": {"review": REWORK}},
        ),
    ]
    cases = [
        _intent("guard-unknown-lane", "unknown_lane", path_to("planned"), tr("in-review")),
        _intent("guard-illegal-planned-done", "guard", path_to("planned"), tr("done")),
        _intent("guard-illegal-done-unforced", "guard", path_to("done"), tr("planned")),
        _intent(
            "guard-illegal-force-genesis",
            "guard",
            path_to("planned"),
            tr("genesis", force=True, reason="operator"),
        ),
        _intent("guard-force-missing-reason", "force", path_to("planned"), tr("done", force=True)),
        _intent(
            "guard-force-blank-actor",
            "force",
            path_to("planned"),
            tr("done", actor=" ", force=True, reason="operator"),
        ),
        _intent(
            "guard-actor-whitespace-planned", "guard", path_to("planned"), tr("claimed", actor="  ")
        ),
        _intent(
            "guard-actor-whitespace-review",
            "guard",
            path_to("for_review"),
            tr("in_review", actor="  "),
        ),
        _intent(
            "guard-subtasks-incomplete",
            "guard",
            path_to("in_progress"),
            tr("for_review"),
            tasks=incomplete_tasks,
        ),
        _intent(
            "guard-reason-required", "guard", path_to("in_progress"), tr("planned", reason="  ")
        ),
        _intent(
            "guard-review-ref-required-in-progress",
            "guard",
            path_to("approved"),
            tr("in_progress", review_ref=" "),
        ),
        _intent("guard-review-ref-required-planned", "guard", path_to("approved"), tr("planned")),
        _intent("guard-reviewer-approval-missing", "guard", path_to("in_progress"), tr("approved")),
        _intent(
            "guard-reviewer-approval-verdict",
            "guard",
            path_to("in_progress"),
            tr("approved", evidence={"review": REWORK}),
        ),
        _intent("guard-in-review-result-missing", "guard", path_to("in_review"), tr("blocked")),
        _intent(
            "guard-in-review-reviewer-missing",
            "guard",
            path_to("in_review"),
            tr(
                "blocked",
                review_result={"reviewer": "", "verdict": "changes_requested", "reference": "r"},
            ),
        ),
        _intent(
            "guard-in-review-verdict-missing",
            "guard",
            path_to("in_review"),
            tr(
                "blocked", review_result={"reviewer": "reviewer-a", "verdict": "", "reference": "r"}
            ),
        ),
        _intent(
            "guard-in-review-reference-missing",
            "guard",
            path_to("in_review"),
            tr(
                "blocked",
                review_result={
                    "reviewer": "reviewer-a",
                    "verdict": "changes_requested",
                    "reference": "",
                },
            ),
        ),
        _intent(
            "guard-review-ref-mismatch",
            "guard",
            path_to("in_review"),
            tr("planned", review_ref="other", review_result=REWORK),
        ),
        _intent(
            "guard-review-evidence-mismatch",
            "guard",
            path_to("in_review"),
            tr(
                "approved",
                review_result=APPROVAL,
                evidence={"review": {**APPROVAL, "reference": "other"}},
            ),
        ),
        _intent(
            "guard-force-note-in-review",
            "force_note",
            path_to("in_review"),
            tr("planned", force=True, reason=" "),
        ),
        _intent(
            "guard-force-note-approved",
            "force_note",
            path_to("approved"),
            tr("in_progress", force=True, reason=None),
        ),
    ]
    for suffix, source, target, kwargs in malformed_done:
        cases.append(
            _intent(
                f"guard-done-evidence-{suffix}",
                "done_evidence",
                path_to(source),
                tr(target, **kwargs),
            )
        )
    return cases


def _dependency_intents() -> list[Intent]:
    dep_tasks = default_tasks()
    dep_tasks["WP02.md"] = task_text("WP02", dependencies="[WP01]")
    setup = (*path_to("in_progress"), *path_to("planned", wp_id="WP02"))
    missing_tasks = default_tasks()
    missing_tasks["WP02.md"] = task_text("WP02", dependencies="[WP99]")
    ambiguous_tasks = dict(dep_tasks)
    ambiguous_tasks["WP02-renamed.md"] = task_text("WP02", dependencies="[WP01]")
    malformed_tasks = dict(dep_tasks)
    malformed_tasks["WP02.md"] = task_text("WP02", dependencies="{bad: WP01}")
    unreadable_tasks = dict(dep_tasks)
    unreadable_tasks["WP02.md"] = "---\nwork_package_id: WP02\ndependencies: [WP01]\n"
    start_refused_tasks = default_tasks()
    start_refused_tasks["WP01.md"] = task_text("WP01", dependencies="[WP99]")
    synthetic_setup = (
        *path_to("canceled", reason_source="synthetic"),
        *path_to("planned", wp_id="WP02"),
    )
    cases = [
        _intent(
            "dependency-planned-claim-unsatisfied",
            "dependency",
            setup,
            tr("claimed", wp_id="WP02"),
            tasks=dep_tasks,
        ),
        _intent(
            "dependency-claimed-start-unsatisfied",
            "dependency",
            (
                *path_to("in_progress"),
                *path_to("planned", wp_id="WP02"),
                tr("claimed", wp_id="WP02", force=True, reason="operator seed"),
            ),
            tr("in_progress", wp_id="WP02"),
            tasks=dep_tasks,
        ),
        _intent(
            "dependency-start-planned-two-units-refused",
            "dependency",
            path_to("planned"),
            start_impl(),
            tasks=start_refused_tasks,
        ),
        _intent(
            "dependency-missing-declaration",
            "dependency",
            path_to("planned", wp_id="WP02"),
            tr("claimed", wp_id="WP02"),
            tasks=missing_tasks,
        ),
        _intent(
            "dependency-no-tasks-file-accepted",
            "dependency",
            path_to("planned", wp_id="WP02"),
            tr("claimed", wp_id="WP02"),
            tasks={},
        ),
        _intent(
            "dependency-ambiguous-declaration",
            "dependency",
            setup,
            tr("claimed", wp_id="WP02"),
            tasks=ambiguous_tasks,
        ),
        _intent(
            "dependency-malformed-declaration",
            "dependency",
            setup,
            tr("claimed", wp_id="WP02"),
            tasks=malformed_tasks,
        ),
        _intent(
            "dependency-unreadable-declaration",
            "dependency",
            setup,
            tr("claimed", wp_id="WP02"),
            tasks=unreadable_tasks,
        ),
        _intent(
            "dependency-canceled-synthetic",
            "dependency",
            synthetic_setup,
            tr("claimed", wp_id="WP02"),
            tasks=dep_tasks,
        ),
        _intent(
            "dependency-force-bypass",
            "dependency",
            setup,
            tr("claimed", wp_id="WP02", force=True, reason="operator override"),
            tasks=dep_tasks,
        ),
        _intent(
            "dependency-non-gated-blocked",
            "dependency",
            setup,
            tr("blocked", wp_id="WP02"),
            tasks=dep_tasks,
        ),
    ]
    for satisfied in ("approved", "done"):
        for entry in ("claimed", "in_progress"):
            setup_to = "planned" if entry == "claimed" else "claimed"
            cases.append(
                _intent(
                    f"dependency-{satisfied}-{entry.replace('_', '-')}-accepted",
                    "dependency",
                    (*path_to(satisfied), *path_to(setup_to, wp_id="WP02")),
                    tr(entry, wp_id="WP02"),
                    tasks=dep_tasks,
                )
            )
    operator_setup = (
        *path_to("canceled", reason_source="operator"),
        *path_to("planned", wp_id="WP02"),
    )
    cases.append(
        _intent(
            "dependency-canceled-operator-accepted",
            "dependency",
            operator_setup,
            tr("claimed", wp_id="WP02"),
            tasks=dep_tasks,
        )
    )
    for name, declaration in (
        ("bare", "WP01"),
        ("comma", '"WP01, WP02"'),
        ("empty-string", '"[]"'),
    ):
        tasks = default_tasks()
        tasks["WP03.md"] = task_text("WP03", dependencies=declaration)
        cases.append(
            _intent(
                f"dependency-legacy-{name}",
                "dependency",
                (
                    *path_to("approved"),
                    *path_to("approved", wp_id="WP02"),
                    *path_to("planned", wp_id="WP03"),
                ),
                tr("claimed", wp_id="WP03"),
                tasks=tasks,
            )
        )
    legacy_unmet_setup = (
        *path_to("approved"),
        *path_to("in_progress", wp_id="WP02"),
        *path_to("planned", wp_id="WP03"),
    )
    for name, declaration in (("bare", "WP01"), ("comma", '"WP01, WP02"')):
        tasks = default_tasks()
        tasks["WP03.md"] = task_text("WP03", dependencies=declaration)
        cases.append(
            _intent(
                f"dependency-legacy-{name}-unmet",
                "dependency",
                legacy_unmet_setup,
                tr("claimed", wp_id="WP03"),
                tasks=tasks,
            )
        )
    return cases


def _force_intents() -> list[Intent]:
    return [
        _intent(
            "force-done-to-planned",
            "force",
            path_to("done"),
            tr("planned", force=True, reason="operator rework"),
        ),
        _intent(
            "force-canceled-to-in-progress",
            "force",
            path_to("canceled"),
            tr("in_progress", force=True, reason="operator resume"),
        ),
        _intent(
            "force-to-uninitialized",
            "force",
            path_to("planned"),
            tr("uninitialized", force=True, reason="operator reset"),
        ),
        _intent(
            "force-to-same-lane",
            "force",
            path_to("planned"),
            tr("planned", force=True, reason="operator reaffirm"),
        ),
        _intent(
            "force-arbitrary-review-lane",
            "force",
            path_to("planned"),
            tr("in_review", force=True, reason="operator review"),
        ),
        _intent(
            "force-approved-noted-exit",
            "force",
            path_to("approved"),
            tr("in_progress", force=True, reason="operator rework"),
        ),
        _intent(
            "force-canceled-to-canceled",
            "force",
            path_to("canceled"),
            tr("canceled", force=True, reason="operator reaffirm"),
        ),
        _intent(
            "force-done-to-uninitialized",
            "force",
            path_to("done"),
            tr("uninitialized", force=True, reason="operator reset"),
        ),
        _intent("alias-doing-from-blocked", "alias", path_to("blocked"), tr("doing")),
        _intent(
            "alias-doing-force",
            "alias",
            path_to("planned"),
            tr("DOING", force=True, reason="operator alias"),
        ),
    ]


def _claim_intents() -> list[Intent]:
    cases = [
        _intent("claim-start-from-planned-two-units", "claim", path_to("planned"), start_impl()),
        _intent("claim-start-from-claimed", "claim", path_to("claimed"), start_impl()),
        _intent("claim-start-review", "review_claim", path_to("for_review"), start_review()),
        _intent(
            "claim-incompatible-claimed",
            "claim",
            path_to("claimed", actor="implementer-a"),
            start_impl(actor="implementer-b"),
        ),
        _intent(
            "claim-incompatible-stale-from",
            "claim",
            path_to("claimed", actor="implementer-a"),
            start_impl(actor="implementer-b", from_lane="planned"),
        ),
        _intent(
            "claim-incompatible-in-progress",
            "claim",
            path_to("in_progress", actor="implementer-a"),
            start_impl(actor="implementer-b"),
        ),
        _intent(
            "claim-third-actor-after-rework",
            "claim",
            (
                *path_to("in_review"),
                tr(
                    "in_progress",
                    actor="reviewer-a",
                    review_result=REWORK,
                    review_ref=REWORK["reference"],
                ),
            ),
            start_impl(actor="implementer-b"),
        ),
        _intent(
            "claim-review-reviewer-conflict",
            "review_claim",
            (
                *path_to("in_review", reviewer="reviewer-a"),
                ann({"role": "reviewer"}, actor="reviewer-a"),
            ),
            start_review(actor="reviewer-b", from_lane="for_review"),
        ),
        _intent(
            "claim-start-wrong-lane-stale-from",
            "start_rejected",
            path_to("for_review"),
            start_impl(from_lane="claimed"),
        ),
        _intent("claim-start-review-done", "start_rejected", path_to("done"), start_review()),
        _intent("claim-start-genesis", "start_rejected", (), start_impl()),
        _intent(
            "claim-structured-actor-tool",
            "claim",
            path_to("claimed", actor=STRUCTURED_ACTOR),
            start_impl(actor="codex"),
        ),
    ]
    for placeholder in ("implement-command", "unknown", "user"):
        cases.append(
            _intent(
                f"claim-placeholder-{placeholder}",
                "claim",
                path_to("claimed", actor=placeholder),
                start_impl(actor="implementer-a"),
            )
        )
    return cases


def _lifecycle_force_intents() -> list[Intent]:
    """Append-only lifecycle force scenarios, keeping established case ids stable."""
    return [
        _intent(
            "claim-start-force-review-accepted",
            "claim",
            path_to("in_review"),
            start_impl(force=True, reason="operator override"),
        ),
        _intent(
            "claim-start-force-blank-note",
            "start_rejected",
            path_to("for_review"),
            start_impl(force=True, reason=" "),
        ),
        _intent(
            "claim-start-force-outside-review-lanes",
            "start_rejected",
            path_to("claimed"),
            start_impl(force=True, reason="operator override"),
        ),
        _intent(
            "claim-start-force-in-review-requires-note",
            "claim",
            path_to("in_review"),
            start_impl(force=True),
        ),
        _intent(
            "claim-start-force-approved-requires-note",
            "start_rejected",
            path_to("approved"),
            start_impl(force=True),
        ),
        _intent(
            "claim-start-for-review-other-actor-conflict",
            "claim",
            path_to("for_review"),
            start_impl(actor="implementer-b", force=True),
        ),
        _intent(
            "claim-latest-implementer-of-record-conflict",
            "claim",
            (
                *path_to("claimed", actor="implementer-a"),
                tr("in_progress", actor="implementer-b"),
                tr("for_review", actor="implementer-b"),
                tr("in_review", actor="reviewer-a"),
                tr(
                    "in_progress",
                    actor="reviewer-a",
                    review_result=REWORK,
                    review_ref=REWORK["reference"],
                ),
            ),
            start_impl(actor="implementer-a"),
        ),
    ]


def _review_fix_intents() -> list[Intent]:
    """Append-only lifecycle parity cases from the WP07 review."""
    tasks = default_tasks()
    tasks["WP01.md"] = task_text("WP01", dependencies="[WP99]")
    return [
        _intent(
            "dependency-start-planned-force-does-not-bypass",
            "dependency",
            path_to("planned"),
            start_impl(force=True),
            tasks=tasks,
        ),
        _intent(
            "dependency-start-claimed-force-does-not-bypass",
            "dependency",
            (*path_to("planned"), tr("claimed", force=True, reason="operator seed")),
            start_impl(force=True),
            tasks=tasks,
        ),
        _intent(
            "claim-start-reason-without-force",
            "start_rejected",
            path_to("claimed"),
            start_impl(reason="note without force"),
        ),
    ]


def _batch_intents() -> list[Intent]:
    planned_pair = (*path_to("planned"), *path_to("planned", wp_id="WP02"))
    return [
        _intent(
            "batch-sequential-same-wp",
            "batch",
            path_to("planned"),
            tr("claimed"),
            tr("in_progress"),
        ),
        _intent(
            "batch-cross-wp-claims",
            "batch",
            planned_pair,
            tr("claimed"),
            tr("claimed", wp_id="WP02"),
        ),
        _intent(
            "batch-cross-wp-three-units",
            "batch",
            (*planned_pair, *path_to("planned", wp_id="WP03")),
            tr("claimed"),
            tr("blocked", wp_id="WP02"),
            tr("canceled", wp_id="WP03"),
        ),
        _intent(
            "batch-refused-third-unit",
            "batch",
            planned_pair,
            tr("claimed"),
            tr("in_progress"),
            tr("done", wp_id="WP02"),
        ),
        _intent(
            "batch-refused-second-unit",
            "batch",
            planned_pair,
            tr("claimed"),
            tr("done", wp_id="WP02"),
        ),
        _intent(
            "batch-dependency-after-first",
            "batch",
            planned_pair,
            tr("approved", force=True, reason="operator approve"),
            tr("claimed", wp_id="WP02"),
        ),
        _intent(
            "batch-review-exit-sequence",
            "batch",
            path_to("in_review"),
            tr("in_progress", review_result=REWORK, review_ref=REWORK["reference"]),
            tr("for_review"),
        ),
        _intent("batch-start-planned", "batch", path_to("planned"), start_impl()),
        _intent(
            "batch-clock-ahead-approved-departure",
            "fold",
            path_to("approved"),
            tr("in_progress", review_ref="review/WP01/rework"),
            tr("for_review"),
            log_edit="clock-ahead-approval",
        ),
        _intent(
            "fold-first-wins-duplicate-id",
            "fold",
            path_to("planned"),
            tr("in_progress"),
            log_edit="duplicate-first-blocked",
        ),
    ]


def _done_evidence_scope_intents() -> list[Intent]:
    return [
        _intent(
            "done-evidence-empty-review-planned-claim",
            "done_evidence",
            path_to("planned"),
            tr("claimed", evidence={"review": {}}),
        ),
        _intent(
            "done-evidence-malformed-in-progress-planned",
            "done_evidence",
            path_to("in_progress"),
            tr("planned", reason="r", evidence={"review": {"reviewer": "a"}}),
        ),
        _intent(
            "done-evidence-nondict-to-approved",
            "done_evidence",
            path_to("in_progress"),
            tr("approved", evidence={"review": "x"}),
        ),
        _intent(
            "done-evidence-ws-reviewer-in-review-done",
            "guard",
            path_to("in_review"),
            tr(
                "done",
                actor="reviewer-a",
                review_result=APPROVAL,
                evidence={"review": {"reviewer": "  ", "verdict": "approved", "reference": "r"}},
            ),
        ),
        _intent(
            "done-evidence-ws-verdict-approved-done",
            "guard",
            path_to("approved"),
            tr(
                "done",
                actor="reviewer-a",
                evidence={"review": {"reviewer": "rv", "verdict": "  ", "reference": "r"}},
            ),
        ),
    ]


def _retrospective_fold_intents() -> list[Intent]:
    scenarios = (
        ("fold-retrospective-event-name", "retrospective-event-name"),
        ("fold-retrospective-captured", "retrospective-captured"),
        ("fold-retrospective-capture-failed", "retrospective-capture-failed"),
        ("fold-retrospective-skipped", "retrospective-skipped"),
    )
    return [
        _intent(case_id, "fold", path_to("planned"), tr("claimed"), log_edit=edit)
        for case_id, edit in scenarios
    ]


def _annotation_intents() -> list[Intent]:
    subtask_tasks = default_tasks()
    subtask_tasks["WP01.md"] = task_text(subtasks="[T001]")
    return [
        _intent(
            "annotation-subtasks-complete-unlocks-review",
            "annotation",
            path_to("in_progress"),
            ann({"subtasks": {"T001": "done"}}),
            tr("for_review"),
            tasks=subtask_tasks,
        ),
        _intent(
            "annotation-standalone-role",
            "annotation",
            path_to("claimed"),
            ann({"role": "implementer"}),
        ),
        _intent(
            "annotation-standalone-agent", "annotation", path_to("claimed"), ann({"agent": "codex"})
        ),
        _intent(
            "annotation-review-delta",
            "annotation",
            path_to("in_review"),
            ann(
                {
                    "review": {
                        "at": "2026-01-01T00:00:00+00:00",
                        "actor": "reviewer-a",
                        "wp_id": "WP01",
                        "reason": "review complete",
                    }
                },
                actor="reviewer-a",
            ),
        ),
    ]


def _illegal_matrix_intents() -> list[Intent]:
    matrix = {
        "planned": ("in_progress", "for_review", "in_review", "approved", "done", "uninitialized"),
        "claimed": ("for_review", "in_review", "approved", "done", "planned"),
        "in_progress": ("claimed", "in_review", "done", "uninitialized"),
        "for_review": ("planned", "claimed", "in_progress", "approved", "done"),
        "in_review": ("claimed", "for_review", "uninitialized"),
        "approved": ("claimed", "for_review", "in_review", "uninitialized"),
        "blocked": ("planned", "claimed", "for_review", "in_review", "approved", "done"),
        "done": (
            "claimed",
            "in_progress",
            "for_review",
            "in_review",
            "approved",
            "blocked",
            "canceled",
        ),
        "canceled": (
            "planned",
            "claimed",
            "in_progress",
            "for_review",
            "in_review",
            "approved",
            "blocked",
        ),
    }
    return [
        _intent(
            f"illegal-matrix-{source.replace('_', '-')}-to-{target.replace('_', '-')}",
            "lane_machine",
            path_to(source),
            tr(target),
        )
        for source, targets in matrix.items()
        for target in targets
    ]


def build_intents() -> tuple[Intent, ...]:
    """Return the input table; every expected outcome still comes from core."""

    intents = (
        _legal_edge_intents()
        + _guard_intents()
        + _dependency_intents()
        + _force_intents()
        + _claim_intents()
        + _batch_intents()
        + _annotation_intents()
        + _illegal_matrix_intents()
        + _done_evidence_scope_intents()
        + _retrospective_fold_intents()
        + _lifecycle_force_intents()
        + _review_fix_intents()
    )
    ids = [intent.case_id for intent in intents]
    if len(ids) != len(set(ids)):
        duplicates = sorted(case_id for case_id, count in Counter(ids).items() if count > 1)
        raise GenerationError(f"Duplicate intent ids: {duplicates}")
    return tuple(intents)


class CoreOracle:
    """Lazy core loader and real-entry-point runner."""

    def __init__(self, checkout: Path) -> None:
        actual_sha = subprocess.run(
            ["git", "-C", str(checkout), "rev-parse", "HEAD"],
            check=True,
            capture_output=True,
            text=True,
        ).stdout.strip()
        if actual_sha != CORE_SHA:
            raise GenerationError(f"Core checkout is {actual_sha}, expected {CORE_SHA}")
        sys.path.insert(0, str(checkout / "src"))
        import yaml
        from specify_cli.coordination import status_transition as coordination_status_transition
        from specify_cli.status import emit, wp_state
        from specify_cli.status import store, transition_pipeline
        from specify_cli.status.emit import build_status_event, emit_status_transition
        from specify_cli.status.models import InnerStateChanged, ReviewResult, StatusEvent
        from specify_cli.status.models import WPInnerStateDelta
        from specify_cli.status.work_package_lifecycle import (
            start_implementation_status,
            start_review_status,
        )

        self.checkout = checkout
        self.emit_module = emit
        self.coordination_status_transition = coordination_status_transition
        self.transition_pipeline = transition_pipeline
        self.store = store
        self.wp_state = wp_state
        self.build_status_event = build_status_event
        self.emit_status_transition = emit_status_transition
        self.start_implementation_status = start_implementation_status
        self.start_review_status = start_review_status
        self.StatusEvent = StatusEvent
        self.InnerStateChanged = InnerStateChanged
        self.ReviewResult = ReviewResult
        self.WPInnerStateDelta = WPInnerStateDelta
        self.sources = self._load_reason_sources(yaml)
        self._counter = 0
        self._clock = datetime(2026, 1, 1, tzinfo=timezone.utc)

    def _load_reason_sources(self, yaml: Any) -> list[ReasonSource]:
        fixture = self.checkout / "tests/contract/fixtures/mission_write_refusal_parity.yaml"
        document = yaml.safe_load(fixture.read_text(encoding="utf-8"))
        extra_texts = {
            "implementation_evidence_required": ("requires completed subtasks",),
            "review_evidence_invalid": (
                "requires review_result",
                "Review evidence must match review_result",
            ),
            "force_note_required": ("--force requires a non-blank --note",),
        }
        sources: list[ReasonSource] = []
        for item in document["reasons"]:
            texts = (item["source_text"], *extra_texts.get(item["reason"], ()))
            for text in texts:
                source_text = (self.checkout / item["source"]).read_text(encoding="utf-8")
                if text not in source_text:
                    raise GenerationError(
                        f"Refusal source text {text!r} is absent from {item['source']}"
                    )
            sources.append(ReasonSource(item["reason"], item["code"], item["source"], texts))
        return sources

    def _event_id(self) -> str:
        self._counter += 1
        return f"{self._counter:026X}"

    def _timestamp(self) -> str:
        self._counter += 1
        return (self._clock + timedelta(seconds=self._counter)).isoformat()

    def _now(self) -> datetime:
        self._counter += 1
        return self._clock + timedelta(seconds=self._counter)

    def _install_determinism(self, case_number: int) -> None:
        self._counter = case_number * 10000
        self.emit_module._generate_ulid = self._event_id
        self.emit_module.now_utc_iso = self._timestamp
        self.emit_module.now_utc = self._now
        self.coordination_status_transition.now_utc_iso = self._timestamp
        self.coordination_status_transition.now_utc = self._now
        self.transition_pipeline.now_utc_iso = self._timestamp

    def _mission_dir(self, repo: Path) -> Path:
        mission_dir = repo / "kitty-specs" / MISSION["directory"]
        mission_dir.mkdir(parents=True)
        (mission_dir / "meta.json").write_text(
            json.dumps(
                {"mission_id": MISSION["mission_id"], "mission_slug": MISSION["mission_slug"]}
            )
            + "\n",
            encoding="utf-8",
        )
        return mission_dir

    def _write_log(self, mission_dir: Path, rows: Sequence[Mapping[str, Any]]) -> None:
        text = "".join(json.dumps(row, sort_keys=True) + "\n" for row in rows)
        (mission_dir / "status.events.jsonl").write_text(text, encoding="utf-8", newline="\n")

    def _read_log(self, mission_dir: Path) -> list[dict[str, Any]]:
        path = mission_dir / "status.events.jsonl"
        if not path.exists():
            return []
        return [
            json.loads(line)
            for line in path.read_text(encoding="utf-8").splitlines()
            if line.strip()
        ]

    def _core_kwargs(self, call: Call, mission_dir: Path, repo: Path) -> dict[str, Any]:
        _ensure_row_kwargs(call.operation, call.kwargs)
        if call.operation == "transition":
            kwargs: dict[str, Any] = {
                "feature_dir": mission_dir,
                "mission_slug": MISSION["mission_slug"],
                "wp_id": call.wp_id,
                "to_lane": call.to_lane,
                "actor": call.actor,
                "repo_root": repo,
                "fan_out": False,
                "refresh_projection": False,
                **call.kwargs,
            }
        elif call.operation == "start_implementation":
            kwargs = {
                "feature_dir": mission_dir,
                "mission_slug": MISSION["mission_slug"],
                "wp_id": call.wp_id,
                "actor": call.actor,
                "workspace_context": f"worktree:/parity/{call.wp_id}",
                "execution_mode": "worktree",
                "repo_root": repo,
                **{
                    key: value
                    for key, value in call.kwargs.items()
                    if key not in {"force", "reason"}
                },
            }
            if "force" in call.kwargs:
                kwargs["review_lane_exit"] = call.kwargs["force"]
            if "reason" in call.kwargs:
                kwargs["operator_force_note"] = call.kwargs["reason"]
        else:
            kwargs = {
                "feature_dir": mission_dir,
                "mission_slug": MISSION["mission_slug"],
                "wp_id": call.wp_id,
                "actor": call.actor,
                "workspace_context": f"review:/parity/{call.wp_id}",
                "execution_mode": "worktree",
                "repo_root": repo,
                **call.kwargs,
            }
        if "review_result" in kwargs and isinstance(kwargs["review_result"], dict):
            kwargs["review_result"] = self.ReviewResult.from_dict(kwargs["review_result"])
        return kwargs

    def _recorded_kwargs(self, kwargs: Mapping[str, Any]) -> dict[str, Any]:
        result: dict[str, Any] = {}
        for key, value in kwargs.items():
            if isinstance(value, Path):
                result[key] = (
                    "${MISSION_DIR}" if value.name == MISSION["directory"] else "${REPO_ROOT}"
                )
            elif isinstance(value, self.ReviewResult):
                result[key] = value.to_dict()
            else:
                result[key] = copy.deepcopy(value)
        return result

    def _execute_annotation(
        self, call: Call, mission_dir: Path
    ) -> tuple[list[dict[str, Any]], dict[str, Any]]:
        delta = self.WPInnerStateDelta.from_dict(call.kwargs["delta"])
        at = self._timestamp()
        event_id = self._event_id()
        event = self.wp_state.annotate(
            call.wp_id,
            delta,
            actor=call.actor,
            at=at,
            event_id=event_id,
        )
        self.store.append_annotations_atomic_verified(mission_dir, [event])
        return [event.to_dict()], {
            "entry_point": "specify_cli.status.wp_state.annotate",
            "kwargs": {
                "wp_id": call.wp_id,
                "actor": call.actor,
                "at": at,
                "event_id": event_id,
                **call.kwargs,
            },
        }

    def _execute(
        self, call: Call, mission_dir: Path, repo: Path
    ) -> tuple[list[list[dict[str, Any]]], dict[str, Any]]:
        if call.operation == "annotation":
            rows, recorded = self._execute_annotation(call, mission_dir)
            return [rows], recorded
        before = len(self._read_log(mission_dir))
        kwargs = self._core_kwargs(call, mission_dir, repo)
        if call.operation == "transition":
            self.emit_status_transition(**kwargs)
        elif call.operation == "start_implementation":
            self.start_implementation_status(**kwargs)
        else:
            self.start_review_status(**kwargs)
        appended = self._read_log(mission_dir)[before:]
        if not appended:
            raise GenerationError("Core produced a zero-event no-op; model it as a protocol case")
        groups = [appended]
        if call.operation == "start_implementation" and len(appended) == 2:
            groups = [[appended[0]], [appended[1]]]
        return groups, {
            "entry_point": self._entry_point(call),
            "kwargs": self._recorded_kwargs(kwargs),
        }

    @staticmethod
    def _entry_point(call: Call) -> str:
        return {
            "transition": "specify_cli.status.emit.emit_status_transition",
            "start_implementation": "specify_cli.status.work_package_lifecycle.start_implementation_status",
            "start_review": "specify_cli.status.work_package_lifecycle.start_review_status",
            "annotation": "specify_cli.status.wp_state.annotate",
        }[call.operation]

    def _would_be_row(self, call: Call, mission_dir: Path, repo: Path) -> dict[str, Any]:
        kwargs = self._core_kwargs(call, mission_dir, repo)
        from_lane = call.request_from_lane or self.emit_module._derive_from_lane(
            mission_dir, call.wp_id
        )
        if call.operation == "start_review":
            kwargs["reason"] = "Started review via action command"
            kwargs.setdefault("review_ref", "action-review-claim")
        build_keys = (
            "mission_slug",
            "wp_id",
            "actor",
            "force",
            "execution_mode",
            "reason",
            "reason_source",
            "review_ref",
            "evidence",
            "review_result",
            "policy_metadata",
        )
        build_kwargs = {key: kwargs[key] for key in build_keys if key in kwargs}
        if call.operation == "start_implementation":
            build_kwargs.update(
                {key: call.kwargs[key] for key in ("force", "reason") if key in call.kwargs}
            )
        raw_evidence = build_kwargs.get("evidence")
        evidence_is_model_compatible = True
        if isinstance(raw_evidence, dict):
            try:
                build_kwargs["evidence"] = self.emit_module._build_done_evidence(raw_evidence)
            except Exception:
                evidence_is_model_compatible = False
                build_kwargs["evidence"] = None
        try:
            event = self.build_status_event(
                from_lane=str(from_lane),
                to_lane=call.to_lane or "in_progress",
                mission_id=MISSION["mission_id"],
                **build_kwargs,
            )
            row = event.to_dict()
        except ValueError:
            # StatusEvent cannot hold the unknown lane whose refusal is under
            # test. Use core's row model for every other field, then restore
            # the client's verbatim unknown target in the language-neutral row.
            event = self.build_status_event(
                from_lane=str(from_lane),
                to_lane=str(from_lane),
                mission_id=MISSION["mission_id"],
                **build_kwargs,
            )
            row = event.to_dict()
            row["to_lane"] = call.to_lane
        if not evidence_is_model_compatible:
            row["evidence"] = raw_evidence
        return row

    def _would_be_rows(self, call: Call, mission_dir: Path, repo: Path) -> list[dict[str, Any]]:
        """Build the refused request, including the R13 planned-start claim row."""

        from_lane = call.request_from_lane or self.emit_module._derive_from_lane(
            mission_dir, call.wp_id
        )
        if call.operation != "start_implementation" or str(from_lane) != "planned":
            return [self._would_be_row(call, mission_dir, repo)]
        planned_call = Call(
            call.operation,
            call.wp_id,
            "claimed",
            call.actor,
            call.kwargs,
            "planned",
        )
        claimed_call = Call(
            call.operation,
            call.wp_id,
            "in_progress",
            call.actor,
            call.kwargs,
            "claimed",
        )
        return [
            self._would_be_row(planned_call, mission_dir, repo),
            self._would_be_row(claimed_call, mission_dir, repo),
        ]

    def _edit_log(self, rows: list[dict[str, Any]], edit: str | None) -> list[dict[str, Any]]:
        if edit is None:
            return rows
        edited = copy.deepcopy(rows)
        if edit == "duplicate-first-blocked":
            duplicate = copy.deepcopy(edited[0])
            duplicate["to_lane"] = "blocked"
            return [duplicate, *edited]
        if edit == "clock-ahead-approval":
            approval_at = max(datetime.fromisoformat(str(row["at"])) for row in edited) + timedelta(
                days=1
            )
            for row in edited:
                if row.get("to_lane") == "approved":
                    row["at"] = approval_at.isoformat()
            return edited
        retrospective_rows = {
            "retrospective-event-name": {"event_name": "retrospective.captured"},
            "retrospective-captured": {"type": "RetrospectiveCaptured"},
            "retrospective-capture-failed": {"type": "RetrospectiveCaptureFailed"},
            "retrospective-skipped": {"type": "RetrospectiveSkipped"},
        }
        if edit in retrospective_rows:
            return [retrospective_rows[edit], *edited]
        raise GenerationError(f"Unknown log edit: {edit}")

    def generate(self, intent: Intent, case_number: int) -> dict[str, Any]:
        self._install_determinism(case_number)
        with tempfile.TemporaryDirectory(prefix="parity-core-") as temporary:
            repo = Path(temporary)
            subprocess.run(["git", "init", "-q", str(repo)], check=True, capture_output=True)
            mission_dir = self._mission_dir(repo)
            tasks_dir = mission_dir / "tasks"
            tasks_dir.mkdir()
            for name, text in intent.tasks.items():
                (tasks_dir / name).write_text(text, encoding="utf-8", newline="\n")
            for call in intent.setup:
                self._execute(call, mission_dir, repo)
            prior = self._edit_log(self._read_log(mission_dir), intent.log_edit)
            self._write_log(mission_dir, prior)
            units: list[dict[str, Any]] = []
            calls_record: list[dict[str, Any]] = []
            refusal: dict[str, Any] | None = None
            for call in intent.calls:
                unit_index = len(units)
                try:
                    groups, recorded = self._execute(call, mission_dir, repo)
                except Exception as exception:
                    if isinstance(exception, GenerationError):
                        raise
                    mapped = map_exception_to_refusal(exception, self.sources)
                    rows = self._would_be_rows(call, mission_dir, repo)
                    units.extend(
                        {"wpId": call.wp_id, "operation": call.operation, "rows": [row]}
                        for row in rows
                    )
                    calls_record.append(
                        {
                            "entry_point": self._entry_point(call),
                            "kwargs": self._recorded_kwargs(
                                self._core_kwargs(call, mission_dir, repo)
                            ),
                        }
                    )
                    refusal = {
                        **mapped,
                        "unit": unit_index if len(intent.calls) > 1 or len(rows) > 1 else None,
                    }
                    break
                calls_record.append(recorded)
                for rows in groups:
                    units.append({"wpId": call.wp_id, "operation": call.operation, "rows": rows})
            kind = (
                "batch"
                if len(units) > 1
                else "annotation"
                if intent.calls[0].operation == "annotation"
                else "unit"
            )
            expected = (
                {"result": "refusal", **refusal}
                if refusal is not None
                else {
                    "result": "ack",
                    "units": [
                        {"replayed": False, "appendedLines": len(unit["rows"])} for unit in units
                    ],
                    **({"fsyncs": 1} if kind == "batch" else {}),
                }
            )
            core_call: dict[str, Any] = (
                calls_record[0]
                if len(calls_record) == 1
                else {"entry_point": "sequential-batch", "kwargs": {"calls": calls_record}}
            )
            _normalize_timestamps(prior, units)
            return {
                "id": intent.case_id,
                "oracle": "core",
                "layer": "domain",
                "area": intent.area,
                "operation": intent.calls[0].operation,
                "mission": MISSION,
                "log": prior,
                "tasks": dict(intent.tasks),
                "request": {"kind": kind, "units": units},
                "expected": expected,
                "core_call": core_call,
                "notes": intent.notes,
            }


def _normalize_timestamps(prior: list[dict[str, Any]], units: list[dict[str, Any]]) -> None:
    rows = [*prior, *(row for unit in units for row in unit["rows"])]
    for row in rows:
        if isinstance(row.get("at"), str):
            parsed = datetime.fromisoformat(row["at"])
            row["at"] = parsed.replace(microsecond=0).isoformat()


def _render(document: Mapping[str, Any]) -> str:
    return json.dumps(document, indent=2, ensure_ascii=False, sort_keys=True) + "\n"


def _print_area_counts(case_dir: Path) -> None:
    counts: Counter[str] = Counter()
    for path in case_dir.glob("*.json"):
        document = json.loads(path.read_text(encoding="utf-8"))
        counts[document["area"]] += 1
    total = sum(counts.values())
    for area in sorted(counts):
        print(f"{area}: {counts[area]}")
    print(f"total: {total}")


def core_python(checkout: Path) -> tuple[int, int]:
    """Read the required interpreter major.minor from core's own version pin."""

    version = (checkout / ".python-version").read_text(encoding="utf-8").strip()
    parts = version.split(".")
    try:
        if len(parts) < 2:
            raise ValueError
        return int(parts[0]), int(parts[1])
    except ValueError as error:
        raise GenerationError(f"Invalid core .python-version: {version!r}") from error


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--check", action="store_true", help="diff generated core cases without writing"
    )
    args = parser.parse_args(argv)
    checkout_value = os.environ.get("SPEC_KITTY_CORE_CHECKOUT")
    if not checkout_value:
        parser.error("SPEC_KITTY_CORE_CHECKOUT is required")
    try:
        checkout = Path(checkout_value).resolve()
        required_python = core_python(checkout)
        if sys.version_info[:2] != required_python:
            parser.error(f"core's locked Python {'.'.join(map(str, required_python))} is required")
        oracle = CoreOracle(checkout)
        rendered: dict[str, str] = {}
        for number, intent in enumerate(build_intents(), 1):
            try:
                rendered[intent.case_id] = _render(oracle.generate(intent, number))
            except Exception as error:
                raise GenerationError(f"{intent.case_id}: {error}") from error
        case_dir = CORPUS_DIR / "cases"
        differences = diff_case_files(rendered, case_dir)
        if args.check:
            if differences:
                print("Drift: " + ", ".join(differences), file=sys.stderr)
                return 1
        else:
            write_case_files(rendered, case_dir)
        _print_area_counts(case_dir)
        print(f"Python {sys.version_info.major}.{sys.version_info.minor}.{sys.version_info.micro}")
        print(f"core cases: {len(rendered)}; drift: {len(differences) if args.check else 0}")
        return 0
    except GenerationError as error:
        print(f"generation failed: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
