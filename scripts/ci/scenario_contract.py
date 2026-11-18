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
  scenario carries a non-empty ``@manual-reason:<text>`` and is never also ``@wip``, a
  ``@manual-reason:`` never appears without ``@manual``, and neither tags an ``Examples`` block;
* every step of a gating scenario (neither ``@wip`` nor ``@manual``) matches exactly one step definition in each
  binding the scenario claims (``python``, plus ``jvm`` when tagged ``@jvm``; ``jvm`` alone when
  tagged ``@jvm-only``, a promise only the JVM services implement), keyword
  included (pytest-bdd matches Given, When and Then separately; And and But take the keyword
  before them; a first step must be Given, When or Then). A python definition inside the
  features folder serves only the features below its own folder, as pytest-bdd scopes an
  area's ``conftest.py``. In a ``@wip`` or ``@manual`` scenario such findings are ``info``;
* each area has a ``README.md`` whose ``| Group | Step |`` table is consistent (Arrange and Assume
  rows are ``Given``, Act rows ``When``, Assert rows ``Then``), and a ``PROMISES.md`` that declares
  whether the JVM binding covers the area (``JVM binding: none|partial|full``), gives every
  promise a Binding (``python``, ``python, jvm``, ``jvm``, ``@wip`` or ``manual``), and indexes exactly the promises the
  area's scenarios carry, once each. The three binding claims must agree
  (``BINDING_MISMATCH``): a row's Binding follows its scenario's ``@wip``, ``@manual``, ``@jvm``
  and ``@jvm-only`` tags, and
  ``JVM binding`` is ``none``, ``partial`` or ``full`` as the gating rows claim ``jvm``.

