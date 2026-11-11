"""Structure of the nightly SonarCloud workflow (#232).

The scan is informational: it runs on `schedule` and `workflow_dispatch` only, never
on `pull_request`, so it cannot enter a required-check chain or gate a merge. Without
`SONAR_TOKEN` it skips green. Coverage is generated in the same job from the
checked-out revision; the workflow never downloads another run's artefacts.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path, PurePosixPath
from typing import Any

import pytest
import yaml

ROOT = Path(__file__).resolve().parents[1]
WORKFLOWS = ROOT / ".github" / "workflows"
SONAR = WORKFLOWS / "sonar.yml"
PROPERTIES = ROOT / "sonar-project.properties"
sys.path.insert(0, str(ROOT / "scripts"))

PIN_LINE = re.compile(r"^[^@\s]+@[0-9a-f]{40} # v\d+\.\d+\.\d+$")
TOKEN_OUTPUT = "steps.sonar_token.outputs.enabled == 'true'"
BUILD_OUTCOMES = {
    "PYTEST_OUTCOME": "pytest",
    "JDK_OUTCOME": "jdk",
    "BUNDLE_OUTCOME": "bundle",
    "GRADLE_OUTCOME": "gradle",
    "CLASSPATHS_OUTCOME": "classpaths",
}


def _load(path: Path) -> dict[str, Any]:
    # BaseLoader keeps `on` a string key.
    data: dict[str, Any] = yaml.load(path.read_text(encoding="utf-8"), Loader=yaml.BaseLoader)
    return data


def _steps() -> list[dict[str, Any]]:
    steps: list[dict[str, Any]] = _load(SONAR)["jobs"]["scan"]["steps"]
    return steps


def _step(step_id: str) -> dict[str, Any]:
    return next(s for s in _steps() if s.get("id") == step_id)


def _properties() -> dict[str, str]:
    entries: dict[str, str] = {}
    for line in PROPERTIES.read_text(encoding="utf-8").splitlines():
        if line and not line.lstrip().startswith("#") and "=" in line:
            key, value = line.split("=", 1)
            entries[key.strip()] = value.strip()
    return entries


def _paths_overlap(left: str, right: str) -> bool:
    """Whether either path equals or contains the other, by whole path components."""
    left_parts = PurePosixPath(left).parts
    right_parts = PurePosixPath(right).parts
    return (
        left_parts == right_parts[: len(left_parts)]
        or right_parts == left_parts[: len(right_parts)]
    )


def _java_modules() -> list[tuple[str, str, str]]:
    from sonar_java_modules import java_modules

    return java_modules(ROOT)


def test_triggers_are_schedule_and_workflow_dispatch_only() -> None:
    triggers = _load(SONAR)["on"]
    assert set(triggers) == {"schedule", "workflow_dispatch"}
    assert "pull_request" not in triggers


def test_nightly_cron_is_off_the_hour_and_clear_of_every_other_workflow_cron() -> None:
    cron = _load(SONAR)["on"]["schedule"][0]["cron"]
    fields = cron.split()
    assert len(fields) == 5
    assert fields[0] != "0", "the nightly must not run on the hour"

    other_crons = {
        entry["cron"]
        for path in WORKFLOWS.glob("*.yml")
        if path != SONAR
        for entry in (_load(path)["on"].get("schedule") or [])
    }
    assert other_crons, "no cron parsed from the sibling workflows; the collision check is vacuous"
    assert cron not in other_crons


def test_workflow_permissions_are_contents_read_only() -> None:
    assert _load(SONAR)["permissions"] == {"contents": "read"}


def test_missing_token_skips_green_with_a_notice() -> None:
    step = _step("sonar_token")
    run = step["run"]
    assert step["env"]["SONAR_TOKEN"] == "${{ secrets.SONAR_TOKEN }}"
    assert "enabled=false" in run
    assert "::notice::" in run
    assert "enabled=true" in run


def test_scan_and_build_steps_are_gated_by_the_token_guard() -> None:
    assert TOKEN_OUTPUT in _step("coverage")["if"]
    # The scan itself runs whenever the token is present, even when coverage is absent.
    assert TOKEN_OUTPUT in _step("scan")["if"]


@pytest.mark.parametrize("step_id", ["coverage", "scan", "quality_gate", "report"])
def test_analysis_steps_continue_after_failures_but_not_cancellation(step_id: str) -> None:
    assert _step(step_id)["if"] == f"!cancelled() && {TOKEN_OUTPUT}"


def test_always_is_not_used_anywhere_even_in_comments() -> None:
    assert "always()" not in SONAR.read_text(encoding="utf-8")


def test_scan_environment_contains_only_the_sonar_token() -> None:
    assert set(_step("scan")["env"]) == {"SONAR_TOKEN"}


def test_build_steps_do_not_receive_the_sonar_token() -> None:
    token_steps = {"sonar_token", "scan", "quality_gate"}
    for step in _steps():
        if step.get("id") not in token_steps:
            assert "SONAR_TOKEN" not in step.get("env", {}), step["name"]


def test_run_blocks_never_interpolate_actions_expressions() -> None:
    for step in _steps():
        assert "${{" not in step.get("run", ""), step["name"]


def test_every_action_is_pinned_to_a_full_commit_sha_with_a_version_comment() -> None:
    # YAML drops the trailing comment, so check the raw lines as written.
    uses = [
        line.strip().removeprefix("- ")
        for line in SONAR.read_text(encoding="utf-8").splitlines()
        if line.strip().removeprefix("- ").startswith("uses:")
    ]
    assert uses
    for line in uses:
        ref = line.removeprefix("uses:").strip()
        if ref.startswith("./"):
            continue  # a local reusable workflow
        assert PIN_LINE.match(ref), (
            f"sonar.yml: {ref} is not pinned by a full SHA with a version comment"
        )


def test_coverage_is_generated_in_job_not_downloaded_from_another_run() -> None:
    text = SONAR.read_text(encoding="utf-8")
    assert "actions/download-artifact" not in text
    run = _step("coverage")["run"]
    assert "coverage.xml" in run
    assert "jacocoTestReport.xml" in run
    assert "sonar.java.test.libraries" in run
    # The reports are bound to the revision being scanned.
    assert "git rev-parse HEAD" in run


def test_collect_derives_reports_and_joins_the_gradle_classpath_files() -> None:
    run = _step("coverage")["run"]
    assert "python3 scripts/sonar_java_modules.py" in run
    assert "::warning::sonar: no JaCoCo report for $name ($report)" in run
    assert "libraries.txt" in run
    assert "test-libraries.txt" in run
    assert "-Dsonar.java.libraries=" in run
    assert "-Dsonar.java.test.libraries=" in run
    assert "paste -sd," in run
    assert "GITHUB_STEP_SUMMARY" in run
    assert "fallback" in run
    assert "python3 scripts/sonar_java_modules.py --coverage" in run
    assert "lines covered" in run


def test_explicit_classpath_task_is_best_effort_and_gradle_gated() -> None:
    step = _step("classpaths")
    assert step["run"] == "./gradlew --no-daemon sonarLibraries"
    assert step["continue-on-error"] == "true"
    assert step["if"] == _step("gradle")["if"]
    assert _steps().index(step) == _steps().index(_step("gradle")) + 1


def test_failed_step_summary_receives_outcomes_through_environment() -> None:
    step = _step("report")
    for env_name, step_id in BUILD_OUTCOMES.items():
        assert step["env"][env_name] == f"${{{{ steps.{step_id}.outcome }}}}"
        assert f"${env_name}" in step["run"]
    assert '"failure"' in step["run"]
    assert "none failed" in step["run"]
    assert "GITHUB_STEP_SUMMARY" in step["run"]


def test_sonar_project_identity() -> None:
    properties = _properties()
    assert properties["sonar.projectKey"] == "spec-kitty_spec-kitty-events"
    assert properties["sonar.organization"] == "spec-kitty"
    assert "src" in properties["sonar.sources"].split(",")
    assert "java/src/main/java" in properties["sonar.sources"].split(",")
    assert "service/src/main/java" in properties["sonar.sources"].split(",")


@pytest.mark.parametrize(
    ("left", "right", "expected"),
    [
        ("java/src", "java/src/test/java", True),
        ("java/src/test/java", "java/src", True),
        ("java/src", "java/src", True),
        ("java/src/main/java", "java/src/test/java", False),
        ("java/src", "java/src-other/test/java", False),
    ],
)
def test_source_test_overlap_uses_path_components(left: str, right: str, expected: bool) -> None:
    assert _paths_overlap(left, right) is expected


def test_sonar_source_roots_do_not_overlap_test_roots() -> None:
    properties = _properties()
    sources = properties["sonar.sources"].split(",")
    tests = properties["sonar.tests"].split(",")
    assert not any(_paths_overlap(source, test) for source in sources for test in tests)


def test_packaged_conformance_tests_are_test_sources() -> None:
    assert _properties()["sonar.test.inclusions"] == "**/test_*.py"


@pytest.mark.parametrize(
    "key",
    ["sonar.python.coverage.reportPaths", "sonar.coverage.jacoco.xmlReportPaths"],
)
def test_coverage_report_properties_are_declared(key: str) -> None:
    assert key in _properties()


def test_every_derived_java_module_uses_the_shared_jacoco_xml_wiring() -> None:
    modules = _java_modules()
    assert modules
    convention = (ROOT / "build-logic/src/main/kotlin/events.jacoco-xml.gradle.kts").read_text(
        encoding="utf-8"
    )
    assert 'finalizedBy("jacocoTestReport")' in convention
    assert "xml.required.set(true)" in convention

    for name, directory, report in modules:
        assert report == f"{directory}/build/reports/jacoco/test/jacocoTestReport.xml"
        build_script = (ROOT / directory / "build.gradle.kts").read_text(encoding="utf-8")
        applies_convention = (
            'id("events.library")' in build_script or 'id("events.service")' in build_script
        )
        enables_xml_itself = (
            'finalizedBy("jacocoTestReport")' in build_script
            and "xml.required.set(true)" in build_script
        )
        assert applies_convention or enables_xml_itself, name

    for plugin in ("events.library", "events.service"):
        convention_script = (ROOT / f"build-logic/src/main/kotlin/{plugin}.gradle.kts").read_text(
            encoding="utf-8"
        )
        assert 'id("events.jacoco-xml")' in convention_script
