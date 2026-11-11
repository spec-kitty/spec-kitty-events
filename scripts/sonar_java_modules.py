#!/usr/bin/env python3
"""List Gradle projects with main Java sources and their Sonar JaCoCo report paths."""

from __future__ import annotations

import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

Module = tuple[str, str, str]

_LINE_COMMENT = re.compile(r"//[^\n]*")
_INCLUDE_CALL = re.compile(r"\binclude\(([^)]*)\)")
_QUOTED = re.compile(r'"([^"]+)"')
_PROJECT_DIR = re.compile(
    r'\bproject\(\s*"(:?[^"]+)"\s*\)\.projectDir\s*=\s*file\(\s*"([^"]+)"\s*\)',
)
_REPORT_SUFFIX = "build/reports/jacoco/test/jacocoTestReport.xml"


def _project_path(name: str) -> str:
    return name if name.startswith(":") else f":{name}"


def java_modules(root: Path, settings_path: Path | None = None) -> list[Module]:
    """Return ``(project name, directory, report)`` rows for projects with main Java sources."""
    settings = settings_path if settings_path is not None else root / "settings.gradle.kts"
    text = _LINE_COMMENT.sub("", settings.read_text(encoding="utf-8"))
    project_dirs = {_project_path(m.group(1)): m.group(2) for m in _PROJECT_DIR.finditer(text)}
    if len(project_dirs) != text.count(".projectDir"):
        raise ValueError(
            f'{settings}: a projectDir assignment is not project("..").projectDir = file("..")'
        )

    names: list[str] = []
    include_calls = 0
    for call in _INCLUDE_CALL.finditer(text):
        arguments = _QUOTED.findall(call.group(1))
        if arguments:
            include_calls += 1
        names.extend(_project_path(argument) for argument in arguments)
    if include_calls != len(re.findall(r"\binclude\(", text)):
        raise ValueError(f"{settings}: an include(...) call has no quoted project path")

    modules: list[Module] = []
    for name in names:
        directory = project_dirs.get(name, name.lstrip(":").replace(":", "/"))
        if (root / directory / "src" / "main" / "java").is_dir():
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