Step definitions are read through :data:`STEP_SOURCES`, a registry keyed by binding name. A
binding counts as registered only when it has both a reader there and a step folder to read
(``--steps BINDING=DIR``, repeatable for several folders). Two are registered: ``python``
(pytest-bdd decorators, read with :mod:`ast`, never imported) and ``jvm`` (Cucumber-JVM
annotations, read from ``*.java`` source as text, never compiled; spec-kitty-events#199). A
``jvm`` claim -- an ``@jvm`` tag, a ``JVM binding`` other than ``none``, a ``python, jvm`` promise
row -- without a ``jvm`` step folder is a ``BAD_BINDING_DECLARATION``; with one, every step of an
``@jvm`` scenario must match exactly one Java step definition. By default the CLI reads
``python`` from the features folder and ``jvm`` from ``java/src/test/java``.

Findings about a tag point at the line of that tag, not the scenario line. Findings are
de-duplicated and sorted by file, line, code and message.

Known limits of the jvm step reader: only ``@Given``, ``@When``, ``@Then``, ``@And`` and ``@But``
(simple or qualified) are read, with one single-line string literal as the argument (``value =``
allowed); comments are blanked out first. The expression is a regular expression when it starts
with ``^``, ends with ``$`` or is wrapped in ``/``, and otherwise a Cucumber expression with the
built-in parameter types, plain ``(optional)`` text and whitespace-bounded ``a/b`` alternation.
Cucumber-JVM ignores the keyword when matching, so a JVM definition matches any keyword. A
concatenated or constant argument, a text block, a custom ``@ParameterType`` or a regex Python
cannot compile is an ``UNSUPPORTED_EXPRESSION``.

Known limits of the python step reader: only ``@given``, ``@when``, ``@then`` and ``@step``
decorators are read, with the step text as a string literal, ``parsers.parse("...")`` (fields
``{name}``, ``{name:d}`` and ``{name:w}`` only, matched ignoring case as the parse library does)
or ``parsers.re("...")``. Anything else is an
``UNSUPPORTED_EXPRESSION``, so the steps it binds fail loudly rather than pass silently.

Usage::

    python scripts/ci/scenario_contract.py [--features DIR] [--steps BINDING=DIR ...]

(``--steps`` replaces both defaults: give ``python`` too when giving ``jvm``.)

Exit status is 1 when any ``error`` finding is reported, otherwise 0.
"""

from __future__ import annotations

import argparse
import json
import ast
import datetime
import re
import sys
from collections.abc import Callable, Mapping, Sequence
from dataclasses import dataclass, field, replace
from pathlib import Path
from typing import Union

REPO_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_FEATURES = REPO_ROOT / "tests" / "scenarios"
#: Where the Cucumber-JVM step definitions live (read as source; no JDK needed).
DEFAULT_JVM_STEPS = REPO_ROOT / "java" / "src" / "test" / "java"
DEFAULT_JVM_RUNNERS = DEFAULT_FEATURES / "jvm-runners.json"

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
MANUAL_REASON_WITHOUT_MANUAL = "MANUAL_REASON_WITHOUT_MANUAL"
MANUAL_ON_EXAMPLES = "MANUAL_ON_EXAMPLES"
JVM_ONLY_WITH_JVM = "JVM_ONLY_WITH_JVM"
JVM_ONLY_ON_EXAMPLES = "JVM_ONLY_ON_EXAMPLES"
# JVM runners (#284)
BAD_RUNNER = "BAD_RUNNER"
JVM_SCENARIO_NOT_RUN = "JVM_SCENARIO_NOT_RUN"
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
JVM_ONLY = "jvm-only"

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
    "jvm": (JVM,),
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
    areas: tuple[str, ...] | None = None  # a JVM runner's areas: the only features it serves


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
        for tag in tags:
            # pytest-bdd turns Examples tags into raw pytest marks, never through the tag hook,
            # so a @manual or @jvm-only row would run in Python despite the tag.
            if tag.name in ("manual", JVM_ONLY) or tag.name.startswith("manual-reason:"):
                self.error(
                    MANUAL_ON_EXAMPLES if tag.name != JVM_ONLY else JVM_ONLY_ON_EXAMPLES,
                    tag.line,
                    f"@{tag.name} cannot tag an Examples block: tag the Scenario Outline instead",
                )
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
_PLAIN_TAGS = frozenset({"wip", "manual", JVM, JVM_ONLY})


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
        return [
            _problem(
                MANUAL_REASON_WITHOUT_MANUAL,
                scenario.file,
                tag.line,
                f"@{tag.name} without @manual: a reason alone must not exempt a scenario",
            )
            for _value, tag in _values(scenario, "manual-reason")
        ]
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
    problems = [
        _problem(
            BAD_BINDING_DECLARATION,
            scenario.file,
            tag.line,
            f"@{tag.name} claims a JVM binding, but no {JVM!r} step source is registered "
            f"(registered: {', '.join(registered)})",
        )
        for tag in scenario.tags
        if tag.name in (JVM, JVM_ONLY) and JVM not in registered
    ]
    names = {tag.name for tag in scenario.tags}
    if {JVM, JVM_ONLY} <= names:
        only = next(tag for tag in scenario.tags if tag.name == JVM_ONLY)
        problems.append(
            _problem(
                JVM_ONLY_WITH_JVM,
                scenario.file,
                only.line,
                f"@{JVM_ONLY} and @{JVM} contradict: @{JVM} adds the JVM to the python binding, "
                f"@{JVM_ONLY} binds the JVM alone",
            )
        )
    return problems


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


# --- the jvm source: Cucumber-JVM annotations, read as source (no JDK, nothing compiled) ----

#: A Cucumber-JVM step annotation, simple or qualified (``@Given`` or
#: ``@io.cucumber.java.en.Given``), up to its opening parenthesis. Core's ``_JAVA_STEP_RE`` reads
#: ``@(Given|When|Then)("...")`` in one pattern; here the argument is parsed separately, so an
#: annotation whose argument is not one string literal is reported, never skipped.
_JAVA_STEP_RE = re.compile(r"@(?:[A-Za-z_$][\w$]*\s*\.\s*)*(Given|When|Then|And|But)\s*\(")
_JAVA_VALUE_RE = re.compile(r"\s*(?:value\s*=\s*)?")
_JAVA_ESCAPES = {
    "b": "\b",
    "t": "\t",
    "n": "\n",
    "f": "\f",
    "r": "\r",
    "s": " ",
    '"': '"',
    "'": "'",
    "\\": "\\",
}
_OCTAL_ESCAPE_RE = re.compile(r"[0-3][0-7]{0,2}|[4-7][0-7]?")
_UNICODE_ESCAPE_RE = re.compile(r"u+([0-9a-fA-F]{4})")

#: Cucumber's built-in parameter types (cucumber-expressions), as regexes. A custom
#: ``@ParameterType`` is not modelled: a step using one is an ``UNSUPPORTED_EXPRESSION``.
_INT_PATTERN = r"(?:-?\d+)|(?:\d+)"
_FLOAT_PATTERN = r"[-+]?(?:\d+(?:[.,]\d*)?|[.,]\d+)(?:[eE][-+]?\d+)?"
_CUCUMBER_PARAMETERS = {
    "int": _INT_PATTERN,
    "byte": _INT_PATTERN,
    "short": _INT_PATTERN,
    "long": _INT_PATTERN,
    "biginteger": _INT_PATTERN,
    "float": _FLOAT_PATTERN,
    "double": _FLOAT_PATTERN,
    "bigdecimal": _FLOAT_PATTERN,
    "word": r"[^\s]+",
    "string": r"\"(?:[^\"\\]*(?:\\.[^\"\\]*)*)\"|'(?:[^'\\]*(?:\\.[^'\\]*)*)'",
    "": r".*",
}


def _skip_java_literal(text: str, index: int) -> int:
    """The index just past the string, text-block or char literal opening at ``index``."""
    if text.startswith('"""', index):
        end = index + 3
        while end < len(text):
            if text[end] == "\\":
                end += 2
            elif text.startswith('"""', end):
                return end + 3
            else:
                end += 1
        return len(text)
    quote = text[index]
    end = index + 1
    while end < len(text) and text[end] not in (quote, "\n"):
        end += 2 if text[end] == "\\" else 1
    return min(end + 1, len(text))


def strip_java_comments(text: str) -> str:
    """Blank out ``//`` and ``/* */`` comments outside literals; line numbers are kept."""
    out: list[str] = []
    index = 0
    while index < len(text):
        char = text[index]
        if char in "\"'":
            end = _skip_java_literal(text, index)
            out.append(text[index:end])
            index = end
        elif text.startswith("//", index):
            end = text.find("\n", index)
            end = len(text) if end == -1 else end
            out.append(" " * (end - index))
            index = end
        elif text.startswith("/*", index):
            end = text.find("*/", index + 2)
            end = len(text) if end == -1 else end + 2
            out.append(re.sub(r"[^\n]", " ", text[index:end]))
            index = end
        else:
            out.append(char)
            index += 1
    return "".join(out)


def _mask_java_literals(text: str) -> str:
    """``text`` with every literal's content blanked (quotes and newlines kept), so an
    annotation-shaped text inside a string is never read as an annotation."""
    out: list[str] = []
    index = 0
    while index < len(text):
        if text[index] in "\"'":
            end = _skip_java_literal(text, index)
            out.append(re.sub(r"[^\n\"']", " ", text[index:end]))
            index = end
        else:
            out.append(text[index])
            index += 1
    return "".join(out)


def _java_string_literal(text: str, index: int) -> tuple[str, int] | None:
    """Decode the one-line string literal at ``index``; ``None`` when there is none."""
    if not text.startswith('"', index) or text.startswith('"""', index):
        return None
    out: list[str] = []
    end = index + 1
    while end < len(text):
        char = text[end]
        if char == '"':
            return "".join(out), end + 1
        if char == "\n":
            return None
        if char != "\\":
            out.append(char)
            end += 1
            continue
        rest = text[end + 1 :]
        if (m := _UNICODE_ESCAPE_RE.match(rest)) is not None:
            out.append(chr(int(m.group(1), 16)))
            end += 1 + m.end()
        elif (m := _OCTAL_ESCAPE_RE.match(rest)) is not None:
            out.append(chr(int(m.group(0), 8)))
            end += 1 + m.end()
        elif rest[:1] in _JAVA_ESCAPES:
            out.append(_JAVA_ESCAPES[rest[0]])
            end += 2
        else:
            return None
    return None


def _cucumber_text(chunk: str, expression: str, *, parameters: bool) -> str:
    """One whitespace-free piece of a Cucumber expression (no alternation) as a regex."""
    parts: list[str] = []
    index = 0
    while index < len(chunk):
        char = chunk[index]
        if char == "\\" and index + 1 < len(chunk):
            parts.append(re.escape(chunk[index + 1]))
            index += 2
        elif char == "{" and parameters:
            end = chunk.find("}", index)
            name = chunk[index + 1 : end] if end != -1 else None
            if name is None or name not in _CUCUMBER_PARAMETERS:
                raise UnsupportedExpressionError(
                    f"parameter {chunk[index : end + 1] if end != -1 else chunk[index:]!r} in step "
                    f"expression {expression!r} is not a built-in Cucumber parameter type"
                )
            parts.append(f"({_CUCUMBER_PARAMETERS[name]})")
            index = end + 1
        elif char == "(":
            end = chunk.find(")", index)
            inner = chunk[index + 1 : end] if end != -1 else ""
            if end == -1 or not inner or any(c in inner for c in "({/\\"):
                raise UnsupportedExpressionError(
                    f"optional text in step expression {expression!r} is not supported here; "
                    "use plain (text) without nesting, parameters, alternation or escapes"
                )
            parts.append(f"(?:{re.escape(inner)})?")
            index = end + 1
        elif char in "{})":
            raise UnsupportedExpressionError(
                f"unmatched {char!r} in step expression {expression!r}; escape it with a backslash"
            )
        else:
            parts.append(re.escape(char))
            index += 1
    return "".join(parts)


def _split_unescaped(chunk: str, separator: str) -> list[str]:
    pieces, current, index = [], [], 0
    while index < len(chunk):
        if chunk[index] == "\\" and index + 1 < len(chunk):
            current.append(chunk[index : index + 2])
            index += 2
        elif chunk[index] == separator:
            pieces.append("".join(current))
            current = []
            index += 1
        else:
            current.append(chunk[index])
            index += 1
    pieces.append("".join(current))
    return pieces


def cucumber_expression_to_regex(expression: str) -> re.Pattern[str]:
    """Translate a Cucumber-JVM step expression to a regex; use ``fullmatch`` on step text.

    As cucumber-expressions decides: an expression that starts with ``^`` or ends with ``$``, or
    is wrapped in ``/``, is a regular expression; anything else is a Cucumber expression with the
    built-in parameter types, ``(optional)`` text and ``a/b`` alternation (bounded by whitespace).
    Matching is case-sensitive, as in Cucumber.
    """
    regex_source: str | None = None
    if expression.startswith("^") or expression.endswith("$"):
        regex_source = expression
    elif len(expression) >= 2 and expression.startswith("/") and expression.endswith("/"):
        regex_source = expression[1:-1]
    if regex_source is not None:
        try:
            return re.compile(regex_source)
        except re.error as exc:
            raise UnsupportedExpressionError(
                f"regular expression {expression!r} does not compile as a Python regex: {exc}"
            ) from exc
    parts: list[str] = []
    for chunk in re.split(r"(\s+)", expression):
        if not chunk or chunk.isspace():
            parts.append(re.escape(chunk))
            continue
        alternatives = _split_unescaped(chunk, "/")
        if len(alternatives) == 1:
            parts.append(_cucumber_text(chunk, expression, parameters=True))
            continue
        if not all(alternatives) or any("{" in a.replace("\\{", "") for a in alternatives):
            raise UnsupportedExpressionError(
                f"alternation in step expression {expression!r} needs non-empty alternatives "
                "without parameters"
            )
        branches = [_cucumber_text(a, expression, parameters=False) for a in alternatives]
        parts.append(f"(?:{'|'.join(branches)})")
    return re.compile("".join(parts))


def _jvm_definitions_in(path: Path) -> tuple[list[StepDefinition], list[Problem], bool]:
    try:
        text = strip_java_comments(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError) as exc:
        return [], [_problem(STEP_FILE_UNREADABLE, path, 1, f"cannot read: {exc}")], False
    defs: list[StepDefinition] = []
    problems: list[Problem] = []
    found = False
    for match in _JAVA_STEP_RE.finditer(_mask_java_literals(text)):
        found = True
        line = text.count("\n", 0, match.start()) + 1
        value = _JAVA_VALUE_RE.match(text, match.end())
        start = value.end() if value is not None else match.end()
        literal = _java_string_literal(text, start)
        try:
            if literal is None or not text[literal[1] :].lstrip().startswith(")"):
                raise UnsupportedExpressionError(
                    f"@{match.group(1)} at line {line} does not take one string literal; "
                    'use @Given("...") with a single-line literal (no concatenation, constant or text block)'
                )
            expression = literal[0]
            regex = cucumber_expression_to_regex(expression)
        except UnsupportedExpressionError as exc:
            problems.append(_problem(UNSUPPORTED_EXPRESSION, path, line, str(exc)))
            continue
        # Cucumber-JVM matches a step definition whatever the step's keyword: '*' says so.
        defs.append(StepDefinition(JVM, "*", expression, f"{path.name}:{line}", regex, path))
    return defs, problems, found


def load_jvm_step_definitions(steps_dir: Path) -> tuple[list[StepDefinition], list[Problem]]:
    """Read Cucumber-JVM step annotations from every ``*.java`` under ``steps_dir`` (as text).

    Comments are blanked out first, so a commented-out annotation binds nothing. Only
    ``@Given``, ``@When``, ``@Then``, ``@And`` and ``@But`` with one string literal are read;
    Cucumber's Java 8 lambda glue is not. The keyword of the annotation is not part of the
    match (Cucumber-JVM ignores it), so two annotations with the same text are ambiguous.
    """
    defs: list[StepDefinition] = []
    problems: list[Problem] = []
    found_any = False
    for path in sorted(steps_dir.rglob("*.java")):
        file_defs, file_problems, found = _jvm_definitions_in(path)
        defs += file_defs
        problems += file_problems
        found_any = found_any or found
    if not found_any:
        problems.append(
            _problem(
                NO_STEP_DEFINITIONS,
                steps_dir,
                1,
                "no Cucumber-JVM @Given/@When/@Then step definitions found",
            )
        )
    return defs, problems


StepSource = Callable[[Path], tuple[list[StepDefinition], list[Problem]]]

#: Step-definition readers keyed by binding name: ``python`` (pytest-bdd, #197) and ``jvm``
#: (Cucumber-JVM, #199). A new binding plugs its reader in here and its folder in with ``--steps``.
STEP_SOURCES: dict[str, StepSource] = {
    PYTHON: load_python_step_definitions,
    JVM: load_jvm_step_definitions,
}


def claimed_bindings(scenario: Scenario) -> tuple[str, ...]:
    """``python`` binds every scenario, an ``@jvm`` one the JVM as well, except a
    ``@jvm-only`` scenario: a promise only the JVM services implement, bound by the JVM alone
    (the mirror of an area bound in Python only; operator ruling on #289)."""
    names = {tag.name for tag in scenario.tags}
    if JVM_ONLY in names:
        return (JVM,)
    return (PYTHON, JVM) if JVM in names else (PYTHON,)


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
    return ", ".join(claimed_bindings(scenario))


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


StepDirs = Mapping[str, Union[Path, Sequence[Path]]]


def _directories(value: Path | Sequence[Path]) -> list[Path]:
    return [value] if isinstance(value, Path) else list(value)


def load_definitions(
    step_dirs: StepDirs, sources: Mapping[str, StepSource] = STEP_SOURCES
) -> tuple[list[StepDefinition], list[Problem]]:
    """Read each binding's step folders (one folder, or several) through its registered source."""
    defs: list[StepDefinition] = []
    problems: list[Problem] = []
    for binding, value in sorted(step_dirs.items()):
        reader = sources.get(binding)
        for directory in _directories(value):
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


def _in_areas(features_dir: Path, feature: Path, areas: Sequence[str]) -> bool:
    relative = feature.resolve().relative_to(features_dir.resolve()).as_posix()
    return any(relative.startswith(area.rstrip("/") + "/") for area in areas)


def visible_definitions(
    definitions: Sequence[StepDefinition], features_dir: Path, feature: Path
) -> list[StepDefinition]:
    """The definitions a run of ``feature`` can use: one defined inside ``features_dir`` is
    visible only below its own folder (pytest-bdd scopes an area's ``conftest.py`` to that area,
    the root ``conftest.py`` to every area); a JVM runner's definition only in the runner's
    areas, as its Cucumber run reads only those (#284); any other, from a separate step folder,
    serves all."""
    root = features_dir.resolve()
    folder = feature.resolve().parent
    visible = []
    for definition in definitions:
        if definition.areas is not None:
            if _in_areas(features_dir, feature, definition.areas):
                visible.append(definition)
            continue
        home = definition.path.resolve().parent if definition.path is not None else None
        if home is None or not home.is_relative_to(root) or folder.is_relative_to(home):
            visible.append(definition)
    return visible


# ---------------------------------------------------------------------------
# JVM runners (#284): tests/scenarios/jvm-runners.json, shared with the Gradle build
# ---------------------------------------------------------------------------

_GLUE_RE = re.compile(r"[a-z][a-z0-9_]*(?:\.[a-z][a-z0-9_]*)*")
_AREA_RE = re.compile(r"[a-z0-9][a-z0-9_-]*(?:/[a-z0-9][a-z0-9_-]*)*")


@dataclass(frozen=True)
class JvmRunner:
    """One Cucumber-JVM run: a Gradle project, its glue package and the areas it runs."""

    project: str
    glue: str
    steps: Path  # the glue package's folder: the only step definitions the run can find
    areas: tuple[str, ...]
    registry: Path | None = None  # the file the runner was read from, for findings


def load_jvm_runners(
    path: Path, repo_root: Path = REPO_ROOT
) -> tuple[list[JvmRunner], list[Problem]]:
    """Read the runner registry. A malformed entry, a missing glue folder, or an area two
    runners share (a scenario must run in exactly one place) is a ``BAD_RUNNER``."""

    def bad(message: str) -> Problem:
        return _problem(BAD_RUNNER, path, 1, message)

    try:
        document = json.loads(path.read_text(encoding="utf-8"))
        entries = document["runners"]
        assert isinstance(entries, list) and entries
    except (OSError, ValueError, KeyError, TypeError, AssertionError) as error:
        return [], [bad(f"unreadable JVM runner registry: {error}")]
    runners: list[JvmRunner] = []
    problems: list[Problem] = []
    owner: dict[str, str] = {}
    for entry in entries:
        project = entry.get("project") if isinstance(entry, dict) else None
        glue = entry.get("glue") if isinstance(entry, dict) else None
        sources = entry.get("testSources") if isinstance(entry, dict) else None
        areas = entry.get("areas") if isinstance(entry, dict) else None
        if not (
            isinstance(project, str)
            and project.startswith(":")
            and isinstance(glue, str)
            and _GLUE_RE.fullmatch(glue)
            and isinstance(sources, str)
            and isinstance(areas, list)
            and areas
            and all(isinstance(a, str) and _AREA_RE.fullmatch(a) for a in areas)
        ):
            problems.append(bad(f"malformed runner entry {entry!r}"))
            continue
        steps = repo_root / sources / glue.replace(".", "/")
        if not steps.is_dir():
            problems.append(bad(f"{project}: glue package folder {steps} does not exist"))
        for area in areas:
            for other, other_project in owner.items():
                if area == other or area.startswith(other + "/") or other.startswith(area + "/"):
                    problems.append(
                        bad(f"{project} area {area!r} overlaps {other_project} area {other!r}")
                    )
            owner[area] = project
        runners.append(JvmRunner(project, glue, steps, tuple(areas), path))
    return runners, problems


def _runner_definitions(
    runners: Sequence[JvmRunner], reader: StepSource
) -> tuple[list[StepDefinition], list[Problem]]:
    defs: list[StepDefinition] = []
    problems: list[Problem] = []
    for runner in runners:
        if not runner.steps.is_dir():
            continue
        found, found_problems = reader(runner.steps)
        defs += [replace(d, areas=runner.areas) for d in found]
        problems += found_problems
    return defs, problems


def _check_jvm_runs(
    features_dir: Path, path: Path, scenarios: Sequence[Scenario], runners: Sequence[JvmRunner]
) -> list[Problem]:
    """A gating scenario that claims the JVM must lie in a runner's areas, or it never runs."""
    if any(_in_areas(features_dir, path, runner.areas) for runner in runners):
        return []
    return [
        _problem(
            JVM_SCENARIO_NOT_RUN,
            scenario.file,
            scenario.line,
            f"scenario {scenario.name!r} claims the JVM, but no runner in "
            f"{DEFAULT_JVM_RUNNERS.name} runs its area",
        )
        for scenario in scenarios
        if is_gating(scenario) and JVM in claimed_bindings(scenario)
    ]


def _check_layout(features_dir: Path, path: Path) -> list[Problem]:
    parts = path.relative_to(features_dir).parts
    if len(parts) == 3 and parts[2] == f"{parts[1]}.feature":
        return []
    return [
        _problem(BAD_LAYOUT, path, 1, "a feature lives at <area>/<behaviour>/<behaviour>.feature")
    ]


def check_tree(
    features_dir: Path,
    step_dirs: StepDirs | None = None,
    sources: Mapping[str, StepSource] = STEP_SOURCES,
    jvm_runners: Sequence[JvmRunner] | None = None,
) -> list[Problem]:
    """Check every area and ``.feature`` file under ``features_dir``.

    ``step_dirs`` maps a binding name to the directory (or directories) its step source reads;
    by default only the ``python`` step definitions are read, from ``features_dir`` itself. A binding counts as
    registered only when ``sources`` has a reader for it *and* ``step_dirs`` names where its
    step definitions live, so a ``jvm`` claim is never accepted without reading JVM steps.
    ``jvm_runners`` registers ``jvm`` too: each runner's definitions serve only its areas, and a
    gating scenario that claims the JVM outside every runner's areas is ``JVM_SCENARIO_NOT_RUN``.
    """
    step_dirs = dict(step_dirs) if step_dirs is not None else {PYTHON: features_dir}
    step_dirs = {binding: value for binding, value in step_dirs.items() if _directories(value)}
    registered = sorted(binding for binding in step_dirs if binding in sources)
    definitions, problems = load_definitions(step_dirs, sources)
    if jvm_runners is not None and JVM in sources:
        runner_defs, runner_problems = _runner_definitions(jvm_runners, sources[JVM])
        definitions += runner_defs
        problems += runner_problems
        registered = sorted({*registered, JVM})
    files = sorted(features_dir.rglob("*.feature"))
    if not files:
        problems.append(_problem(NO_FEATURES, features_dir, 1, "no .feature files found"))
    all_scenarios: list[Scenario] = []
    by_area: dict[Path, list[Scenario]] = {}
    served: set[str] = set()  # runners with at least one gating JVM scenario to run
    for path in files:
        problems += _check_layout(features_dir, path)
        scenarios, parse_problems = parse_feature(path)
        visible = visible_definitions(definitions, features_dir, path)
        problems += parse_problems + _check_scenarios(scenarios, visible, registered)
        if jvm_runners is not None:
            problems += _check_jvm_runs(features_dir, path, scenarios, jvm_runners)
            for runner in jvm_runners:
                if _in_areas(features_dir, path, runner.areas) and any(
                    is_gating(s) and JVM in claimed_bindings(s) for s in scenarios
                ):
                    served.add(runner.project)
        all_scenarios += scenarios
        area = features_dir / path.relative_to(features_dir).parts[0]
        if area.is_dir():
            by_area.setdefault(area, []).extend(scenarios)
    for area in sorted(by_area):
        problems += check_area_readme(area)
        index = check_area_promises(area, registered)
        problems += index.problems + check_promise_index(area, index, by_area[area])
    problems += check_unique_promises(all_scenarios)
    for runner in jvm_runners or ():
        if runner.project not in served:
            problems.append(
                _problem(
                    BAD_RUNNER,
                    runner.registry or features_dir,
                    1,
                    f"{runner.project} has no gating @jvm or @jvm-only scenario in {list(runner.areas)}:"
                    " its Cucumber run would select nothing and fail; remove the entry",
                )
            )
    if files and not any(is_gating(s) for s in all_scenarios):
        problems.append(
            _problem(
                NO_GATING_SCENARIOS, features_dir, 1, "no scenario gates (all are @wip or @manual)"
            )
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
        help=(
            "where a binding's step definitions live; repeat it to give a binding several folders "
            "(default: python=<features>, and jvm from the runner registry)"
        ),
    )
    parser.add_argument(
        "--jvm-runners",
        type=Path,
        metavar="FILE",
        help=(
            "the JVM runner registry; each runner's steps serve only its areas "
            f"(default without --steps: {DEFAULT_JVM_RUNNERS.relative_to(REPO_ROOT)})"
        ),
    )
    return parser.parse_args(argv)


def main(argv: Sequence[str] | None = None) -> int:
    args = _parse_args(argv)
    step_dirs: dict[str, list[Path]] = {}
    for binding, directory in args.steps or [(PYTHON, args.features)]:
        step_dirs.setdefault(binding, []).append(directory)
    registry = args.jvm_runners or (None if args.steps else DEFAULT_JVM_RUNNERS)
    runners: list[JvmRunner] | None = None
    registry_problems: list[Problem] = []
    if registry is not None:
        runners, registry_problems = load_jvm_runners(registry)
    problems = sort_problems(
        registry_problems + check_tree(args.features, step_dirs, jvm_runners=runners)
    )
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
