"""Value types of the legacy-row upcaster: context, issues, result and error."""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Optional, Tuple

__all__ = [
    "IssueCode",
    "UpcastContext",
    "UpcastError",
    "UpcastIssue",
    "UpcastResult",
]


class IssueCode:
    """The stable issue codes of the upcaster contract (design section 9)."""

    NOT_JSON = "NOT_JSON"
    NOT_AN_OBJECT = "NOT_AN_OBJECT"
    ALREADY_CLOUDEVENT = "ALREADY_CLOUDEVENT"
    UNRECOGNISED_ROW = "UNRECOGNISED_ROW"
    UNSUPPORTED_SCHEMA_VERSION = "UNSUPPORTED_SCHEMA_VERSION"
    UNREGISTERED_TYPE = "UNREGISTERED_TYPE"
    UNEXPECTED_MEMBER = "UNEXPECTED_MEMBER"
    MISSING_MEMBER = "MISSING_MEMBER"
    INVALID_MEMBER = "INVALID_MEMBER"
    INVALID_ID = "INVALID_ID"
    INVALID_TIME = "INVALID_TIME"
    CLOCK_OUT_OF_RANGE = "CLOCK_OUT_OF_RANGE"
    MISSION_ID_CONFLICT = "MISSION_ID_CONFLICT"
    MISSION_SLUG_CONFLICT = "MISSION_SLUG_CONFLICT"
    SUBJECT_CONFLICT = "SUBJECT_CONFLICT"
    AGGREGATE_UNMAPPABLE = "AGGREGATE_UNMAPPABLE"
    MISSION_ID_MISSING = "MISSION_ID_MISSING"

    #: Every code, in contract order.
    ALL: Tuple[str, ...] = (
        NOT_JSON,
        NOT_AN_OBJECT,
        ALREADY_CLOUDEVENT,
        UNRECOGNISED_ROW,
        UNSUPPORTED_SCHEMA_VERSION,
        UNREGISTERED_TYPE,
        UNEXPECTED_MEMBER,
        MISSING_MEMBER,
        INVALID_MEMBER,
        INVALID_ID,
        INVALID_TIME,
        CLOCK_OUT_OF_RANGE,
        MISSION_ID_CONFLICT,
        MISSION_SLUG_CONFLICT,
        SUBJECT_CONFLICT,
        AGGREGATE_UNMAPPABLE,
        MISSION_ID_MISSING,
    )


@dataclass(frozen=True)
class UpcastContext:
    """What the caller knows about the log being read (the Mission's ``meta.json``).

    Context fills a gap a row leaves; it never overrides a value the row carries. A
    disagreement is a ``MISSION_ID_CONFLICT`` or ``MISSION_SLUG_CONFLICT``.
    """

    missionid: Optional[str] = None
    missionslug: Optional[str] = None
    projectuuid: Optional[str] = None


@dataclass(frozen=True)
class UpcastIssue:
    """One named reason a row was not mapped (or, for a pass-through, why)."""

    code: str
    message: str


class UpcastError(ValueError):
    """Raised in strict use for a row that cannot be mapped."""

    def __init__(self, issue: UpcastIssue) -> None:
        super().__init__(f"{issue.code}: {issue.message}")
        self.issue = issue

    @property
    def code(self) -> str:
        return self.issue.code


@dataclass(frozen=True)
class UpcastResult:
    """The outcome of reading one row.

    ``outcome`` is ``mapped`` (``row`` is a new CloudEvent) or ``passthrough`` (``row``
    is the input, unchanged). ``form`` names the detected legacy form (``none`` when
    nothing matched). ``original`` is the input value and ``original_line`` its bytes
    when it was read from a log line, so a writer can re-emit a legacy row as written.
    """

    outcome: str
    row: Any
    form: str
    original: Any
    original_line: Optional[bytes] = None
    issues: Tuple[UpcastIssue, ...] = field(default_factory=tuple)

    @property
    def mapped(self) -> bool:
        return self.outcome == "mapped"

    @property
    def codes(self) -> Tuple[str, ...]:
        return tuple(issue.code for issue in self.issues)
