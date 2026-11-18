"""Per-PR contract check for the BDD scenarios (no JDK, stdlib only; spec-kitty-events#197).

Runs ``scripts/ci/scenario_contract.py`` over the real ``tests/scenarios`` tree and over the
broken fixtures in ``tests/fixtures/scenario_contract/`` (one rule each) to prove every rule can
fail, then pins the reader's Background handling, the finding order and the CLI exit codes.
"""

from __future__ import annotations

import ast
import dataclasses
import importlib.util
import random
import subprocess
import sys
from pathlib import Path
from types import ModuleType

import pytest

REPO_ROOT = Path(__file__).resolve().parents[1]
SCRIPT = REPO_ROOT / "scripts" / "ci" / "scenario_contract.py"
FIXTURES = REPO_ROOT / "tests" / "fixtures" / "scenario_contract"
FIXTURE_FEATURES = FIXTURES / "features"
FIXTURE_AREAS = FIXTURES / "areas"
FIXTURE_STEPS = FIXTURES / "steps"
FIXTURE_JVM_STEPS = FIXTURES / "steps_jvm"
REAL_FEATURES = REPO_ROOT / "tests" / "scenarios"
REAL_JVM_STEPS = REPO_ROOT / "java" / "src" / "test" / "java"


def _load() -> ModuleType:
    spec = importlib.util.spec_from_file_location("scenario_contract", SCRIPT)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


contract = _load()


@pytest.fixture(scope="module")
def fixture_defs() -> list:
    defs, problems = contract.load_python_step_definitions(FIXTURE_STEPS)
    assert problems == []
    return defs


def _codes(problems, severity: str = "error") -> list[str]:
    return sorted(p.code for p in problems if p.severity == severity)


# --- one broken fixture per feature-level rule ---------------------------------------------

EXPECTED_FEATURE_ERRORS = {
    "parse_error.feature": ["PARSE_ERROR"],
    "leading_bullet.feature": ["PARSE_ERROR"],
    "no_scenarios.feature": ["NO_SCENARIOS"],
    "no_steps.feature": ["NO_STEPS"],
    "outline_no_examples.feature": ["NO_EXAMPLES"],
    "unbound_step.feature": ["UNBOUND_STEP"],
    "wrong_keyword.feature": ["UNBOUND_STEP"],
    "background_unbound.feature": ["UNBOUND_STEP"],
    "outline_unbound.feature": ["UNBOUND_STEP"],
    "ambiguous_step.feature": ["AMBIGUOUS_STEP"],
    "missing_promise.feature": ["MISSING_PROMISE"],
    "bad_promise.feature": ["BAD_PROMISE_ID"],
    "duplicate_promise.feature": ["DUPLICATE_PROMISE"],
    "duplicate_promise_across_scenarios.feature": ["DUPLICATE_PROMISE"],
    "feature_level_promise.feature": ["DUPLICATE_PROMISE"],
    "duplicate_promise_examples.feature": ["DUPLICATE_PROMISE"],
    "unknown_tag.feature": ["UNKNOWN_TAG"],
    "unknown_prefixed_tag.feature": ["UNKNOWN_TAG"],
    "wip_missing_owner.feature": ["WIP_MISSING_OWNER"],
    "wip_missing_since.feature": ["WIP_MISSING_SINCE"],
    "bad_wip_owner.feature": ["BAD_WIP_OWNER"],
    "bad_wip_since.feature": ["BAD_WIP_SINCE"],
    "bad_defect.feature": ["BAD_DEFECT"],
    "jvm_tag.feature": ["BAD_BINDING_DECLARATION"],
    "manual_missing_reason.feature": ["MANUAL_MISSING_REASON"],
    "manual_empty_reason.feature": ["BAD_MANUAL_REASON"],
    "manual_with_wip.feature": ["MANUAL_WITH_WIP"],
}


@pytest.mark.parametrize("name,codes", sorted(EXPECTED_FEATURE_ERRORS.items()))
def test_each_broken_feature_reports_its_rule(name: str, codes: list[str], fixture_defs) -> None:
    problems = contract.check_feature_file(FIXTURE_FEATURES / name, fixture_defs)
    assert _codes(problems) == codes


