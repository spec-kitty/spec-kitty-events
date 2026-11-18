"""The cutover-boundary fixture lister for the BDD scenarios (test-only; spec-kitty-events#199).

Lists ``conformance/fixtures/cutover_boundary/<directory>/*.json`` through the installed package,
each with its ``manifest.json`` row (the row pins the event type and the expected result). A file
without a manifest row is an error, so a new fixture is never judged against nothing. Not part of
the package: the directory names are test taxonomy, not public API (C-007).
"""

from __future__ import annotations

import json
from dataclasses import dataclass
from functools import lru_cache
from pathlib import Path
from typing import Any

from tests.support.class_taxonomy import FIXTURES_ROOT, MANIFEST_PATH

CUTOVER_BOUNDARY_DIR: str = "cutover_boundary"


@dataclass(frozen=True)
class BoundaryFixture:
    """One cutover-boundary fixture file and its manifest row."""

    path: Path
    rel_path: str
    entry: dict[str, Any]

    @property
    def event_type(self) -> str:
        event_type: str = self.entry["event_type"]
        return event_type

    @property
    def expected_result(self) -> str:
        expected: str = self.entry["expected_result"]
        return expected

    def load(self) -> dict[str, Any]:
        with self.path.open("r", encoding="utf-8") as fh:
            data: dict[str, Any] = json.load(fh)
        return data


@lru_cache(maxsize=1)
def _manifest_rows() -> dict[str, dict[str, Any]]:
    with MANIFEST_PATH.open("r", encoding="utf-8") as fh:
        manifest: dict[str, Any] = json.load(fh)
    return {
        row["path"]: row
        for row in manifest["fixtures"]
        if row["path"].startswith(f"{CUTOVER_BOUNDARY_DIR}/")
    }


def list_boundary_fixtures(directory: str) -> list[BoundaryFixture]:
    """Every fixture under ``cutover_boundary/<directory>/``, sorted by path, with its row."""
    rows = _manifest_rows()
    fixtures = []
    for path in sorted((FIXTURES_ROOT / CUTOVER_BOUNDARY_DIR / directory).glob("*.json")):
        rel_path = path.relative_to(FIXTURES_ROOT).as_posix()
        entry = rows.get(rel_path)
        if entry is None:
            raise LookupError(f"{rel_path} has no manifest.json row")
        fixtures.append(BoundaryFixture(path, rel_path, entry))
    return fixtures
