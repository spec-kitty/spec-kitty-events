"""Per-PR contract check for the BDD scenarios under ``tests/scenarios`` (stdlib only, no JDK).

Modelled on core's ``scripts/ci/scenario_contract.py`` and
``tests/architectural/test_scenario_contract.py`` (spec-kitty-events#197). It reads the Gherkin
files with a small line-based reader, reads the step definitions of each registered binding, and
checks, without running anything:

* the layout: every feature is ``<area>/<behaviour>/<behaviour>.feature``;
* every feature file parses, holds at least one scenario, and every scenario has steps
  (Background steps are prepended to every scenario under them; outlines are expanded per
  ``Examples`` row, and tags on an ``Examples`` block apply to that block's rows only);
* the tag vocabulary of ``tests/scenarios/README.md`` (a closed set: an unknown tag is an error):
  every scenario carries a well-formed ``@promise:PR-EVENTS-<AREA>-NNN``, promise ids are unique
  across the suite, a ``@wip`` scenario names its owner and start date, and a ``@manual``
  scenario carries a non-empty ``@manual-reason:<text>`` and is never also ``@wip``;
* every step of a gating scenario (neither ``@wip`` nor ``@manual``) matches exactly one step definition in each
  binding the scenario claims (``python`` always, ``jvm`` too when tagged ``@jvm``), keyword
  included (pytest-bdd matches Given, When and Then separately; And and But take the keyword
  before them; a first step must be Given, When or Then). A python definition inside the
  features folder serves only the features below its own folder, as pytest-bdd scopes an
  area's ``conftest.py``. In a ``@wip`` or ``@manual`` scenario such findings are ``info``;
* each area has a ``README.md`` whose ``| Group | Step |`` table is consistent (Arrange and Assume
  rows are ``Given``, Act rows ``When``, Assert rows ``Then``), and a ``PROMISES.md`` that declares
  whether the JVM binding covers the area (``JVM binding: none|partial|full``), gives every
  promise a Binding (``python``, ``python, jvm``, ``@wip`` or ``manual``), and indexes exactly the promises the
  area's scenarios carry, once each. The three binding claims must agree
  (``BINDING_MISMATCH``): a row's Binding follows its scenario's ``@wip``, ``@manual`` and
  ``@jvm`` tags, and
  ``JVM binding`` is ``none``, ``partial`` or ``full`` as the gating rows claim ``jvm``.

Step definitions are read through :data:`STEP_SOURCES`, a registry keyed by binding name. A
binding counts as registered only when it has both a reader there and a step folder to read
(``--steps BINDING=DIR``). Only ``python`` (pytest-bdd decorators, read with :mod:`ast`, never
imported) is registered; until
D3 (#199) or D4 (#270) registers a ``jvm`` source, every ``jvm`` claim -- an ``@jvm`` tag, a
``JVM binding`` other than ``none``, a ``python, jvm`` promise row -- is a
``BAD_BINDING_DECLARATION``.

Findings about a tag point at the line of that tag, not the scenario line. Findings are
de-duplicated and sorted by file, line, code and message.

Known limits of the python step reader: only ``@given``, ``@when``, ``@then`` and ``@step``
decorators are read, with the step text as a string literal, ``parsers.parse("...")`` (fields
``{name}``, ``{name:d}`` and ``{name:w}`` only, matched ignoring case as the parse library does)
or ``parsers.re("...")``. Anything else is an
``UNSUPPORTED_EXPRESSION``, so the steps it binds fail loudly rather than pass silently.

Usage::

    python scripts/ci/scenario_contract.py [--features DIR] [--steps BINDING=DIR ...]

Exit status is 1 when any ``error`` finding is reported, otherwise 0.
"""

from __future__ import annotations

import argparse
import ast
import datetime
import re
import sys
from collections.abc import Callable, Mapping, Sequence
from dataclasses import dataclass, field
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_FEATURES = REPO_ROOT / "tests" / "scenarios"

ERROR = "error"
INFO = "info"

# Gherkin and layout
PARSE_ERROR = "PARSE_ERROR"
NO_SCENARIOS = "NO_SCENARIOS"
NO_STEPS = "NO_STEPS"
NO_EXAMPLES = "NO_EXAMPLES"
NO_FEATURES = "NO_FEATURES"
NO_GATING_SCENARIOS = "NO_GATING_SCENARIOS"
BAD_LAYOUT = "BAD_LAYOUT"
# Tags
UNKNOWN_TAG = "UNKNOWN_TAG"
MISSING_PROMISE = "MISSING_PROMISE"
BAD_PROMISE_ID = "BAD_PROMISE_ID"
DUPLICATE_PROMISE = "DUPLICATE_PROMISE"
WIP_MISSING_OWNER = "WIP_MISSING_OWNER"
WIP_MISSING_SINCE = "WIP_MISSING_SINCE"
BAD_WIP_OWNER = "BAD_WIP_OWNER"
BAD_WIP_SINCE = "BAD_WIP_SINCE"
BAD_DEFECT = "BAD_DEFECT"
MANUAL_MISSING_REASON = "MANUAL_MISSING_REASON"
BAD_MANUAL_REASON = "BAD_MANUAL_REASON"
MANUAL_WITH_WIP = "MANUAL_WITH_WIP"
# Steps
UNBOUND_STEP = "UNBOUND_STEP"
AMBIGUOUS_STEP = "AMBIGUOUS_STEP"
UNSUPPORTED_EXPRESSION = "UNSUPPORTED_EXPRESSION"
NO_STEP_DEFINITIONS = "NO_STEP_DEFINITIONS"
STEP_FILE_UNREADABLE = "STEP_FILE_UNREADABLE"
# Areas
MISSING_AREA_README = "MISSING_AREA_README"
MISSING_STEPS_TABLE = "MISSING_STEPS_TABLE"
STEP_GROUP_MISMATCH = "STEP_GROUP_MISMATCH"
MISSING_BINDING_DECLARATION = "MISSING_BINDING_DECLARATION"
BAD_BINDING_DECLARATION = "BAD_BINDING_DECLARATION"
PROMISE_NOT_INDEXED = "PROMISE_NOT_INDEXED"
BINDING_MISMATCH = "BINDING_MISMATCH"
INDEXED_PROMISE_UNUSED = "INDEXED_PROMISE_UNUSED"