def test_every_feature_fixture_is_expected() -> None:
    on_disk = {p.name for p in FIXTURE_FEATURES.glob("*.feature")}
    assert on_disk == set(EXPECTED_FEATURE_ERRORS) | {
        "valid.feature",
        "wip_unbound.feature",
        "manual_valid.feature",
    }


def test_valid_feature_is_clean(fixture_defs) -> None:
    assert contract.check_feature_file(FIXTURE_FEATURES / "valid.feature", fixture_defs) == []


def test_unbound_steps_of_a_wip_scenario_are_information_only(fixture_defs) -> None:
    problems = contract.check_feature_file(FIXTURE_FEATURES / "wip_unbound.feature", fixture_defs)
    assert _codes(problems) == []
    assert _codes(problems, "info") == ["UNBOUND_STEP"]


def test_manual_scenario_is_clean_and_does_not_gate(fixture_defs) -> None:
    """A @manual promise with its reason passes; its unbound step is information only."""
    path = FIXTURE_FEATURES / "manual_valid.feature"
    problems = contract.check_feature_file(path, fixture_defs)
    assert _codes(problems) == []
    assert _codes(problems, "info") == ["UNBOUND_STEP"]
    scenarios, _ = contract.parse_feature(path)
    assert [contract.is_gating(s) for s in scenarios] == [False]


# Findings about a tag point at the tag's line, not at the scenario line below it.
TAG_LINES = {
    "bad_promise.feature": 3,
    "duplicate_promise.feature": 4,
    "feature_level_promise.feature": 1,
    "unknown_tag.feature": 3,
    "wip_missing_owner.feature": 4,
    "bad_wip_since.feature": 4,
    "jvm_tag.feature": 3,
    "manual_missing_reason.feature": 4,
    "manual_with_wip.feature": 4,
    "manual_empty_reason.feature": 4,
}


@pytest.mark.parametrize("name,line", sorted(TAG_LINES.items()))
def test_tag_findings_point_at_the_tag_line(name: str, line: int, fixture_defs) -> None:
    problems = contract.check_feature_file(FIXTURE_FEATURES / name, fixture_defs)
    assert [p.line for p in problems if p.severity == "error"] == [line]
    scenario_lines = [
        number
        for number, text in enumerate((FIXTURE_FEATURES / name).read_text().splitlines(), 1)
        if text.strip().startswith("Scenario")
    ]
    assert line not in scenario_lines


# --- one broken fixture per area- or tree-level rule ---------------------------------------

EXPECTED_AREA_ERRORS = {
    "bad_layout": ["BAD_LAYOUT"],
    "missing_area_readme": ["MISSING_AREA_README"],
    "missing_steps_table": ["MISSING_STEPS_TABLE"],
    "step_group_mismatch": ["STEP_GROUP_MISMATCH"],
    "missing_promises_file": ["MISSING_BINDING_DECLARATION"],
    "missing_jvm_declaration": ["MISSING_BINDING_DECLARATION"],
    "bad_jvm_declaration": ["BAD_BINDING_DECLARATION"],
    "jvm_bound": ["BAD_BINDING_DECLARATION"] * 3,  # the @jvm tag, the row and the area line
    "bad_promise_binding": ["BAD_BINDING_DECLARATION"],
    "binding_mismatch_wip": ["BINDING_MISMATCH"],
    "binding_mismatch_manual": ["BINDING_MISMATCH"],
    "promise_not_indexed": ["PROMISE_NOT_INDEXED"],
    "indexed_promise_unused": ["INDEXED_PROMISE_UNUSED"],
    "duplicate_promise_across_files": ["DUPLICATE_PROMISE"],
    "duplicate_promise_row": ["DUPLICATE_PROMISE"],
    "bad_indexed_promise": ["BAD_PROMISE_ID"],
    "no_gating_scenarios": ["NO_GATING_SCENARIOS"],
    "no_features": ["NO_FEATURES"],
}


def _check_area(name: str, **kwargs):
    return contract.check_tree(FIXTURE_AREAS / name, {"python": FIXTURE_STEPS}, **kwargs)


