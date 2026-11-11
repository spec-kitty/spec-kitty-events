"""The Sonar Java module list comes from Gradle settings and real source roots."""

from __future__ import annotations

import subprocess
import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "scripts" / "sonar_java_modules.py"
sys.path.insert(0, str(ROOT / "scripts"))
from sonar_java_modules import java_modules, line_counter  # noqa: E402


EXPECTED_MODULES = {
    (":java-binding", "java", "java/build/reports/jacoco/test/jacocoTestReport.xml"),
    (":mission-status-api", "service", "service/build/reports/jacoco/test/jacocoTestReport.xml"),
    (
        ":domain-model",
        "service/domain-model",
        "service/domain-model/build/reports/jacoco/test/jacocoTestReport.xml",
    ),
    (
        ":event-log",
        "service/event-log",
        "service/event-log/build/reports/jacoco/test/jacocoTestReport.xml",
    ),
    (
        ":command-cli",
        "service/command-cli",
        "service/command-cli/build/reports/jacoco/test/jacocoTestReport.xml",
    ),
}


def test_repository_modules_come_from_a_settings_copy_and_real_directories(
    tmp_path: Path,
) -> None:
    settings_copy = tmp_path / "settings.gradle.kts"
    settings_copy.write_text((ROOT / "settings.gradle.kts").read_text(encoding="utf-8"))

    assert set(java_modules(ROOT, settings_copy)) == EXPECTED_MODULES
    assert ":contracts" not in {name for name, _, _ in java_modules(ROOT)}
    assert ":mission-status-api" in {name for name, _, _ in java_modules(ROOT)}


def test_synthetic_module_needs_main_sources_and_defaults_to_its_project_name(
    tmp_path: Path,
) -> None:
    (tmp_path / "settings.gradle.kts").write_text(
        'include(":new-module")\n'
        'include(":multi-a", "multi-b")  // several paths, one without a colon\n'
        '// include(":commented-out")\n'
        'include(":without-main")\n'
        'include(":moved")\nproject(":moved").projectDir = file("elsewhere/moved")\n',
        encoding="utf-8",
    )
    for directory in ("new-module", "multi-a", "multi-b", "elsewhere/moved", "commented-out"):
        (tmp_path / directory / "src" / "main").mkdir(parents=True)
    (tmp_path / "without-main" / "src" / "test").mkdir(parents=True)

    suffix = "build/reports/jacoco/test/jacocoTestReport.xml"
    assert java_modules(tmp_path) == [
        (":moved", "elsewhere/moved", f"elsewhere/moved/{suffix}"),
        (":multi-a", "multi-a", f"multi-a/{suffix}"),
        (":multi-b", "multi-b", f"multi-b/{suffix}"),
        (":new-module", "new-module", f"new-module/{suffix}"),
    ]


@pytest.mark.parametrize(
    "settings",
    [
        "include(PROJECTS)\n",
        'include(":a")\nproject(":a").projectDir = File(rootDir, "a")\n',
    ],
)
def test_unparseable_settings_fail_instead_of_dropping_modules(
    tmp_path: Path, settings: str
) -> None:
    (tmp_path / "settings.gradle.kts").write_text(settings, encoding="utf-8")

    with pytest.raises(ValueError, match="settings.gradle.kts"):
        java_modules(tmp_path)


def test_cli_prints_tab_separated_module_report_rows() -> None:
    result = subprocess.run(
        [sys.executable, str(SCRIPT)],
        cwd=ROOT,
        check=True,
        capture_output=True,
        text=True,
    )

    assert result.stdout.splitlines() == ["\t".join(module) for module in java_modules(ROOT)]


def test_line_counter_reads_the_report_level_line_counter(tmp_path: Path) -> None:
    report = tmp_path / "jacocoTestReport.xml"
    report.write_text(
        '<report name="m"><package name="p"><counter type="LINE" missed="9" covered="9"/></package>'
        '<counter type="BRANCH" missed="1" covered="2"/><counter type="LINE" missed="38" covered="527"/></report>',
        encoding="utf-8",
    )

    assert line_counter(report) == (38, 527)


def test_line_counter_is_none_for_a_missing_malformed_or_counterless_report(tmp_path: Path) -> None:
    broken = tmp_path / "broken.xml"
    broken.write_text("<report", encoding="utf-8")
    bare = tmp_path / "bare.xml"
    bare.write_text('<report name="m"/>', encoding="utf-8")

    assert line_counter(tmp_path / "absent.xml") is None
    assert line_counter(broken) is None
    assert line_counter(bare) is None