PYTHON = "python"
JVM = "jvm"

PROMISE_PATTERN = r"PR-EVENTS-[A-Z][A-Z0-9]*(?:-[A-Z][A-Z0-9]*)*-[0-9]{3}"
_PROMISE_RE = re.compile(PROMISE_PATTERN)
_HANDLE_RE = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]*")
_ISSUE_RE = re.compile(r"[1-9][0-9]*")
_DATE_RE = re.compile(r"[0-9]{4}-[0-9]{2}-[0-9]{2}")

_STEP_KEYWORDS = ("Given", "When", "Then")
_STEP_LINE_RE = re.compile(r"^(Given|When|Then|And|But|\*)\s+(\S.*)$")
_FEATURE_RE = re.compile(r"^Feature:")
_RULE_RE = re.compile(r"^Rule:")
_BACKGROUND_RE = re.compile(r"^Background:")
_SCENARIO_RE = re.compile(r"^(?:Scenario|Example):\s*(.*)$")
_OUTLINE_RE = re.compile(r"^Scenario (?:Outline|Template):\s*(.*)$")
_EXAMPLES_RE = re.compile(r"^(?:Examples|Scenarios):")
_PLACEHOLDER_RE = re.compile(r"<([^<>]+)>")
_CELL_SPLIT_RE = re.compile(r"(?<!\\)\|")
_DOC_DELIMITERS = ('"""', "```")

# README.md steps table: Group -> the step keyword its rows must start with.
GROUP_KEYWORDS: dict[str, str] = {
    "Arrange": "Given",
    "Assume": "Given",
    "Act": "When",
    "Assert": "Then",
}
# PROMISES.md: the area-level JVM declaration, and the per-promise Binding values.
_JVM_DECLARATION_RE = re.compile(r"^JVM binding:\s*(.*?)\s*$")
JVM_COVERAGE = ("none", "partial", "full")
PROMISE_BINDINGS: dict[str, tuple[str, ...]] = {
    "python": (PYTHON,),
    "python, jvm": (PYTHON, JVM),
    "@wip": (),
    "manual": (),
}
# Bindings of rows whose scenario does not gate: they never count towards ``JVM binding``.
NON_GATING_BINDINGS = frozenset({"@wip", "manual"})


@dataclass(frozen=True, order=True)
class Problem:
    """One finding. ``code`` is stable; ``severity`` is ``error`` or ``info``."""

    file: str
    line: int
    code: str
    severity: str
    message: str


@dataclass(frozen=True)
class Tag:
    name: str
    line: int


@dataclass(frozen=True)
class Step:
    keyword: str  # Given, When, Then, or * for a bare bullet
    text: str
    line: int


@dataclass(frozen=True)
class Scenario:
    file: str
    line: int
    name: str
    keyword: str
    tags: tuple[Tag, ...]
    steps: tuple[Step, ...]


@dataclass(frozen=True)
class StepDefinition:
    binding: str
    keyword: str  # Given, When, Then, or * (pytest-bdd's @step matches any keyword)
    expression: str
    source: str
    regex: re.Pattern[str]
    path: Path | None = None  # the defining file, for pytest-bdd's conftest scoping


class UnsupportedExpressionError(ValueError):
    """A step expression this check does not model."""


def _problem(
    code: str, file: str | Path, line: int, message: str, severity: str = ERROR
) -> Problem:
    return Problem(str(file), line, code, severity, message)


# ---------------------------------------------------------------------------
# Gherkin reader (adapted from core's; tags keep their line numbers)
# ---------------------------------------------------------------------------


@dataclass
class _Block:
    kind: str  # "background" | "scenario" | "outline"
    name: str
    line: int
    tags: tuple[Tag, ...] = ()
    steps: list[Step] = field(default_factory=list)
    tables: list[list[list[str]]] = field(default_factory=list)
    table_tags: list[tuple[Tag, ...]] = field(default_factory=list)
    in_examples: bool = False
    last_keyword: str | None = None


def _split_cells(line: str) -> list[str]:
    inner = line.strip()
    inner = inner[1:] if inner.startswith("|") else inner
    inner = inner[:-1] if inner.endswith("|") and not inner.endswith("\\|") else inner
    return [cell.strip().replace("\\|", "|") for cell in _CELL_SPLIT_RE.split(inner)]