@pytest.mark.parametrize("name,codes", sorted(EXPECTED_AREA_ERRORS.items()))
def test_each_broken_area_reports_its_rule(name: str, codes: list[str]) -> None:
    assert _codes(_check_area(name)) == codes


def test_every_area_fixture_is_expected() -> None:
    on_disk = {p.name for p in FIXTURE_AREAS.iterdir() if p.is_dir()}
    assert on_disk == set(EXPECTED_AREA_ERRORS) | set(EXPECTED_WITH_JVM) | set(
        EXPECTED_WITH_JAVA_STEPS
    ) | {
        "valid",
        "manual_bound",
        "step_from_another_area",
    }


def test_steps_are_scoped_to_their_area_as_pytest_bdd_scopes_conftest() -> None:
    """A step defined in area a's conftest.py does not bind area b's feature, and a phrase both
    areas define is not ambiguous."""
    tree = FIXTURE_AREAS / "step_from_another_area"
    problems = contract.check_tree(tree)
    assert [(p.code, Path(p.file).relative_to(tree).as_posix(), p.line) for p in problems] == [
        ("UNBOUND_STEP", "b/beh/beh.feature", 6)
    ]


def test_valid_area_is_clean() -> None:
    assert _check_area("valid") == []


def test_manual_promise_counts_in_promises_with_binding_manual() -> None:
    assert _check_area("manual_bound") == []


def _jvm_reader(definitions):
    """A stand-in ``jvm`` step source that reads the given definitions as JVM ones."""

    def read(_directory: Path):
        return [dataclasses.replace(d, binding="jvm") for d in definitions], []

    return read


# Rules that only bite once a jvm step source is registered (D3 #199 / D4 #270).
EXPECTED_WITH_JVM = {
    "binding_mismatch_jvm_coverage": ["BINDING_MISMATCH"],
}


def _check_with_jvm(name: str, jvm_definitions) -> list:
    return contract.check_tree(
        FIXTURE_AREAS / name,
        {"python": FIXTURE_STEPS, "jvm": FIXTURE_STEPS},
        sources={**contract.STEP_SOURCES, "jvm": _jvm_reader(jvm_definitions)},
    )


@pytest.mark.parametrize("name,codes", sorted(EXPECTED_WITH_JVM.items()))
def test_each_broken_area_reports_its_rule_once_jvm_is_registered(
    name: str, codes: list[str], fixture_defs
) -> None:
    assert _codes(_check_with_jvm(name, fixture_defs)) == codes


def test_step_sources_are_a_registry_keyed_by_binding_name(fixture_defs) -> None:
    """Plugging a ``jvm`` reader in, with a step folder, is all a JVM binding needs."""
    assert sorted(contract.STEP_SOURCES) == ["jvm", "python"]
    assert contract.STEP_SOURCES["jvm"] is contract.load_jvm_step_definitions
    assert _check_with_jvm("jvm_bound", fixture_defs) == []


def test_a_jvm_scenario_is_matched_against_the_jvm_steps(fixture_defs) -> None:
    problems = _check_with_jvm("jvm_bound", [])
    assert _codes(problems) == ["UNBOUND_STEP"] * 3
    assert all("(jvm)" in p.message for p in problems)


def test_a_registered_source_without_a_step_folder_is_not_a_binding(fixture_defs) -> None:
    problems = contract.check_tree(
        FIXTURE_AREAS / "jvm_bound",
        {"python": FIXTURE_STEPS},
        sources={**contract.STEP_SOURCES, "jvm": _jvm_reader(fixture_defs)},
    )
    assert _codes(problems) == ["BAD_BINDING_DECLARATION"] * 3


def test_steps_for_an_unregistered_binding_are_a_bad_binding_declaration() -> None:
    problems = contract.check_tree(
        FIXTURE_AREAS / "valid", {"python": FIXTURE_STEPS, "kotlin": FIXTURE_STEPS}
    )
    assert _codes(problems) == ["BAD_BINDING_DECLARATION"]


# --- the jvm step source: Cucumber-JVM annotations read as Java source (#199) ----------------

