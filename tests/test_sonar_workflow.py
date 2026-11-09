"""Structure of the nightly SonarCloud workflow (#232).

The scan is informational: it runs on `schedule` and `workflow_dispatch` only, never
on `pull_request`, so it cannot enter a required-check chain or gate a merge. Without
`SONAR_TOKEN` it skips green. Coverage is generated in the same job from the
checked-out revision; the workflow never downloads another run's artefacts.
"""

from __future__ import annotations

import re
from pathlib import Path
from typing import Any

import pytest
import yaml

ROOT = Path(__file__).resolve().parents[1]
WORKFLOWS = ROOT / ".github" / "workflows"
SONAR = WORKFLOWS / "sonar.yml"
PROPERTIES = ROOT / "sonar-project.properties"

PIN_LINE = re.compile(r"^[^@\s]+@[0-9a-f]{40} # v\d+\.\d+\.\d+$")
EXISTING_CRONS = {"41 3 * * *", "23 5 * * *"}


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


def test_triggers_are_schedule_and_workflow_dispatch_only() -> None:
    triggers = _load(SONAR)["on"]
    assert set(triggers) == {"schedule", "workflow_dispatch"}
    assert "pull_request" not in triggers


def test_nightly_cron_is_off_the_hour_and_clear_of_the_existing_crons() -> None:
    cron = _load(SONAR)["on"]["schedule"][0]["cron"]
    fields = cron.split()
    assert len(fields) == 5
    assert fields[0] != "0", "the nightly must not run on the hour"
    assert cron not in EXISTING_CRONS


def test_missing_token_skips_green_with_a_notice() -> None:
    step = _step("sonar_token")
    run = step["run"]
    assert step["env"]["SONAR_TOKEN"] == "${{ secrets.SONAR_TOKEN }}"
    assert "enabled=false" in run
    assert "::notice::" in run
    assert "enabled=true" in run


def test_scan_and_build_steps_are_gated_by_the_token_guard() -> None:
    token_output = "steps.sonar_token.outputs.enabled == 'true'"
    assert token_output in _step("coverage")["if"]
    # The scan itself runs whenever the token is present, even when coverage is absent.
    assert token_output in next(s for s in _steps() if s.get("name") == "SonarCloud Scan")["if"]


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


def test_sonar_project_identity() -> None:
    properties = _properties()
    assert properties["sonar.projectKey"] == "spec-kitty_spec-kitty-events"
    assert properties["sonar.organization"] == "spec-kitty"
    assert "src" in properties["sonar.sources"].split(",")
    assert "java/src/main/java" in properties["sonar.sources"].split(",")
    assert "service/src/main/java" in properties["sonar.sources"].split(",")


@pytest.mark.parametrize(
    "key",
    ["sonar.python.coverage.reportPaths", "sonar.coverage.jacoco.xmlReportPaths"],
)
def test_coverage_report_properties_are_declared(key: str) -> None:
    assert key in _properties()