class _Reader:
    """Line-based Gherkin subset reader. One instance reads one file."""

    def __init__(self, file: str) -> None:
        self.file = file
        self.problems: list[Problem] = []
        self.scenarios: list[Scenario] = []
        self.feature_seen = False
        self.feature_line = 0
        self.feature_tags: tuple[Tag, ...] = ()
        self.rule_tags: tuple[Tag, ...] = ()
        self.feature_background: list[Step] = []
        self.rule_background: list[Step] = []
        self.in_rule = False
        self.pending: list[Tag] = []
        self.current: _Block | None = None
        self.doc_delimiter: str | None = None
        self.doc_line = 0

    def error(self, code: str, line: int, message: str) -> None:
        self.problems.append(_problem(code, self.file, line, message))

    def _take_pending(self) -> tuple[Tag, ...]:
        tags = tuple(self.pending)
        self.pending = []
        return tags

    def _reject_pending(self, line: int) -> None:
        if self.pending:
            self.error(
                PARSE_ERROR, line, "tags must be followed by Feature, Rule, Scenario or Examples"
            )
            self.pending = []

    def read(self, text: str) -> None:
        for number, raw in enumerate(text.splitlines(), start=1):
            self.line(number, raw.strip())
        self._finish()

    def _finish(self) -> None:
        if self.doc_delimiter is not None:
            self.error(PARSE_ERROR, self.doc_line, "doc string is never closed")
        self._reject_pending(self.doc_line or 1)
        self._close_block()
        if not self.feature_seen:
            self.error(PARSE_ERROR, 1, "no Feature line found")
        elif not self.scenarios:
            self.error(
                NO_SCENARIOS,
                self.feature_line,
                "the feature has no Scenario, Example or Scenario Outline",
            )

    def line(self, number: int, stripped: str) -> None:
        if self.doc_delimiter is not None:
            if stripped.startswith(self.doc_delimiter):
                self.doc_delimiter = None
            return
        if not stripped or stripped.startswith("#"):
            return
        self._classify(stripped)(number, stripped)

    def _classify(self, stripped: str) -> Callable[[int, str], None]:
        if stripped.startswith("@"):
            return self._on_tags
        if stripped.startswith("|"):
            return self._on_row
        if stripped.startswith(_DOC_DELIMITERS):
            return self._on_doc_string
        structural: list[tuple[re.Pattern[str], Callable[[int, str], None]]] = [
            (_FEATURE_RE, self._on_feature),
            (_RULE_RE, self._on_rule),
            (_BACKGROUND_RE, self._on_background),
            (_OUTLINE_RE, self._on_outline),
            (_SCENARIO_RE, self._on_scenario),
            (_EXAMPLES_RE, self._on_examples),
        ]
        for pattern, handler in structural:
            if pattern.match(stripped):
                return handler
        if _STEP_LINE_RE.match(stripped):
            return self._on_step
        return self._on_text

    def _on_tags(self, number: int, stripped: str) -> None:
        for token in stripped.split():
            if token.startswith("#"):
                break
            if token.startswith("@") and len(token) > 1:
                self.pending.append(Tag(token[1:], number))
            else:
                self.error(PARSE_ERROR, number, f"malformed tag {token!r}")

    def _on_feature(self, number: int, _stripped: str) -> None:
        if self.feature_seen:
            self.error(PARSE_ERROR, number, "a second Feature line in one file")
            self._reject_pending(number)
            return
        self.feature_seen = True
        self.feature_line = number
        self.feature_tags = self._take_pending()

    def _require_feature(self, number: int) -> bool:
        if not self.feature_seen:
            self.error(PARSE_ERROR, number, "content before the Feature line")
            self.pending = []
        return self.feature_seen

    def _on_rule(self, number: int, _stripped: str) -> None:
        if not self._require_feature(number):
            return
        self._close_block()
        self.rule_tags = self._take_pending()
        self.rule_background = []
        self.in_rule = True

    def _on_background(self, number: int, _stripped: str) -> None:
        if not self._require_feature(number):
            return
        self._close_block()
        self._reject_pending(number)
        self.current = _Block("background", "Background", number)

    def _open_scenario(self, kind: str, number: int, stripped: str) -> None:
        if not self._require_feature(number):
            return
        self._close_block()
        name = stripped.split(":", 1)[1].strip()
        tags = self.feature_tags + self.rule_tags + self._take_pending()
        self.current = _Block(kind, name, number, tags)

    def _on_scenario(self, number: int, stripped: str) -> None:
        self._open_scenario("scenario", number, stripped)

    def _on_outline(self, number: int, stripped: str) -> None:
        self._open_scenario("outline", number, stripped)

    def _on_examples(self, number: int, _stripped: str) -> None:
        tags = self._take_pending()
        block = self.current
        if block is None or block.kind != "outline":
            self.error(PARSE_ERROR, number, "Examples outside a Scenario Outline")
            return
        block.in_examples = True
        block.tables.append([])
        block.table_tags.append(tags)

    def _on_step(self, number: int, stripped: str) -> None:
        self._reject_pending(number)
        block = self.current
        if block is None and self.feature_seen:
            return  # directly under Feature: or Rule: this is description text, not a step
        if block is None or block.in_examples:
            self.error(
                PARSE_ERROR, number, "step outside a Scenario, Scenario Outline or Background"
            )
            return
        match = _STEP_LINE_RE.match(stripped)
        assert match is not None  # _classify only routes matching lines here
        word, text = match.group(1), match.group(2)
        if word in _STEP_KEYWORDS:
            block.last_keyword = word
        elif block.last_keyword is None:
            # pytest-bdd refuses this too: the first step must be Given, When or Then.
            self.error(PARSE_ERROR, number, f"{word!r} step has no preceding Given, When or Then")
            return
        assert block.last_keyword is not None
        block.steps.append(Step(block.last_keyword, text, number))

    def _on_row(self, number: int, stripped: str) -> None:
        block = self.current
        if block is not None and block.in_examples:
            block.tables[-1].append(_split_cells(stripped))
            return
        if block is None or not block.steps:
            self.error(PARSE_ERROR, number, "table row without a preceding step")

    def _on_doc_string(self, number: int, stripped: str) -> None:
        block = self.current
        if block is None or block.in_examples or not block.steps:
            self.error(PARSE_ERROR, number, "doc string without a preceding step")
            return
        self.doc_delimiter = stripped[:3]
        self.doc_line = number

    def _on_text(self, number: int, stripped: str) -> None:
        self._reject_pending(number)
        block = self.current
        description_allowed = self.feature_seen and (block is None or self._in_description(block))
        if not description_allowed:
            self.error(PARSE_ERROR, number, f"unparseable line {stripped!r}")

    @staticmethod
    def _in_description(block: _Block) -> bool:
        if block.in_examples:
            return not block.tables[-1]
        return not block.steps

    def _close_block(self) -> None:
        block, self.current = self.current, None
        if block is None:
            return
        if block.kind == "background":
            target = self.rule_background if self.in_rule else self.feature_background
            target.extend(block.steps)
            return
        if not block.steps:
            self.error(NO_STEPS, block.line, f"scenario {block.name!r} has no steps")
        base = tuple(self.feature_background + self.rule_background)
        if block.kind == "scenario":
            self._emit(block, "Scenario", base + tuple(block.steps))
        else:
            self._emit_outline(block, base)

    def _emit(
        self, block: _Block, keyword: str, steps: tuple[Step, ...], extra: tuple[Tag, ...] = ()
    ) -> None:
        self.scenarios.append(
            Scenario(self.file, block.line, block.name, keyword, block.tags + extra, steps)
        )

    def _emit_outline(self, block: _Block, base: tuple[Step, ...]) -> None:
        rows = self._outline_rows(block)
        if not rows:
            self.error(
                NO_EXAMPLES, block.line, f"scenario outline {block.name!r} has no Examples rows"
            )
            self._emit(block, "Scenario Outline", base + tuple(block.steps))
            return
        for values, tags in rows:
            expanded = tuple(
                Step(s.keyword, _substitute(s.text, values), s.line) for s in block.steps
            )
            self._emit(block, "Scenario Outline", base + expanded, tags)

    def _outline_rows(self, block: _Block) -> list[tuple[dict[str, str], tuple[Tag, ...]]]:
        rows: list[tuple[dict[str, str], tuple[Tag, ...]]] = []
        for table, tags in zip(block.tables, block.table_tags):
            if not table:
                self.error(PARSE_ERROR, block.line, "Examples without a header row")
                continue
            header = table[0]
            for cells in table[1:]:
                if len(cells) != len(header):
                    self.error(
                        PARSE_ERROR,
                        block.line,
                        f"Examples row has {len(cells)} cells, header has {len(header)}",
                    )
                    continue
                rows.append((dict(zip(header, cells)), tags))
        return rows