# Areas checked with the real jvm reader over steps_jvm/ (Steps.java).
EXPECTED_WITH_JAVA_STEPS = {
    "jvm_step_missing": ["UNBOUND_STEP"],  # an @jvm claim whose step has no Java definition
}


def _check_with_java_steps(name: str) -> list:
    return contract.check_tree(
        FIXTURE_AREAS / name, {"python": FIXTURE_STEPS, "jvm": FIXTURE_JVM_STEPS}
    )


@pytest.mark.parametrize("name,codes", sorted(EXPECTED_WITH_JAVA_STEPS.items()))
def test_a_jvm_claim_without_a_java_step_fails_the_check(name: str, codes: list[str]) -> None:
    problems = _check_with_java_steps(name)
    assert _codes(problems) == codes
    (problem,) = problems
    assert (Path(problem.file).name, problem.line) == ("behaviour.feature", 6)
    assert problem.message == """no step definition matches 'Given the value "x" (jvm)'"""


def test_a_jvm_claim_with_every_java_step_is_clean() -> None:
    assert _check_with_java_steps("jvm_bound") == []


def test_the_jvm_reader_reads_annotations_and_skips_comments() -> None:
    defs, problems = contract.load_jvm_step_definitions(FIXTURE_JVM_STEPS)
    assert problems == []
    assert [(d.binding, d.keyword, d.expression, d.source) for d in defs] == [
        ("jvm", "*", "a thing", "Steps.java:9"),
        ("jvm", "*", "it happens", "Steps.java:12"),
        ("jvm", "*", "it holds", "Steps.java:15"),
    ]


def test_unsupported_java_expressions_are_reported_with_their_line() -> None:
    defs, problems = contract.load_jvm_step_definitions(FIXTURES / "steps_jvm_unsupported")
    assert defs == []
    assert [(p.code, p.line) for p in problems] == [
        ("UNSUPPORTED_EXPRESSION", n) for n in (7, 10, 13, 17, 20, 23, 26)
    ]


def test_a_java_folder_without_step_annotations_is_reported(tmp_path: Path) -> None:
    (tmp_path / "Plain.java").write_text("class Plain {}\n", encoding="utf-8")
    _defs, problems = contract.load_jvm_step_definitions(tmp_path)
    assert _codes(problems) == ["NO_STEP_DEFINITIONS"]


def test_an_unreadable_java_file_is_reported(tmp_path: Path) -> None:
    (tmp_path / "Bad.java").write_bytes(b"\xff\xfe")
    _defs, problems = contract.load_jvm_step_definitions(tmp_path)
    assert _codes(problems) == ["NO_STEP_DEFINITIONS", "STEP_FILE_UNREADABLE"]


def test_cucumber_jvm_ignores_the_keyword_so_equal_texts_are_ambiguous(tmp_path: Path) -> None:
    (tmp_path / "Steps.java").write_text(
        '@Given("a thing") void a() {}\n@Then("a thing") void b() {}\n'
        '@When("it happens") void c() {}\n@Then("it holds") void d() {}\n',
        encoding="utf-8",
    )
    problems = contract.check_tree(
        FIXTURE_AREAS / "jvm_bound", {"python": FIXTURE_STEPS, "jvm": tmp_path}
    )
    assert [(p.code, p.line) for p in problems] == [("AMBIGUOUS_STEP", 5)]
    assert "Steps.java:1, Steps.java:2" in problems[0].message


def test_a_binding_may_read_several_step_folders(tmp_path: Path) -> None:
    (tmp_path / "More.java").write_text('@Given("the value {string}") void v() {}\n', "utf-8")
    folders = {"python": FIXTURE_STEPS, "jvm": [FIXTURE_JVM_STEPS, tmp_path]}
    assert contract.check_tree(FIXTURE_AREAS / "jvm_step_missing", folders) == []


