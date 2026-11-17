"""Per-PR contract check for the BDD scenarios (no JDK, stdlib only; spec-kitty-events#197).

Runs ``scripts/ci/scenario_contract.py`` over the real ``tests/scenarios`` tree and over the
broken fixtures in ``tests/fixtures/scenario_contract/`` (one rule each) to prove every rule can
fail, then pins the reader's Background handling, the finding order and the CLI exit codes.
"""

from __future__ import annotations

import ast
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
REAL_FEATURES = REPO_ROOT / "tests" / "scenarios"


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
    "unknown_tag.feature": ["UNKNOWN_TAG"],
    "unknown_prefixed_tag.feature": ["UNKNOWN_TAG"],
    "wip_missing_owner.feature": ["WIP_MISSING_OWNER"],
    "wip_missing_since.feature": ["WIP_MISSING_SINCE"],
    "bad_wip_owner.feature": ["BAD_WIP_OWNER"],
    "bad_wip_since.feature": ["BAD_WIP_SINCE"],
    "bad_defect.feature": ["BAD_DEFECT"],
    "jvm_tag.feature": ["BAD_BINDING_DECLARATION"],
}


@pytest.mark.parametrize("name,codes", sorted(EXPECTED_FEATURE_ERRORS.items()))
def test_each_broken_feature_reports_its_rule(name: str, codes: list[str], fixture_defs) -> None:
    problems = contract.check_feature_file(FIXTURE_FEATURES / name, fixture_defs)
    assert _codes(problems) == codes


def test_every_feature_fixture_is_expected() -> None:
    on_disk = {p.name for p in FIXTURE_FEATURES.glob("*.feature")}
    assert on_disk == set(EXPECTED_FEATURE_ERRORS) | {"valid.feature", "wip_unbound.feature"}


def test_valid_feature_is_clean(fixture_defs) -> None:
    assert contract.check_feature_file(FIXTURE_FEATURES / "valid.feature", fixture_defs) == []


def test_unbound_steps_of_a_wip_scenario_are_information_only(fixture_defs) -> None:
    problems = contract.check_feature_file(FIXTURE_FEATURES / "wip_unbound.feature", fixture_defs)
    assert _codes(problems) == []
    assert _codes(problems, "info") == ["UNBOUND_STEP"]


# Findings about a tag point at the tag's line, not at the scenario line below it.
TAG_LINES = {
    "bad_promise.feature": 3,
    "duplicate_promise.feature": 4,
    "feature_level_promise.feature": 1,
    "unknown_tag.feature": 3,
    "wip_missing_owner.feature": 4,
    "bad_wip_since.feature": 4,
    "jvm_tag.feature": 3,
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
    "jvm_declaration_without_source": ["BAD_BINDING_DECLARATION"],
    "bad_promise_binding": ["BAD_BINDING_DECLARATION"],
    "promise_binding_without_source": ["BAD_BINDING_DECLARATION"],
    "promise_not_indexed": ["PROMISE_NOT_INDEXED"],
    "indexed_promise_unused": ["INDEXED_PROMISE_UNUSED"],
    "duplicate_promise_across_files": ["DUPLICATE_PROMISE"],
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
    assert on_disk == set(EXPECTED_AREA_ERRORS) | {"valid"}


def test_valid_area_is_clean() -> None:
    assert _check_area("valid") == []


def test_step_sources_are_a_registry_keyed_by_binding_name() -> None:
    """A registered ``jvm`` source makes the jvm claims valid; the reader is chosen by name."""
    seen: list[Path] = []

    def jvm_reader(directory: Path):
        seen.append(directory)
        return [], []

    sources = {**contract.STEP_SOURCES, "jvm": jvm_reader}
    for name in ("jvm_declaration_without_source", "promise_binding_without_source"):
        problems = contract.check_tree(
            FIXTURE_AREAS / name,
            {"python": FIXTURE_STEPS, "jvm": FIXTURE_STEPS},
            sources=sources,
        )
        assert problems == []
    assert seen == [FIXTURE_STEPS, FIXTURE_STEPS]
    assert sorted(contract.STEP_SOURCES) == ["python"]


def test_steps_for_an_unregistered_binding_are_a_bad_binding_declaration() -> None:
    problems = contract.check_tree(
        FIXTURE_AREAS / "valid", {"python": FIXTURE_STEPS, "jvm": FIXTURE_STEPS}
    )
    assert _codes(problems) == ["BAD_BINDING_DECLARATION"]


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


def test_real_tree_is_clean_and_binds_through_python() -> None:
    assert contract.check_tree(REAL_FEATURES) == []
    defs, problems = contract.load_python_step_definitions(REAL_FEATURES)
    assert problems == []
    assert {d.binding for d in defs} == {"python"}
    assert any(p.parent.name == "envelope" for p in REAL_FEATURES.glob("*/README.md"))


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
