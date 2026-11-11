#!/usr/bin/env python3
"""Generate the write-fold differential cases from the package reducer.

``spec_kitty_events.diary.reduce`` is the reducer core's ``status/reducer.py`` imports, so the lane
it derives for a hand-written log is core's lane. Each case is written to
``service/command-cli/src/test/resources/fold-differential/<id>.json`` as
``{"id", "notes", "log", "expected"}``; ``expected`` maps each work package to its reduced lane, or
is ``{"unreducible": "<exception type>"}`` where the reducer refuses the log. The Java write fold
(``FoldDifferentialTest``) must reach the same lane or refuse the same logs.

Usage:
    python scripts/conformance/generate_fold_differential.py          # rewrite the files
    python scripts/conformance/generate_fold_differential.py --check  # exit 1 on drift
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any, Dict, List, Optional, Sequence

from spec_kitty_events.diary import reduce

OUTPUT_DIR = (
    Path(__file__).resolve().parents[2] / "service/command-cli/src/test/resources/fold-differential"
)

Row = Dict[str, Any]
Case = Dict[str, Any]

ULID_A = "01J00000000000000000000001"
ULID_B = "01J00000000000000000000002"
ULID_C = "01J00000000000000000000003"


def transition(
    event_id: Optional[str],
    wp_id: str,
    from_lane: str,
    to_lane: str,
    at: str,
    **extra: Any,
) -> Row:
    """One log row; ``event_id`` ``None`` is an id-less row (JSON ``null``)."""
    row: Row = {
        "event_id": event_id,
        "wp_id": wp_id,
        "from_lane": from_lane,
        "to_lane": to_lane,
        "at": at,
        "actor": "implementer-a",
        "force": False,
        "execution_mode": "worktree",
        "reason": None,
        "review_ref": None,
        "evidence": None,
        "policy_metadata": None,
    }
    row.update(extra)
    return row


def _cases() -> List[Case]:
    stale_forward = [
        transition(None, "WP01", "planned", "claimed", "2026-01-01T00:00:01+00:00"),
        transition(ULID_A, "WP01", "for_review", "in_review", "2026-01-01T00:00:02+00:00"),
    ]
    cascade = [
        transition(None, "WP01", "planned", "claimed", "2026-01-01T00:00:01+00:00"),
        transition(ULID_A, "WP01", "for_review", "in_review", "2026-01-01T00:00:02+00:00"),
        transition(ULID_C, "WP01", "claimed", "in_progress", "2026-01-01T00:00:03+00:00"),
    ]
    return [
        {
            "id": "idless-setter-stale-forward-applies",
            "notes": (
                "An id-less setter is no setter in core, so a later non-forced for_review->in_review "
                "has no continuity to fail and applies (in_review). The pre-fix Java fold found the "
                "id-less row itself as the setter and dropped the event (claimed). Divergence "
                "direction: Java dropped, core applied."
            ),
            "log": stale_forward,
        },
        {
            "id": "identified-control",
            "notes": (
                "Positive control: the same log with a ULID on the first row. The setter is found, "
                "continuity fails, and the event is dropped (claimed) in core and in Java."
            ),
            "log": [dict(stale_forward[0], event_id=ULID_B), stale_forward[1]],
        },
        {
            "id": "idless-setter-claimed-to-in-progress",
            "notes": (
                "Around claimed->in_progress (#231 4.16): an id-less planned->claimed, then a "
                "non-forced in_progress->for_review whose from_lane is not the lane in force. Core, with "
                "no setter, applies it (for_review); the pre-fix Java fold dropped it (claimed). "
                "Divergence direction: Java dropped, core applied. The cascade case below shows the opposite direction across a log."
            ),
            "log": [
                transition(None, "WP01", "planned", "claimed", "2026-01-01T00:00:01+00:00"),
                transition(
                    ULID_A, "WP01", "in_progress", "for_review", "2026-01-01T00:00:02+00:00"
                ),
            ],
        },
        {
            "id": "idless-setter-cascade-acknowledged-core-drops",
            "notes": (
                "Across a log, the pre-fix fold acknowledges what core drops. Row 2 (for_review->in_review) "
                "applies in core (no id-less setter) and row 3 (claimed->in_progress) is then dropped on "
                "continuity (in_review). The pre-fix fold dropped row 2 against the id-less row, so row 3 "
                "continued claimed and was applied (in_progress). Divergence direction: Java applied, core "
                "dropped."
            ),
            "log": cascade,
        },
        {
            "id": "identified-cascade-control",
            "notes": (
                "Control: the same cascade with a ULID on row 1. Row 1 is a found setter, row 2 is dropped on "
                "continuity and row 3 applies (in_progress) in core and in Java."
            ),
            "log": [dict(cascade[0], event_id=ULID_B)] + cascade[1:],
        },
        {
            "id": "idless-same-timestamp-unorderable",
            "notes": (
                "Same at: an id-less in_review->planned rollback and a forward planned->claimed share "
                "a timestamp. Because core never finds an id-less setter, arm (i) of the arbitration "
                "cannot fire for it; and the two rows tie on every sort key before the id, so the sort "
                "compares None with a string and raises TypeError: the log is unreducible in core and "
                "in Java (both folds refuse, before and after the fix). This pins that the same-timestamp "
                "arm is unreachable through an id-less setter on the plain path; no lane-differing log "
                "was constructible for it."
            ),
            "log": [
                transition(None, "WP01", "in_review", "planned", "2026-01-01T00:00:01+00:00"),
                transition(ULID_A, "WP01", "planned", "claimed", "2026-01-01T00:00:01+00:00"),
            ],
        },
        {
            "id": "idless-two-rows-dedupe",
            "notes": (
                "Core groups every id-less row under the one None key and keeps the first, so the "
                "second row is a duplicate and never applies (claimed). Java must group them the same "
                "way."
            ),
            "log": [
                transition(None, "WP01", "planned", "claimed", "2026-01-01T00:00:01+00:00"),
                transition(None, "WP01", "claimed", "in_progress", "2026-01-01T00:00:02+00:00"),
            ],
        },
        {
            "id": "idless-and-string-none",
            "notes": (
                "An id-less row and a row whose id is the string 'None' (different WPs, different at) "
                "are different keys in core, which folds the log. The pre-fix Java dedupe refused the "
                "log as ids that spell alike. Divergence direction: Java refused, core reduced."
            ),
            "log": [
                transition(None, "WP01", "planned", "claimed", "2026-01-01T00:00:01+00:00"),
                transition("None", "WP02", "planned", "claimed", "2026-01-01T00:00:02+00:00"),
            ],
        },
        {
            "id": "idless-versus-identified-unorderable",
            "notes": (
                "Same at on two WPs, one row id-less and one identified: the sort key's last component "
                "compares None with a string, which raises TypeError, so the log is unreducible in core "
                "and in Java."
            ),
            "log": [
                transition(None, "WP01", "planned", "claimed", "2026-01-01T00:00:01+00:00"),
                transition(ULID_A, "WP02", "planned", "claimed", "2026-01-01T00:00:01+00:00"),
            ],
        },
    ]


def _expected(log: Sequence[Row]) -> Dict[str, Any]:
    try:
        state = reduce([dict(row) for row in log])
    except TypeError as error:  # unorderable ids only; a malformed case must raise
        return {"unreducible": type(error).__name__}
    return {wp: data["lane"] for wp, data in sorted(state.work_packages.items())}


def build() -> Dict[str, str]:
    """File name to exact file text, for every case."""
    files: Dict[str, str] = {}
    for case in _cases():
        document = {**case, "expected": _expected(case["log"])}
        files[f"{case['id']}.json"] = json.dumps(document, indent=2, sort_keys=True) + "\n"
    return files


def drift(directory: Path = OUTPUT_DIR) -> List[str]:
    """Names of files that differ from a fresh derivation (missing, stale or unexpected)."""
    wanted = build()
    found = {path.name for path in directory.glob("*.json")}
    stale = [
        name
        for name, text in wanted.items()
        if not (directory / name).is_file()
        or (directory / name).read_text(encoding="utf-8") != text
    ]
    return sorted(set(stale) | (found - set(wanted)))


def main(argv: Optional[Sequence[str]] = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument(
        "--check", action="store_true", help="exit 1 if the files drift from the reducer"
    )
    args = parser.parse_args(argv)
    if args.check:
        problems = drift()
        for name in problems:
            print(f"drift: {name}", file=sys.stderr)
        return 1 if problems else 0
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    for name, text in build().items():
        (OUTPUT_DIR / name).write_text(text, encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