@pytest.mark.parametrize(
    "expression,matches,misses",
    [
        ("a thing", ["a thing"], ["a  thing", "A thing", "a thing!"]),
        ("the group {string}", ['the group "cli"', "the group 'cli'"], ['the group "a" or "b"']),
        ("{int} cases", ["12 cases", "-3 cases"], ["1.5 cases", "x cases"]),
        ("{float} percent", ["1.5 percent", "2 percent", ".5 percent"], ["x percent"]),
        ("a {word} b", ["a x-y b"], ["a x y b"]),
        ("all of {}", ["all of anything at all", "all of "], []),
        ("a cucumber(s)", ["a cucumber", "a cucumbers"], ["a cucumberss"]),
        ("in the belly/stomach", ["in the belly", "in the stomach"], ["in the belly/stomach"]),
        (
            r"an escaped \(paren\) and \{brace\} and a\/b",
            ["an escaped (paren) and {brace} and a/b"],
            [],
        ),
        ("^a (regex) of (\\d+)$", ["a regex of 12"], ["a (regex) of 12"]),
        ("/wrapped (\\w+)/", ["wrapped word"], ["/wrapped word/"]),
    ],
)
def test_cucumber_expressions_translate_to_full_match_regexes(
    expression: str, matches: list[str], misses: list[str]
) -> None:
    regex = contract.cucumber_expression_to_regex(expression)
    assert all(regex.fullmatch(text) for text in matches), regex.pattern
    assert not any(regex.fullmatch(text) for text in misses), regex.pattern


@pytest.mark.parametrize(
    "expression",
    ["a {color}", "a } b", "a ( b", "an (opt{int})", "x/{int}", "a//b", "^(unbalanced$"],
)
def test_unsupported_cucumber_expressions_are_refused(expression: str) -> None:
    with pytest.raises(contract.UnsupportedExpressionError):
        contract.cucumber_expression_to_regex(expression)


@pytest.mark.parametrize(
    "source,expected",
    [
        ("a // b\nc", "a     \nc"),
        ("a /* b\nc */ d", "a     \n     d"),
        ('s = "// not a comment"; // gone', 's = "// not a comment";        '),
        ("c = '\"'; /* x */", "c = '\"';        "),
        ('t = """\n  /* kept */\n  """; // gone', 't = """\n  /* kept */\n  """;        '),
        ("/* unterminated\n", "               \n"),
    ],
)
def test_strip_java_comments_keeps_literals_and_line_numbers(source: str, expected: str) -> None:
    stripped = contract.strip_java_comments(source)
    assert stripped == expected
    assert stripped.count("\n") == source.count("\n")


def test_java_string_escapes_are_decoded_before_translation(tmp_path: Path) -> None:
    (tmp_path / "Steps.java").write_text(
        '@Given("a \\"quoted\\" tab\\there \\u00e9 \\101 \\\\") void s() {}\n', "utf-8"
    )
    (definition,), problems = contract.load_jvm_step_definitions(tmp_path)
    assert problems == []
    assert definition.expression == 'a "quoted" tab\there \u00e9 A \\'


# --- step sources ----------------------------------------------------------------------------


def test_unsupported_expressions_are_reported_with_their_source() -> None:
    defs, problems = contract.load_python_step_definitions(FIXTURES / "steps_unsupported")
    assert defs == []
    assert [(p.code, p.line) for p in problems] == [
        ("UNSUPPORTED_EXPRESSION", n) for n in (8, 12, 16)
    ]
    assert all(p.file.endswith("steps.py") for p in problems)


def test_an_unreadable_step_file_is_reported(tmp_path: Path) -> None:
    (tmp_path / "steps.py").write_text("def broken(:\n", encoding="utf-8")
    _defs, problems = contract.load_python_step_definitions(tmp_path)
    assert _codes(problems) == ["NO_STEP_DEFINITIONS", "STEP_FILE_UNREADABLE"]


def test_a_steps_folder_without_definitions_is_reported(tmp_path: Path) -> None:
    _defs, problems = contract.load_python_step_definitions(tmp_path)
    assert _codes(problems) == ["NO_STEP_DEFINITIONS"]