def _substitute(text: str, values: Mapping[str, str]) -> str:
    return _PLACEHOLDER_RE.sub(lambda m: values.get(m.group(1), m.group(0)), text)


def parse_feature_text(text: str, file: str) -> tuple[list[Scenario], list[Problem]]:
    """Parse Gherkin ``text``; outlines are expanded and Background steps prepended."""
    reader = _Reader(file)
    reader.read(text)
    return reader.scenarios, reader.problems


def parse_feature(path: Path) -> tuple[list[Scenario], list[Problem]]:
    try:
        text = path.read_text(encoding="utf-8")
    except (OSError, UnicodeDecodeError) as exc:
        return [], [_problem(PARSE_ERROR, path, 1, f"cannot read file: {exc}")]
    return parse_feature_text(text, str(path))


# ---------------------------------------------------------------------------
# Tag vocabulary
# ---------------------------------------------------------------------------


def _valid_date(value: str) -> bool:
    if not _DATE_RE.fullmatch(value):
        return False
    try:
        datetime.date.fromisoformat(value)
    except ValueError:
        return False
    return True


def _matches(pattern: re.Pattern[str]) -> Callable[[str], bool]:
    return lambda value: pattern.fullmatch(value) is not None


# prefix -> (value check, problem code, expectation shown to the author)
_VALUE_RULES: dict[str, tuple[Callable[[str], bool], str, str]] = {
    "promise": (_matches(_PROMISE_RE), BAD_PROMISE_ID, f"an id matching {PROMISE_PATTERN}"),
    "wip-owner": (_matches(_HANDLE_RE), BAD_WIP_OWNER, f"a handle matching {_HANDLE_RE.pattern}"),
    "wip-since": (_valid_date, BAD_WIP_SINCE, "a valid date as YYYY-MM-DD"),
    "defect": (_matches(_ISSUE_RE), BAD_DEFECT, "a positive integer issue number"),
    "manual-reason": (bool, BAD_MANUAL_REASON, "a non-empty reason"),
}
_PLAIN_TAGS = frozenset({"wip", "manual", JVM})


def is_manual(scenario: Scenario) -> bool:
    """``@manual``: a specified promise this repo's CI cannot run (a host, OS, peer or
    operator-run step it lacks). Unlike ``@wip`` it is not unfinished work."""
    return any(tag.name == "manual" for tag in scenario.tags)


def is_wip(scenario: Scenario) -> bool:
    return any(tag.name == "wip" for tag in scenario.tags)


def is_gating(scenario: Scenario) -> bool:
    """A scenario gates unless it is ``@wip`` or ``@manual``."""
    return not (is_wip(scenario) or is_manual(scenario))


def _values(scenario: Scenario, prefix: str) -> list[tuple[str, Tag]]:
    found = []
    for tag in scenario.tags:
        name, colon, value = tag.name.partition(":")
        if colon and name == prefix:
            found.append((value, tag))
    return found


def _check_one_tag(scenario: Scenario, tag: Tag) -> Problem | None:
    prefix, colon, value = tag.name.partition(":")
    if not colon:
        if tag.name in _PLAIN_TAGS:
            return None
        return _problem(UNKNOWN_TAG, scenario.file, tag.line, f"unknown tag @{tag.name}")
    rule = _VALUE_RULES.get(prefix)
    if rule is None:
        return _problem(UNKNOWN_TAG, scenario.file, tag.line, f"unknown prefixed tag @{tag.name}")
    check, code, expectation = rule
    if check(value):
        return None
    return _problem(code, scenario.file, tag.line, f"@{tag.name}: expected {expectation}")


def _check_promise_presence(scenario: Scenario) -> list[Problem]:
    promises = _values(scenario, "promise")
    if not promises:
        return [
            _problem(
                MISSING_PROMISE,
                scenario.file,
                scenario.line,
                f"scenario {scenario.name!r} has no @promise: tag",
            )
        ]
    problems: list[Problem] = []
    seen: set[str] = set()
    for value, tag in promises:
        if value in seen:
            problems.append(
                _problem(
                    DUPLICATE_PROMISE, scenario.file, tag.line, f"@promise:{value} is repeated"
                )
            )
        seen.add(value)
    return problems


def _check_wip(scenario: Scenario) -> list[Problem]:
    wip = next((tag for tag in scenario.tags if tag.name == "wip"), None)
    if wip is None:
        return []
    problems: list[Problem] = []
    if not _values(scenario, "wip-owner"):
        problems.append(
            _problem(WIP_MISSING_OWNER, scenario.file, wip.line, "@wip needs @wip-owner:<handle>")
        )
    if not _values(scenario, "wip-since"):
        problems.append(
            _problem(
                WIP_MISSING_SINCE, scenario.file, wip.line, "@wip needs @wip-since:<YYYY-MM-DD>"
            )
        )
    return problems


