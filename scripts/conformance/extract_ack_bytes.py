#!/usr/bin/env python3
"""Extract the bytes each acknowledged parity case must append to the status log.

For every ``expected.result == "ack"`` case of the status-transition parity corpus this
tool computes, per expected unit, the exact text the write service appends: ``""`` when the
unit is a replay (``replayed: true``), otherwise the unit's request rows serialised one per
line, in order, each ending in LF. The service appends exactly its non-replayed units' rows
(``WriteService.appendNewUnits``), so a batch, the two-unit start-from-planned batch and an
annotation unit all follow the same rule.

Output: ``service/command-cli/src/test/resources/conformance/ack-bytes.json``, a test
resource of the service (not part of the packaged contract). Operator ruling M2-R2: the
expected bytes are extracted from core and committed here; the Java tests read only the
committed file and have no coupling to core.

Modes (``--serializer``):

* ``core`` (default): imports core's ``serialize_event_line`` from the pinned checkout named
  by ``SPEC_KITTY_CORE_CHECKOUT``, with core's own Python 3.11 and the pinned SHA, exactly
  like ``generate_status_append.py``. This is the authoritative mode and the only one CI's
  ``write-core-parity`` job runs (``--check``), proving the committed file equals core's output.
* ``encoder``: the package's ``encode_status_row``. The committed ``status_append`` oracle
  proves that encoder core-exact for such rows. The committed resource was BOOTSTRAPPED in this
  mode because the authoring session could not execute core code. If CI's core ``--check``
  reports drift, the encoder is not core-exact for some row: escalate, never hand-edit the file.

``--check`` writes nothing and exits 1, listing the drifting case ids, when the committed file
differs from the extraction.
"""

from __future__ import annotations

import argparse
import importlib
import json
import os
import platform
import subprocess
import sys
from collections.abc import Callable, Mapping, Sequence
from pathlib import Path
from typing import Any

PINNED_PYTHON = (3, 11)
PINNED_CORE_SHA = "512567eee71e31bb39f580c4f7acf8b865e9d1b2"
ROOT = Path(__file__).resolve().parents[2]
CASES_DIR = (
    ROOT / "src" / "spec_kitty_events" / "conformance" / "status_transition_parity" / "cases"
)
OUTPUT = (
    ROOT
    / "service"
    / "command-cli"
    / "src"
    / "test"
    / "resources"
    / "conformance"
    / "ack-bytes.json"
)
Serializer = Callable[[Mapping[str, Any]], str]
"""Serialises one row to its log line WITHOUT the trailing LF."""


def unit_texts(case: Mapping[str, Any], serialize: Serializer) -> list[str]:
    """Return the appended text of each expected unit of an ack case."""
    request_units = case["request"]["units"]
    expected_units = case["expected"]["units"]
    if len(request_units) != len(expected_units):
        raise ValueError(f"{case['id']}: request and expected unit counts differ")
    texts: list[str] = []
    for request, expected in zip(request_units, expected_units, strict=True):
        if expected["replayed"]:
            texts.append("")
            continue
        texts.append("".join(serialize(row) + "\n" for row in request["rows"]))
    return texts


def extract(cases_dir: Path, serialize: Serializer) -> dict[str, list[str]]:
    """Return ``{case id: [unit text, ...]}`` for every ack case under ``cases_dir``."""
    result: dict[str, list[str]] = {}
    for path in sorted(cases_dir.glob("*.json")):
        case = json.loads(path.read_text(encoding="utf-8"))
        if case["expected"]["result"] == "ack":
            result[case["id"]] = unit_texts(case, serialize)
    return result


def render(extracted: Mapping[str, Sequence[str]]) -> str:
    """Return the deterministic committed text."""
    return json.dumps(extracted, indent=2, sort_keys=True, ensure_ascii=True) + "\n"


def drift(output: Path, extracted: Mapping[str, Sequence[str]]) -> list[str]:
    """Return changed, missing and extra case ids against the committed file."""
    if not output.is_file():
        return sorted(extracted)
    committed = json.loads(output.read_text(encoding="utf-8"))
    ids = set(committed) | set(extracted)
    changed = {i for i in ids if committed.get(i) != extracted.get(i)}
    if not changed and output.read_text(encoding="utf-8") != render(extracted):
        return ["<file formatting>"]
    return sorted(changed)


def encoder_serializer() -> Serializer:
    """The package's byte encoder, minus the trailing LF."""
    from spec_kitty_events.status_log import encode_status_row

    def serialize(row: Mapping[str, Any]) -> str:
        line = encode_status_row(row).decode("ascii")
        if not line.endswith("\n"):
            raise RuntimeError("encode_status_row did not end its line with LF")
        return line[:-1]

    return serialize


def core_serializer(checkout: Path) -> Serializer:
    """Core's serializer at the pinned checkout (CI only; the package never imports core)."""
    sys.path.insert(0, str(checkout / "src"))
    store = importlib.import_module("specify_cli.status.store")

    def serialize(row: Mapping[str, Any]) -> str:
        return str(store.serialize_event_line(row))

    return serialize


def _checkout_head(checkout: Path) -> str | None:
    result = subprocess.run(  # noqa: S603 -- fixed argv, test-only oracle
        ["git", "-C", str(checkout), "rev-parse", "HEAD"],  # noqa: S607
        capture_output=True,
        text=True,
        check=False,
    )
    return result.stdout.strip() if result.returncode == 0 else None


def _pinned_core_serializer() -> tuple[Serializer | None, int]:
    checkout_value = os.environ.get("SPEC_KITTY_CORE_CHECKOUT")
    if not checkout_value:
        print("SPEC_KITTY_CORE_CHECKOUT must name the pinned core checkout", file=sys.stderr)
        return None, 2
    if sys.version_info[:2] != PINNED_PYTHON:
        required = ".".join(str(part) for part in PINNED_PYTHON)
        print(
            f"ack-bytes core mode requires Python {required}, found {platform.python_version()}",
            file=sys.stderr,
        )
        return None, 2
    head = _checkout_head(Path(checkout_value))
    if head != PINNED_CORE_SHA:
        print(
            f"ack-bytes core mode requires core at {PINNED_CORE_SHA}, found {head or 'no git checkout'}",
            file=sys.stderr,
        )
        return None, 2
    return core_serializer(Path(checkout_value)), 0


def main(argv: Sequence[str] | None = None) -> int:
    """Write or check the committed ack-bytes resource."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="diff instead of writing")
    parser.add_argument("--serializer", choices=("core", "encoder"), default="core")
    args = parser.parse_args(argv)
    if args.serializer == "core":
        serialize, status = _pinned_core_serializer()
        if serialize is None:
            return status
    else:
        serialize = encoder_serializer()
    extracted = extract(CASES_DIR, serialize)
    if args.check:
        changed = drift(OUTPUT, extracted)
        for case_id in changed:
            print(f"drift: {case_id}")
        if changed:
            return 1
    else:
        OUTPUT.parent.mkdir(parents=True, exist_ok=True)
        OUTPUT.write_text(render(extracted), encoding="utf-8")
    print(f"ack-bytes ({args.serializer}): {len(extracted)} ack cases")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