def test_parse_fields_translate_to_full_match_regexes() -> None:
    regex = contract.parse_format_to_regex('the class "{name:w}" holds {count:d} {{items}}')
    assert regex.fullmatch('the class "envelope_x" holds 3 {items}')
    assert not regex.fullmatch('the class "a b" holds 3 {items}')
    assert not regex.fullmatch('the class "x" holds three {items}')
    # The parse library behind parsers.parse ignores case; so does the check.
    assert regex.fullmatch('The Class "x" HOLDS 3 {items}')


# --- reader: Background steps ----------------------------------------------------------------

BACKGROUND_FEATURE = """\
Feature: Backgrounds

  Background:
    Given a feature background step

  @promise:PR-EVENTS-FIXTURE-001
  Scenario: First
    When it happens

  Rule: A rule with its own background

    Background:
      Given a rule background step

    @promise:PR-EVENTS-FIXTURE-002
    Scenario: Second
      Then it holds
"""


def test_background_steps_are_collected_into_every_scenario() -> None:
    scenarios, problems = contract.parse_feature_text(BACKGROUND_FEATURE, "background.feature")
    assert problems == []
    assert [[(s.keyword, s.text, s.line) for s in sc.steps] for sc in scenarios] == [
        [("Given", "a feature background step", 4), ("When", "it happens", 8)],
        [
            ("Given", "a feature background step", 4),
            ("Given", "a rule background step", 13),
            ("Then", "it holds", 17),
        ],
    ]


def test_examples_tags_apply_to_their_rows_only() -> None:
    text = """\
Feature: Outline

  @promise:PR-EVENTS-FIXTURE-001
  Scenario Outline: Rows
    Given the value "<value>"

    Examples:
      | value |
      | one   |

    @wip
    Examples:
      | value |
      | two   |
"""
    scenarios, problems = contract.parse_feature_text(text, "outline.feature")
    assert problems == []
    assert [[t.name for t in s.tags] for s in scenarios] == [
        ["promise:PR-EVENTS-FIXTURE-001"],
        ["promise:PR-EVENTS-FIXTURE-001", "wip"],
    ]


# --- finding order ---------------------------------------------------------------------------


def test_findings_are_deduplicated_and_sorted_in_a_stable_order(tmp_path: Path) -> None:
    # Every broken feature fixture in one (badly laid out) tree: many files, lines and codes.
    problems = contract.check_tree(FIXTURE_FEATURES, {"python": FIXTURE_STEPS})
    assert len(problems) > len(EXPECTED_FEATURE_ERRORS)
    keys = [(p.file, p.line, p.code, p.severity, p.message) for p in problems]
    assert keys == sorted(keys)
    assert problems == contract.check_tree(FIXTURE_FEATURES, {"python": FIXTURE_STEPS})
    shuffled = list(problems) * 2
    random.Random(197).shuffle(shuffled)
    assert contract.sort_problems(shuffled) == problems


# --- the real tree and the CLI ---------------------------------------------------------------


def _non_gating_scenario_lines() -> set[tuple[str, int]]:
    """(file, step line) of every step of a @wip or @manual scenario in the real tree."""
    lines: set[tuple[str, int]] = set()
    for path in REAL_FEATURES.rglob("*.feature"):
        scenarios, _ = contract.parse_feature(path)
        for scenario in scenarios:
            if not contract.is_gating(scenario):
                lines |= {(str(path), step.line) for step in scenario.steps}
    return lines


def test_real_tree_is_clean_and_binds_through_python() -> None:
    problems = contract.check_tree(REAL_FEATURES, {"python": REAL_FEATURES, "jvm": REAL_JVM_STEPS})
    assert _codes(problems) == []
    # The only findings are info about steps of non-gating scenarios: the services' @wip
    # promises (#270) whose steps only the jvm source will bind.
    info = [p for p in problems if p.severity == "info"]
    assert {p.code for p in info} <= {"UNBOUND_STEP"}
    assert {(p.file, p.line) for p in info} <= _non_gating_scenario_lines()
    defs, problems = contract.load_python_step_definitions(REAL_FEATURES)
    assert problems == []
    assert {d.binding for d in defs} == {"python"}
    assert any(p.parent.name == "envelope" for p in REAL_FEATURES.glob("*/README.md"))