def _check_manual(scenario: Scenario) -> list[Problem]:
    manual = next((tag for tag in scenario.tags if tag.name == "manual"), None)
    if manual is None:
        return []
    problems: list[Problem] = []
    if not _values(scenario, "manual-reason"):
        problems.append(
            _problem(
                MANUAL_MISSING_REASON,
                scenario.file,
                manual.line,
                "@manual needs a non-empty @manual-reason:<text>",
            )
        )
    if is_wip(scenario):
        problems.append(
            _problem(
                MANUAL_WITH_WIP,
                scenario.file,
                manual.line,
                "@manual and @wip do not combine: @manual is a finished promise CI cannot run, "
                "@wip is unfinished work",
            )
        )
    return problems


def _check_binding_tags(scenario: Scenario, registered: Sequence[str]) -> list[Problem]:
    return [
        _problem(
            BAD_BINDING_DECLARATION,
            scenario.file,
            tag.line,
            f"@{JVM} claims a JVM binding, but no {JVM!r} step source is registered "
            f"(registered: {', '.join(registered)})",
        )
        for tag in scenario.tags
        if tag.name == JVM and JVM not in registered
    ]


def validate_tags(scenario: Scenario, registered: Sequence[str] = (PYTHON,)) -> list[Problem]:
    """Check ``scenario.tags`` (own plus inherited) against the documented vocabulary."""
    problems = [p for p in (_check_one_tag(scenario, t) for t in scenario.tags) if p is not None]
    return (
        problems
        + _check_promise_presence(scenario)
        + _check_wip(scenario)
        + _check_manual(scenario)
        + _check_binding_tags(scenario, registered)
    )


def check_unique_promises(scenarios: Sequence[Scenario]) -> list[Problem]:
    """A promise id tags one scenario (an outline counts once) across the whole suite.

    Every expanded row is read, so a promise on an ``Examples:`` block is checked too.
    """
    owner: dict[str, tuple[str, int]] = {}
    problems: list[Problem] = []
    for scenario in sorted(scenarios, key=lambda s: (s.file, s.line)):
        for value, tag in _values(scenario, "promise"):
            first = owner.setdefault(value, (scenario.file, scenario.line))
            if first != (scenario.file, scenario.line):
                problems.append(
                    _problem(
                        DUPLICATE_PROMISE,
                        scenario.file,
                        tag.line,
                        f"@promise:{value} already tags the scenario at {first[0]}:{first[1]}",
                    )
                )
    return problems


# ---------------------------------------------------------------------------
# Step definitions: a registry of sources keyed by binding name
# ---------------------------------------------------------------------------

_DECORATOR_KEYWORDS = {"given": "Given", "when": "When", "then": "Then", "step": "*"}
_PARSE_FIELD_PATTERNS = {"": r".+?", "d": r"[-+]?\d+", "w": r"\w+"}


def _callable_name(node: ast.expr) -> str | None:
    if isinstance(node, ast.Name):
        return node.id
    if isinstance(node, ast.Attribute):
        return node.attr
    return None


def parse_format_to_regex(fmt: str) -> re.Pattern[str]:
    """Translate a ``parsers.parse`` format to a regex; use ``fullmatch`` on step text."""
    parts: list[str] = []
    index = 0
    while index < len(fmt):
        if fmt.startswith("{{", index) or fmt.startswith("}}", index):
            parts.append(re.escape(fmt[index]))
            index += 2
            continue
        char = fmt[index]
        if char == "{":
            end = fmt.find("}", index)
            if end == -1:
                raise UnsupportedExpressionError(f"unterminated '{{' in step expression {fmt!r}")
            _name, _colon, spec = fmt[index + 1 : end].partition(":")
            pattern = _PARSE_FIELD_PATTERNS.get(spec)
            if pattern is None:
                raise UnsupportedExpressionError(
                    f"format spec {spec!r} in step expression {fmt!r} is not supported; use {{name}}, {{name:d}} or {{name:w}}"
                )
            parts.append(f"({pattern})")
            index = end + 1
        elif char == "}":
            raise UnsupportedExpressionError(f"unmatched '}}' in step expression {fmt!r}")
        else:
            parts.append(re.escape(char))
            index += 1
    # The parse library behind parsers.parse matches case-insensitively and across newlines.
    return re.compile("".join(parts), re.IGNORECASE | re.DOTALL)


def _expression(node: ast.expr) -> tuple[str, re.Pattern[str]]:
    """The step text and its regex for a decorator's first argument."""
    if isinstance(node, ast.Constant) and isinstance(node.value, str):
        return node.value, re.compile(re.escape(node.value))
    if isinstance(node, ast.Call) and len(node.args) == 1:
        arg = node.args[0]
        kind = _callable_name(node.func)
        if isinstance(arg, ast.Constant) and isinstance(arg.value, str):
            if kind == "parse":
                return arg.value, parse_format_to_regex(arg.value)
            if kind == "re":
                try:
                    return arg.value, re.compile(arg.value)
                except re.error as exc:
                    raise UnsupportedExpressionError(
                        f"invalid regular expression {arg.value!r}: {exc}"
                    ) from exc
    raise UnsupportedExpressionError(
        f"step expression {ast.unparse(node)!r} is not supported; use a string literal, "
        'parsers.parse("...") or parsers.re("...")'
    )


def _decorator_step(node: ast.expr) -> tuple[str, ast.expr | None] | None:
    if not isinstance(node, ast.Call):
        return None
    keyword = _DECORATOR_KEYWORDS.get(_callable_name(node.func) or "")
    if keyword is None:
        return None
    if node.args:
        return keyword, node.args[0]
    named = next((kw.value for kw in node.keywords if kw.arg == "name"), None)
    return keyword, named


