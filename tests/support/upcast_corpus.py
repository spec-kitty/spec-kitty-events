"""The upcast corpus lister for the BDD scenarios (test-only; spec-kitty-events#199).

Lists the cases under ``producer_contract/fixtures/upcast/cases/<group>/`` through the installed
package, so the ``upcast/`` scenarios judge the corpus the package ships. The Java binding reads
the same files through ``-Dupcast.cases`` (``java/build.gradle.kts``). Not part of the package:
corpus group names are test taxonomy, not public API (C-007).
"""

from __future__ import annotations

import json
from dataclasses import dataclass
from importlib.resources import files
from pathlib import Path
from typing import Any

UPCAST_CASES_ROOT: Path = Path(
    str(files("spec_kitty_events.producer_contract").joinpath("fixtures/upcast/cases"))
)


@dataclass(frozen=True)
class UpcastCase:
    """One ``cases/<group>/<case>.json`` file of the upcast corpus."""

    group: str
    path: Path
    data: dict[str, Any]

    @property
    def rel_path(self) -> str:
        return self.path.relative_to(UPCAST_CASES_ROOT).as_posix()

    @property
    def line(self) -> str:
        line: str = self.data["line"]
        return line

    @property
    def context(self) -> dict[str, str]:
        context: dict[str, str] = self.data["context"]
        return context

    @property
    def expected(self) -> dict[str, Any]:
        expected: dict[str, Any] = self.data["expected"]
        return expected


def list_upcast_cases(group: str, outcome: str | None = None) -> list[UpcastCase]:
    """Every case of ``group``, sorted by path; only those pinned as ``outcome`` when given."""
    cases = []
    for path in sorted((UPCAST_CASES_ROOT / group).glob("*.json")):
        with path.open("r", encoding="utf-8") as fh:
            case = UpcastCase(group, path, json.load(fh))
        if outcome is None or case.expected["outcome"] == outcome:
            cases.append(case)
    return cases