def test_real_jvm_scenarios_bind_through_the_java_steps() -> None:
    """#199: the upcast/ area is bound by Cucumber-JVM; without the Java steps it fails."""
    defs, problems = contract.load_jvm_step_definitions(REAL_JVM_STEPS)
    assert problems == []
    assert {Path(d.source.split(":")[0]).name for d in defs} == {"UpcastSteps.java"}
    without_jvm = contract.check_tree(REAL_FEATURES)
    assert without_jvm and {p.code for p in without_jvm} == {"BAD_BINDING_DECLARATION"}
    assert {Path(p.file).relative_to(REAL_FEATURES).parts[0] for p in without_jvm} == {"upcast"}


def test_the_cli_reads_the_jvm_steps_by_default(capsys: pytest.CaptureFixture[str]) -> None:
    assert contract.main([]) == 0
    assert "scenario contract: ok" in capsys.readouterr().out
    python_only = ["--steps", f"python={REAL_FEATURES}"]
    assert contract.main(python_only) == 1
    assert "BAD_BINDING_DECLARATION" in capsys.readouterr().out


def test_cli_exit_codes(capsys: pytest.CaptureFixture[str]) -> None:
    clean = ["--features", str(FIXTURE_AREAS / "valid"), "--steps", f"python={FIXTURE_STEPS}"]
    assert contract.main(clean) == 0
    assert "scenario contract: ok" in capsys.readouterr().out
    broken = ["--features", str(FIXTURE_AREAS / "bad_layout"), "--steps", f"python={FIXTURE_STEPS}"]
    assert contract.main(broken) == 1
    out = capsys.readouterr().out
    assert "error BAD_LAYOUT" in out
    assert "scenario contract: 1 error(s), 0 info" in out


def test_the_contract_check_needs_only_the_standard_library() -> None:
    """No JDK and no third-party import: the check runs on a bare interpreter."""
    tree = ast.parse(SCRIPT.read_text(encoding="utf-8"))
    imported = {
        alias.name.split(".")[0]
        for node in ast.walk(tree)
        if isinstance(node, ast.Import)
        for alias in node.names
    } | {
        node.module.split(".")[0]
        for node in ast.walk(tree)
        if isinstance(node, ast.ImportFrom) and node.module and node.level == 0
    }
    assert imported - {"__future__"} <= set(sys.stdlib_module_names)


def test_scenarios_raise_no_unknown_mark_warning() -> None:
    """Tags are consumed by tests/scenarios/conftest.py, so the run is clean under -W error."""
    result = subprocess.run(
        [
            sys.executable,
            "-m",
            "pytest",
            str(REAL_FEATURES),
            "-q",
            "--no-cov",
            "-p",
            "no:cacheprovider",
            "-W",
            "error::pytest.PytestUnknownMarkWarning",
        ],
        cwd=REPO_ROOT,
        capture_output=True,
        text=True,
        timeout=300,
    )
    assert result.returncode == 0, result.stdout + result.stderr


# --- the runtime tag hook (tests/scenarios/conftest.py) --------------------------------------


def _scenario_conftest() -> ModuleType:
    path = REAL_FEATURES / "conftest.py"
    spec = importlib.util.spec_from_file_location("scenarios_root_conftest", path)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def _marks_after(tags: list[str]) -> list[tuple[str, dict]]:
    hook = _scenario_conftest().pytest_bdd_apply_tag

    def scenario() -> None: ...

    for tag in tags:
        assert hook(tag, scenario) is True
    return [(m.name, dict(m.kwargs)) for m in getattr(scenario, "pytestmark", [])]


def test_manual_scenario_is_skipped_with_its_reason() -> None:
    marks = _marks_after(["promise:PR-EVENTS-FIXTURE-001", "manual", "manual-reason:needs-a-peer"])
    assert marks == [("skip", {"reason": "@manual: needs-a-peer"})]


def test_wip_scenario_is_a_non_strict_xfail_and_other_tags_add_no_mark() -> None:
    assert _marks_after(["wip", "wip-owner:someone", "jvm", "defect:1"]) == [
        ("xfail", {"reason": "@wip draft scenario", "strict": False})
    ]