def _python_definitions_in(path: Path) -> tuple[list[StepDefinition], list[Problem], bool]:
    try:
        tree = ast.parse(path.read_text(encoding="utf-8"), filename=str(path))
    except (OSError, UnicodeDecodeError, SyntaxError) as exc:
        return (
            [],
            [
                _problem(
                    STEP_FILE_UNREADABLE,
                    path,
                    getattr(exc, "lineno", None) or 1,
                    f"cannot read: {exc}",
                )
            ],
            False,
        )
    defs: list[StepDefinition] = []
    problems: list[Problem] = []
    found = False
    for node in ast.walk(tree):
        if not isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)):
            continue
        for decorator in node.decorator_list:
            step = _decorator_step(decorator)
            if step is None:
                continue
            found = True
            keyword, argument = step
            try:
                if argument is None:
                    raise UnsupportedExpressionError("step decorator without a step expression")
                expression, regex = _expression(argument)
            except UnsupportedExpressionError as exc:
                problems.append(_problem(UNSUPPORTED_EXPRESSION, path, decorator.lineno, str(exc)))
                continue
            defs.append(
                StepDefinition(
                    PYTHON, keyword, expression, f"{path.name}:{decorator.lineno}", regex, path
                )
            )
    return defs, problems, found


def load_python_step_definitions(steps_dir: Path) -> tuple[list[StepDefinition], list[Problem]]:
    """Read pytest-bdd step decorators from every ``*.py`` under ``steps_dir`` (never imported)."""
    defs: list[StepDefinition] = []
    problems: list[Problem] = []
    found_any = False
    for path in sorted(p for p in steps_dir.rglob("*.py") if "__pycache__" not in p.parts):
        file_defs, file_problems, found = _python_definitions_in(path)
        defs += file_defs
        problems += file_problems
        found_any = found_any or found
    if not found_any:
        problems.append(
            _problem(
                NO_STEP_DEFINITIONS, steps_dir, 1, "no @given/@when/@then step definitions found"
            )
        )
    return defs, problems


StepSource = Callable[[Path], tuple[list[StepDefinition], list[Problem]]]

#: Step-definition readers keyed by binding name. D3 (#199) / D4 (#270) add ``jvm``.
STEP_SOURCES: dict[str, StepSource] = {PYTHON: load_python_step_definitions}


def claimed_bindings(scenario: Scenario) -> tuple[str, ...]:
    """``python`` binds every scenario; an ``@jvm`` scenario is bound by the JVM as well."""
    return (PYTHON, JVM) if any(tag.name == JVM for tag in scenario.tags) else (PYTHON,)


def match_steps(
    scenario: Scenario,
    definitions: Sequence[StepDefinition],
    registered: Sequence[str] = (PYTHON,),
) -> list[Problem]:
    """Report steps that match no definition, or more than one, in each binding the scenario
    claims and that has a step source (an unregistered claim is reported by the tag rules)."""
    problems: list[Problem] = []
    for binding in claimed_bindings(scenario):
        if binding in registered:
            problems += _match_binding(scenario, definitions, binding)
    return problems


def _match_binding(
    scenario: Scenario, definitions: Sequence[StepDefinition], binding: str
) -> list[Problem]:
    severity = ERROR if is_gating(scenario) else INFO
    problems: list[Problem] = []
    for step in scenario.steps:
        hits = [
            d
            for d in definitions
            if d.binding == binding
            and (d.keyword == "*" or step.keyword in ("*", d.keyword))
            and d.regex.fullmatch(step.text)
        ]
        if len(hits) == 1:
            continue
        shown = (
            f"{step.keyword} {step.text}"
            if binding == PYTHON
            else f"{step.keyword} {step.text} ({binding})"
        )
        if not hits:
            problems.append(
                _problem(
                    UNBOUND_STEP,
                    scenario.file,
                    step.line,
                    f"no step definition matches {shown!r}",
                    severity,
                )
            )
        else:
            sources = ", ".join(d.source for d in hits)
            problems.append(
                _problem(
                    AMBIGUOUS_STEP,
                    scenario.file,
                    step.line,
                    f"{shown!r} matches {len(hits)} step definitions: {sources}",
                    severity,
                )
            )
    return problems


# ---------------------------------------------------------------------------
# Area documents: README.md (steps table) and PROMISES.md (bindings, promise index)
# ---------------------------------------------------------------------------


def _tables(text: str) -> list[list[tuple[int, list[str]]]]:
    """Every Markdown table as ``(line, cells)`` rows: header first, separator row dropped."""
    tables: list[list[tuple[int, list[str]]]] = []
    current: list[tuple[int, list[str]]] = []
    for number, raw in enumerate(text.splitlines(), start=1):
        stripped = raw.strip()
        if stripped.startswith("|"):
            cells = _split_cells(stripped)
            if not all(re.fullmatch(r":?-{3,}:?", cell) for cell in cells):
                current.append((number, cells))
            continue
        if current:
            tables.append(current)
            current = []
    if current:
        tables.append(current)
    return tables


def _unquote(cell: str) -> str:
    return cell.strip().strip("`").strip()


def check_area_readme(area: Path) -> list[Problem]:
    readme = area / "README.md"
    if not readme.is_file():
        return [_problem(MISSING_AREA_README, readme, 1, f"area {area.name!r} has no README.md")]
    text = readme.read_text(encoding="utf-8")
    steps_tables = [t for t in _tables(text) if [c.strip() for c in t[0][1]] == ["Group", "Step"]]
    if not steps_tables:
        return [
            _problem(
                MISSING_STEPS_TABLE,
                readme,
                1,
                "README.md has no '| Group | Step |' table of available steps",
            )
        ]
    problems: list[Problem] = []
    for table in steps_tables:
        for number, cells in table[1:]:
            group, step = cells[0].strip(), _unquote(cells[1]) if len(cells) > 1 else ""
            expected = GROUP_KEYWORDS.get(group)
            keyword = step.split(" ", 1)[0]
            if expected is None:
                problems.append(
                    _problem(
                        STEP_GROUP_MISMATCH,
                        readme,
                        number,
                        f"unknown Group {group!r}; use {', '.join(GROUP_KEYWORDS)}",
                    )
                )
            elif keyword != expected:
                problems.append(
                    _problem(
                        STEP_GROUP_MISMATCH,
                        readme,
                        number,
                        f"{group} rows are {expected} steps, not {keyword!r}",
                    )
                )
    return problems


@dataclass(frozen=True)
class AreaPromises:
    present: bool
    promises: dict[str, int]  # id -> line in PROMISES.md
    problems: list[Problem]
    bindings: dict[str, str] = field(default_factory=dict)  # id -> a valid Binding value
    jvm_declaration: tuple[int, str] | None = None  # (line, a valid JVM_COVERAGE value)


