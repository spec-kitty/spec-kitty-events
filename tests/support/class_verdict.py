"""The eight-class envelope verdict, shared by the pytest suite and the BDD scenarios (test-only).

Moved verbatim from ``tests/test_conformance_classes.py`` (spec-kitty-events#197) so that the
``envelope/`` scenarios under ``tests/scenarios/`` judge the class-taxonomy fixtures with the same
verdict as the conformance class test, not a copy of it. It is not part of the package: the
fixture-class names it dispatches on are test taxonomy, not public API (C-007).

:func:`envelope_verdict` runs its checks in a fixed order, and the first failing check decides
the code (``tests/test_class_taxonomy_support.py`` pins the order):

1. Wrapper must be a JSON object.
2. Required *Event* envelope fields must be present.
3. No forbidden legacy keys (recursive walk via
   :func:`spec_kitty_events.forbidden_keys.find_forbidden_keys`).
4. ``payload`` must be a JSON object.
5. Lane fields (when present, on ``WPStatusChanged``) must be in the
   canonical vocabulary; aliases like ``doing`` are accepted.
6. The full envelope must validate against
   :class:`spec_kitty_events.models.Event`. A ``payload``-localised
   failure becomes ``PAYLOAD_SCHEMA_FAIL``; any other envelope-level
   failure becomes ``ENVELOPE_SHAPE_INVALID``.
7. The typed payload model (when known) must accept the inner payload
   — this catches payload-level rejections (e.g. ``extra='forbid'``,
   missing required fields, type mismatches).

So an input that lacks a required field *and* carries a forbidden key is refused as
``ENVELOPE_SHAPE_INVALID``, not ``FORBIDDEN_KEY``.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any

from pydantic import ValidationError as PydanticValidationError

from spec_kitty_events import (
    LANE_ALIASES,
    MISSION_CLOSED,
    MISSION_CREATED,
    MissionClosedPayload,
    MissionCreatedPayload,
    StatusTransitionPayload,
    WP_STATUS_CHANGED,
    Lane,
)
from spec_kitty_events.forbidden_keys import (
    FORBIDDEN_LEGACY_KEYS,
    find_forbidden_keys,
)
from spec_kitty_events.models import Event
from spec_kitty_events.validation_errors import (
    ValidationError,
    ValidationErrorCode,
)


@dataclass(frozen=True)
class VerdictOutcome:
    ok: bool
    error: ValidationError | None


_PAYLOAD_MODELS: dict[str, type] = {
    MISSION_CREATED: MissionCreatedPayload,
    MISSION_CLOSED: MissionClosedPayload,
    WP_STATUS_CHANGED: StatusTransitionPayload,
}

# Required envelope fields per the public Event model. We pre-check
# these so the wrapper can emit ENVELOPE_SHAPE_INVALID with a clear
# missing-fields list before Event.model_validate runs (which would
# emit a Pydantic-internal "Field required" error).
_REQUIRED_ENVELOPE_FIELDS: tuple[str, ...] = (
    "event_id",
    "event_type",
    "aggregate_id",
    "payload",
    "timestamp",
    "build_id",
    "node_id",
    "lamport_clock",
    "correlation_id",
    "project_uuid",
)

_CANONICAL_LANE_VALUES: frozenset[str] = frozenset(member.value for member in Lane)


def _classify_pydantic_error(exc: PydanticValidationError) -> ValidationErrorCode:
    """Map a Pydantic Event-level error to a public ValidationErrorCode.

    Errors anchored at ``payload`` (or below) are payload schema
    failures; anything else is an envelope shape failure.
    """

    for err in exc.errors():
        loc = err.get("loc") or ()
        if loc and loc[0] == "payload":
            return ValidationErrorCode.PAYLOAD_SCHEMA_FAIL
    return ValidationErrorCode.ENVELOPE_SHAPE_INVALID


def envelope_verdict(envelope: Any) -> VerdictOutcome:
    """Validate a JSON-shape envelope against the package contract.

    Uses the real :class:`Event` model as the envelope source of truth.
    """

    # 1. Wrapper shape.
    if not isinstance(envelope, dict):
        return VerdictOutcome(
            ok=False,
            error=ValidationError(
                code=ValidationErrorCode.ENVELOPE_SHAPE_INVALID,
                message="envelope must be a JSON object",
                path=[],
                details={"actual_type": type(envelope).__name__},
            ),
        )

    # 2. Required envelope fields (pre-check so ENVELOPE_SHAPE_INVALID
    # surfaces a clean missing-fields list ahead of Pydantic).
    missing = [f for f in _REQUIRED_ENVELOPE_FIELDS if f not in envelope]
    if missing:
        return VerdictOutcome(
            ok=False,
            error=ValidationError(
                code=ValidationErrorCode.ENVELOPE_SHAPE_INVALID,
                message=f"envelope missing required fields: {missing!r}",
                path=[],
                details={"missing": missing},
            ),
        )

    # 3. Forbidden keys (recursive). Runs before Event.model_validate so
    # that a deeply nested or array-element forbidden key reports
    # FORBIDDEN_KEY rather than getting masked by a Pydantic
    # extra='forbid' rejection.
    first_forbidden = next(
        find_forbidden_keys(envelope, forbidden=FORBIDDEN_LEGACY_KEYS),
        None,
    )
    if first_forbidden is not None:
        return VerdictOutcome(ok=False, error=first_forbidden)

    # 4. Payload shape.
    payload = envelope["payload"]
    if not isinstance(payload, dict):
        return VerdictOutcome(
            ok=False,
            error=ValidationError(
                code=ValidationErrorCode.PAYLOAD_SCHEMA_FAIL,
                message="payload must be a JSON object",
                path=["payload"],
                details={"actual_type": type(payload).__name__},
            ),
        )

    # 5. Lane vocabulary check (only meaningful for WPStatusChanged).
    event_type = envelope["event_type"]
    if event_type == WP_STATUS_CHANGED:
        for lane_field in ("from_lane", "to_lane"):
            value = payload.get(lane_field)
            if value is None:
                continue
            if not isinstance(value, str):
                return VerdictOutcome(
                    ok=False,
                    error=ValidationError(
                        code=ValidationErrorCode.PAYLOAD_SCHEMA_FAIL,
                        message=f"{lane_field} must be a string",
                        path=["payload", lane_field],
                        details={"actual_type": type(value).__name__},
                    ),
                )
            if value not in _CANONICAL_LANE_VALUES and value not in LANE_ALIASES:
                return VerdictOutcome(
                    ok=False,
                    error=ValidationError(
                        code=ValidationErrorCode.UNKNOWN_LANE,
                        message=f"{lane_field}={value!r} is outside the canonical lane vocabulary",
                        path=["payload", lane_field],
                        details={"value": value},
                    ),
                )

    # 6. Real Event model validation. Public envelope SSOT.
    try:
        Event.model_validate(envelope)
    except PydanticValidationError as exc:
        code = _classify_pydantic_error(exc)
        return VerdictOutcome(
            ok=False,
            error=ValidationError(
                code=code,
                message=str(exc),
                path=["payload"] if code == ValidationErrorCode.PAYLOAD_SCHEMA_FAIL else [],
                details={"event_type": event_type},
            ),
        )

    # 7. Typed payload model (catches payload-level errors that Event
    # doesn't constrain because it carries `payload: Dict[str, Any]`).
    model = _PAYLOAD_MODELS.get(event_type)
    if model is not None:
        try:
            model(**payload)
        except Exception as exc:  # pydantic.ValidationError or similar
            return VerdictOutcome(
                ok=False,
                error=ValidationError(
                    code=ValidationErrorCode.PAYLOAD_SCHEMA_FAIL,
                    message=str(exc),
                    path=["payload"],
                    details={"event_type": event_type},
                ),
            )

    return VerdictOutcome(ok=True, error=None)


def lane_mapping_verdict(input_obj: dict[str, Any]) -> VerdictOutcome:
    """Validate a lane_mapping_legacy fixture.

    The fixture's ``input`` carries a ``legacy_lane`` and (for valid cases) a
    ``canonical_lane``. The mapping is valid iff ``legacy_lane`` resolves to
    a canonical ``Lane`` (directly or via ``LANE_ALIASES``) and, when
    ``canonical_lane`` is provided, equals it.
    """

    legacy = input_obj.get("legacy_lane")
    if not isinstance(legacy, str):
        return VerdictOutcome(
            ok=False,
            error=ValidationError(
                code=ValidationErrorCode.PAYLOAD_SCHEMA_FAIL,
                message="legacy_lane must be a string",
                path=["legacy_lane"],
                details={},
            ),
        )

    resolved: Lane | None
    if legacy in _CANONICAL_LANE_VALUES:
        resolved = Lane(legacy)
    elif legacy in LANE_ALIASES:
        resolved = LANE_ALIASES[legacy]
    else:
        return VerdictOutcome(
            ok=False,
            error=ValidationError(
                code=ValidationErrorCode.UNKNOWN_LANE,
                message=f"legacy lane {legacy!r} is outside the canonical lane vocabulary and not in LANE_ALIASES",
                path=["legacy_lane"],
                details={"value": legacy},
            ),
        )

    expected_canonical = input_obj.get("canonical_lane")
    if expected_canonical is not None and resolved.value != expected_canonical:
        return VerdictOutcome(
            ok=False,
            error=ValidationError(
                code=ValidationErrorCode.UNKNOWN_LANE,
                message=(
                    f"legacy lane {legacy!r} resolved to {resolved.value!r}, "
                    f"not the declared canonical {expected_canonical!r}"
                ),
                path=["canonical_lane"],
                details={"resolved": resolved.value},
            ),
        )

    return VerdictOutcome(ok=True, error=None)


def class_verdict(fixture_class: str, input_obj: Any) -> VerdictOutcome:
    if fixture_class == "lane_mapping_legacy":
        if not isinstance(input_obj, dict):
            return VerdictOutcome(
                ok=False,
                error=ValidationError(
                    code=ValidationErrorCode.PAYLOAD_SCHEMA_FAIL,
                    message="lane_mapping fixture input must be a JSON object",
                    path=[],
                    details={},
                ),
            )
        return lane_mapping_verdict(input_obj)
    return envelope_verdict(input_obj)
