"""Lists the replay streams of a fixture folder with the reducer output each pins (test-only).

A replay folder (``conformance/fixtures/<category>/replay/``) pairs every ``<stem>.jsonl``
stream with the golden ``<stem>_output.json`` its fold must reproduce. The pairing is by file
name, so a stream added to a covered folder is picked up by the BDD steps under
``tests/scenarios/replay/`` without editing a feature file; a stream without its output is an
error, never a skip (#198). Not package API (C-007).
"""

from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from tests.support.class_taxonomy import FIXTURES_ROOT

OUTPUT_SUFFIX = "_output.json"


@dataclass(frozen=True)
class ReplayPair:
    """One replay stream and the reducer output its fixture pins."""

    stream: Path
    output: Path

    @property
    def rel_path(self) -> str:
        return self.stream.relative_to(FIXTURES_ROOT).as_posix()

    def rows(self) -> list[dict[str, Any]]:
        lines = self.stream.read_text(encoding="utf-8").splitlines()
        return [json.loads(line) for line in lines if line.strip()]

    def pinned_output(self) -> Any:
        return json.loads(self.output.read_text(encoding="utf-8"))


def list_replay_pairs(folder: str) -> list[ReplayPair]:
    """Every stream under ``FIXTURES_ROOT/folder`` paired with its output, sorted by name.

    Raises ``FileNotFoundError`` when the folder is missing or a stream has no output, and
    ``ValueError`` when an output has no stream.
    """
    root = FIXTURES_ROOT / folder
    if not root.is_dir():
        raise FileNotFoundError(f"no fixture folder {folder!r} under {FIXTURES_ROOT}")
    pairs = []
    for stream in sorted(root.glob("*.jsonl")):
        output = stream.with_name(stream.stem + OUTPUT_SUFFIX)
        if not output.is_file():
            raise FileNotFoundError(f"{stream.name} has no pinned output {output.name}")
        pairs.append(ReplayPair(stream, output))
    streams = {pair.output.name for pair in pairs}
    orphans = sorted(p.name for p in root.glob("*" + OUTPUT_SUFFIX) if p.name not in streams)
    if orphans:
        raise ValueError(f"outputs without a stream in {folder!r}: {orphans}")
    return pairs