def check_area_promises(area: Path, registered: Sequence[str]) -> AreaPromises:
    path = area / "PROMISES.md"
    if not path.is_file():
        return AreaPromises(
            False,
            {},
            [
                _problem(
                    MISSING_BINDING_DECLARATION, path, 1, f"area {area.name!r} has no PROMISES.md"
                )
            ],
        )
    text = path.read_text(encoding="utf-8")
    problems: list[Problem] = []
    declarations = [
        (number, m.group(1))
        for number, raw in enumerate(text.splitlines(), start=1)
        if (m := _JVM_DECLARATION_RE.match(raw.strip()))
    ]
    if not declarations:
        problems.append(
            _problem(
                MISSING_BINDING_DECLARATION,
                path,
                1,
                f"PROMISES.md does not declare whether the JVM binding covers the area: add 'JVM binding: {'|'.join(JVM_COVERAGE)}'",
            )
        )
    jvm_declaration: tuple[int, str] | None = None
    for number, value in declarations:
        if value in JVM_COVERAGE and jvm_declaration is None:
            jvm_declaration = (number, value)
        if value not in JVM_COVERAGE:
            problems.append(
                _problem(
                    BAD_BINDING_DECLARATION,
                    path,
                    number,
                    f"JVM binding {value!r}: expected one of {', '.join(JVM_COVERAGE)}",
                )
            )
        elif value != "none" and JVM not in registered:
            problems.append(
                _problem(
                    BAD_BINDING_DECLARATION,
                    path,
                    number,
                    f"JVM binding {value!r}, but no {JVM!r} step source is registered",
                )
            )
    promises: dict[str, int] = {}
    bindings: dict[str, str] = {}
    for table in _tables(text):
        header = [c.strip() for c in table[0][1]]
        if not header or header[0] != "Promise" or "Binding" not in header:
            continue
        binding_column = header.index("Binding")
        for number, cells in table[1:]:
            promise = _unquote(cells[0])
            if not _PROMISE_RE.fullmatch(promise):
                problems.append(
                    _problem(
                        BAD_PROMISE_ID,
                        path,
                        number,
                        f"{promise}: expected an id matching {PROMISE_PATTERN}",
                    )
                )
                continue
            if promise in promises:
                problems.append(
                    _problem(
                        DUPLICATE_PROMISE,
                        path,
                        number,
                        f"{promise} already has a row at line {promises[promise]}",
                    )
                )
                continue
            promises[promise] = number
            binding = cells[binding_column].strip() if binding_column < len(cells) else ""
            claimed = PROMISE_BINDINGS.get(binding)
            if claimed is not None:
                bindings.setdefault(promise, binding)
            if claimed is None:
                problems.append(
                    _problem(
                        BAD_BINDING_DECLARATION,
                        path,
                        number,
                        f"{promise}: Binding {binding!r}; expected one of {', '.join(PROMISE_BINDINGS)}",
                    )
                )
            elif any(name not in registered for name in claimed):
                missing = ", ".join(name for name in claimed if name not in registered)
                problems.append(
                    _problem(
                        BAD_BINDING_DECLARATION,
                        path,
                        number,
                        f"{promise}: Binding {binding!r}, but no {missing} step source is registered",
                    )
                )
    return AreaPromises(True, promises, problems, bindings, jvm_declaration)


def check_promise_index(
    area: Path, index: AreaPromises, scenarios: Sequence[Scenario]
) -> list[Problem]:
    """Scenario promises and PROMISES.md rows match one to one (skipped when PROMISES.md is absent)."""
    if not index.present:
        return []
    problems: list[Problem] = []
    used: set[str] = set()
    for scenario in scenarios:
        for value, tag in _values(scenario, "promise"):
            used.add(value)
            if value not in index.promises and _PROMISE_RE.fullmatch(value):
                problems.append(
                    _problem(
                        PROMISE_NOT_INDEXED,
                        scenario.file,
                        tag.line,
                        f"@promise:{value} has no row in {area.name}/PROMISES.md",
                    )
                )
    problems += _check_binding_consistency(area, index, scenarios)
    for promise, number in index.promises.items():
        if promise not in used:
            problems.append(
                _problem(
                    INDEXED_PROMISE_UNUSED,
                    area / "PROMISES.md",
                    number,
                    f"{promise} tags no scenario in {area.name}/",
                )
            )
    return problems


def _expected_binding(scenario: Scenario) -> str:
    if is_wip(scenario):
        return "@wip"
    if is_manual(scenario):
        return "manual"
    return "python, jvm" if JVM in claimed_bindings(scenario) else "python"


def _check_binding_consistency(
    area: Path, index: AreaPromises, scenarios: Sequence[Scenario]
) -> list[Problem]:
    """The three binding claims agree: each promise row's Binding matches its scenario's tags
    (``@wip``, ``@manual``, ``@jvm``), and the area's ``JVM binding`` matches its rows."""
    path = area / "PROMISES.md"
    problems: list[Problem] = []
    for scenario in scenarios:
        expected = _expected_binding(scenario)
        for value, _tag in _values(scenario, "promise"):
            declared = index.bindings.get(value)
            if declared is not None and declared != expected:
                problems.append(
                    _problem(
                        BINDING_MISMATCH,
                        path,
                        index.promises[value],
                        f"{value}: Binding {declared!r}, but its scenario's tags make it {expected!r}",
                    )
                )
    if index.jvm_declaration is not None:
        line, declared = index.jvm_declaration
        gating = [b for b in index.bindings.values() if b not in NON_GATING_BINDINGS]
        jvm_rows = sum(1 for b in gating if JVM in PROMISE_BINDINGS[b])
        if jvm_rows == 0:
            actual = "none"
        elif jvm_rows == len(gating):
            actual = "full"
        else:
            actual = "partial"
        if declared != actual:
            problems.append(
                _problem(
                    BINDING_MISMATCH,
                    path,
                    line,
                    f"JVM binding {declared!r}, but the promise rows make it {actual!r}",
                )
            )
    return problems


