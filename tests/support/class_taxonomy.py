"""The one fixture-class lister for ``conformance/fixtures/class_taxonomy/`` (test-only).

``tests/test_fixture_determinism.py``, ``tests/test_conformance_classes.py``,
``tests/test_envelope_strict_journal_class.py`` and the BDD scenarios under
``tests/scenarios/`` all list the class-taxonomy fixtures through
:func:`list_class_fixtures`, so a fixture added to a class directory is picked up by every one
of them without a second copy of the listing logic (spec-kitty-events#197).

It is deliberately not part of the package: test-taxonomy names are not public API (C-007).
"""

from __future__ import annotations

import json
from dataclasses import dataclass
from functools import lru_cache
from importlib.resources import files
from pathlib import Path
from typing import Any

# Read through the installed package, so the scenarios judge the fixtures the package ships
# (with the editable install the suite runs under, this is src/spec_kitty_events/...).
FIXTURES_ROOT: Path = Path(str(files("spec_kitty_events.conformance").joinpath("fixtures")))
MANIFEST_PATH: Path = FIXTURES_ROOT / "manifest.json"
CLASS_TAXONOMY_DIR: str = "class_taxonomy"


@dataclass(frozen=True)
class ClassFixtureEntry:
    """A ``manifest.json`` ``classes.entries`` row."""

    id: str
    fixture_class: str
    path: str
    expected: str
    expected_error_code: str | None
    event_type: str


@dataclass(frozen=True)
class ClassFixture:
    """One JSON fixture file in a class directory, with its manifest row when it has one."""

    fixture_class: str
    path: Path
    rel_path: str
    entry: ClassFixtureEntry | None

    @property
    def name(self) -> str:
        """The file name without ``.json``, for example ``mission_created_baseline``."""
        return self.path.stem

    def load(self) -> dict[str, Any]:
        with self.path.open("r", encoding="utf-8") as fh:
            data: dict[str, Any] = json.load(fh)
        return data


@lru_cache(maxsize=1)
def manifest_class_entries() -> tuple[ClassFixtureEntry, ...]:
    """Every ``classes.entries`` row of ``manifest.json``, in manifest order."""
    with MANIFEST_PATH.open("r", encoding="utf-8") as fh:
        manifest: dict[str, Any] = json.load(fh)
    return tuple(
        ClassFixtureEntry(
            id=raw["id"],
            fixture_class=raw["class"],
            path=raw["path"],
            expected=raw["expected"],
            expected_error_code=raw.get("expected_error_code"),
            event_type=raw.get("event_type", "<n/a>"),
        )
        for raw in manifest["classes"]["entries"]
    )


def list_class_fixtures(*classes: str) -> list[ClassFixture]:
    """Every ``*.json`` fixture under ``class_taxonomy/<class>/`` (recursively), sorted by path.

    A class directory that does not exist yields nothing; callers that need a non-empty class
    assert that themselves.
    """
    entries = {entry.path: entry for entry in manifest_class_entries()}
    found: list[ClassFixture] = []
    for fixture_class in classes:
        directory = FIXTURES_ROOT / CLASS_TAXONOMY_DIR / fixture_class
        if not directory.is_dir():
            continue
        for path in directory.rglob("*.json"):
            rel_path = path.relative_to(FIXTURES_ROOT).as_posix()
            found.append(ClassFixture(fixture_class, path, rel_path, entries.get(rel_path)))
    return sorted(found, key=lambda fixture: fixture.rel_path)
