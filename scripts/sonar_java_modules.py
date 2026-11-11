#!/usr/bin/env python3
"""List Gradle projects with main sources and their Sonar JaCoCo report paths."""

from __future__ import annotations

import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

Module = tuple[str, str, str]

_INCLUDE = re.compile(r'^\s*include\(\s*"(:[^"]+)"\s*\)', re.MULTILINE)
_PROJECT_DIR = re.compile(
    r'^\s*project\(\s*"(:[^"]+)"\s*\)\.projectDir\s*=\s*file\(\s*"([^"]+)"\s*\)',
    re.MULTILINE,
)
_REPORT_SUFFIX = "build/reports/jacoco/test/jacocoTestReport.xml"


def java_modules(root: Path, settings_path: Path | None = None) -> list[Module]:
    """Return ``(project name, directory, report)`` rows for projects with main sources."""
    settings = settings_path if settings_path is not None else root / "settings.gradle.kts"
    text = settings.read_text(encoding="utf-8")
    project_dirs = {match.group(1): match.group(2) for match in _PROJECT_DIR.finditer(text)}

    modules: list[Module] = []
    for match in _INCLUDE.finditer(text):
        name = match.group(1)
        directory = project_dirs.get(name, name.lstrip(":").replace(":", "/"))
        if (root / directory / "src" / "main").is_dir():
            report = (Path(directory) / _REPORT_SUFFIX).as_posix()
            modules.append((name, directory, report))
    return sorted(modules)


def line_counter(report: Path) -> tuple[int, int] | None:
    """Return the report-level JaCoCo ``LINE`` counter as ``(missed, covered)``, or None."""
    try:
        root = ET.parse(report).getroot()
    except (OSError, ET.ParseError):
        return None
    for counter in root.findall("counter"):
        if counter.get("type") == "LINE":
            return int(counter.get("missed", "0")), int(counter.get("covered", "0"))
    return None


def main(argv: list[str] | None = None) -> int:
    """Print one tab-separated row per module; ``--coverage`` adds each report's line counter.

    Plain rows are ``name<TAB>directory<TAB>report``. With ``--coverage`` the rows are
    ``name<TAB>missed<TAB>covered`` for the modules whose report exists.
    """
    args = sys.argv[1:] if argv is None else argv
    root = Path(__file__).resolve().parents[1]
    coverage = "--coverage" in args
    for name, directory, report in java_modules(root):
        if not coverage:
            print("\t".join((name, directory, report)))
            continue
        counter = line_counter(root / report)
        if counter is not None:
            print(f"{name}\t{counter[0]}\t{counter[1]}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