# ---------------------------------------------------------------------------
# Checks, output and entry point
# ---------------------------------------------------------------------------


def sort_problems(problems: Sequence[Problem]) -> list[Problem]:
    """De-duplicate and sort by file, line, code, severity and message (stable output)."""
    return sorted(set(problems))


def load_definitions(
    step_dirs: Mapping[str, Path], sources: Mapping[str, StepSource] = STEP_SOURCES
) -> tuple[list[StepDefinition], list[Problem]]:
    defs: list[StepDefinition] = []
    problems: list[Problem] = []
    for binding, directory in sorted(step_dirs.items()):
        reader = sources.get(binding)
        if reader is None:
            problems.append(
                _problem(
                    BAD_BINDING_DECLARATION,
                    directory,
                    1,
                    f"no step source is registered for binding {binding!r}",
                )
            )
            continue
        found, found_problems = reader(directory)
        defs += found
        problems += found_problems
    return defs, problems


def _check_scenarios(
    scenarios: Sequence[Scenario], definitions: Sequence[StepDefinition], registered: Sequence[str]
) -> list[Problem]:
    problems: list[Problem] = []
    for scenario in scenarios:
        problems += validate_tags(scenario, registered)
        problems += match_steps(scenario, definitions, registered)
    return problems


def check_feature_file(
    path: Path, definitions: Sequence[StepDefinition], registered: Sequence[str] = (PYTHON,)
) -> list[Problem]:
    """Parse, tag-check and step-match one ``.feature`` file (no area or layout rules)."""
    scenarios, problems = parse_feature(path)
    return sort_problems(
        problems
        + _check_scenarios(scenarios, definitions, registered)
        + check_unique_promises(scenarios)
    )


def visible_definitions(
    definitions: Sequence[StepDefinition], features_dir: Path, feature: Path
) -> list[StepDefinition]:
    """The definitions pytest-bdd lets ``feature`` use: one defined inside ``features_dir`` is
    visible only below its own folder (an area's ``conftest.py`` serves that area, the root
    ``conftest.py`` every area); one defined outside it, in a separate step folder, serves all."""
    root = features_dir.resolve()
    folder = feature.resolve().parent
    visible = []
    for definition in definitions:
        home = definition.path.resolve().parent if definition.path is not None else None
        if home is None or not home.is_relative_to(root) or folder.is_relative_to(home):
            visible.append(definition)
    return visible


def _check_layout(features_dir: Path, path: Path) -> list[Problem]:
    parts = path.relative_to(features_dir).parts
    if len(parts) == 3 and parts[2] == f"{parts[1]}.feature":
        return []
    return [
        _problem(BAD_LAYOUT, path, 1, "a feature lives at <area>/<behaviour>/<behaviour>.feature")
    ]


def check_tree(
    features_dir: Path,
    step_dirs: Mapping[str, Path] | None = None,
    sources: Mapping[str, StepSource] = STEP_SOURCES,
) -> list[Problem]:
    """Check every area and ``.feature`` file under ``features_dir``.

    ``step_dirs`` maps a binding name to the directory its step source reads; by default the
    ``python`` step definitions are read from ``features_dir`` itself. A binding counts as
    registered only when ``sources`` has a reader for it *and* ``step_dirs`` names where its
    step definitions live, so a ``jvm`` claim is never accepted without reading JVM steps.
    """
    step_dirs = dict(step_dirs) if step_dirs is not None else {PYTHON: features_dir}
    registered = sorted(binding for binding in step_dirs if binding in sources)
    definitions, problems = load_definitions(step_dirs, sources)
    files = sorted(features_dir.rglob("*.feature"))
    if not files:
        problems.append(_problem(NO_FEATURES, features_dir, 1, "no .feature files found"))
    all_scenarios: list[Scenario] = []
    by_area: dict[Path, list[Scenario]] = {}
    for path in files:
        problems += _check_layout(features_dir, path)
        scenarios, parse_problems = parse_feature(path)
        visible = visible_definitions(definitions, features_dir, path)
        problems += parse_problems + _check_scenarios(scenarios, visible, registered)
        all_scenarios += scenarios
        area = features_dir / path.relative_to(features_dir).parts[0]
        if area.is_dir():
            by_area.setdefault(area, []).extend(scenarios)
    for area in sorted(by_area):
        problems += check_area_readme(area)
        index = check_area_promises(area, registered)
        problems += index.problems + check_promise_index(area, index, by_area[area])
    problems += check_unique_promises(all_scenarios)
    if files and not any(is_gating(s) for s in all_scenarios):
        problems.append(
            _problem(NO_GATING_SCENARIOS, features_dir, 1, "no scenario gates (all are @wip or @manual)")
        )
    return sort_problems(problems)


def format_problems(problems: Sequence[Problem]) -> str:
    return "\n".join(f"{p.file}:{p.line}: {p.severity} {p.code}: {p.message}" for p in problems)


def _step_dir(value: str) -> tuple[str, Path]:
    binding, sep, directory = value.partition("=")
    if not sep or not binding or not directory:
        raise argparse.ArgumentTypeError(f"expected BINDING=DIR, got {value!r}")
    return binding, Path(directory)


def _parse_args(argv: Sequence[str] | None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        prog="scenario_contract", description=(__doc__ or "").split("\n\n")[0]
    )
    parser.add_argument(
        "--features", type=Path, default=DEFAULT_FEATURES, help="directory of the scenario areas"
    )
    parser.add_argument(
        "--steps",
        type=_step_dir,
        action="append",
        metavar="BINDING=DIR",
        help="where a binding's step definitions live (default: python=<features>)",
    )
    return parser.parse_args(argv)


def main(argv: Sequence[str] | None = None) -> int:
    args = _parse_args(argv)
    step_dirs = dict(args.steps) if args.steps else None
    problems = check_tree(args.features, step_dirs)
    if problems:
        print(format_problems(problems))
    errors = sum(1 for p in problems if p.severity == ERROR)
    infos = len(problems) - errors
    if errors:
        print(f"scenario contract: {errors} error(s), {infos} info")
        return 1
    print(f"scenario contract: ok ({infos} info)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
