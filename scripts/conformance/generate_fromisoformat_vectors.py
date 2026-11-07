#!/usr/bin/env python3
"""Generate the Java PyIsoDateTime oracle with core's pinned Python interpreter."""

from __future__ import annotations

import argparse
import difflib
import json
import sys
from collections.abc import Sequence
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUTPUT = ROOT / "service/domain-model/src/test/resources/pyisodatetime/vectors.json"
PINNED_PYTHON = (3, 11)

# Valid vectors use the branch "valid"; every other label names a rejection
# branch of the Java port that at least one vector must exercise.
INPUTS: tuple[tuple[str, str], ...] = (
    ("2026-01-02", "valid"),
    ("20260102", "valid"),
    ("2026-01-02T03:04:05", "valid"),
    ("2026-01-02 03:04:05", "valid"),
    ("2026-01-02x03:04:05", "valid"),
    ("2026-01-02🦀03:04:05", "valid"),
    ("2026-01-02T03:04:05Z", "valid"),
    ("2026-01-02T03:04:05+05:30", "valid"),
    ("2026-01-02T03:04:05-00:00", "valid"),
    ("2026-01-02T03:04:05+05:30:15", "valid"),
    ("2026-01-02T03:04:05+05:30:15.123456", "valid"),
    ("2026-01-02T03:04:05+05:30:15.123456789", "valid"),
    ("2026-01-02T03:04:05.1", "valid"),
    ("2026-01-02T03:04:05.12", "valid"),
    ("2026-01-02T03:04:05.123", "valid"),
    ("2026-01-02T03:04:05.1234", "valid"),
    ("2026-01-02T03:04:05.12345", "valid"),
    ("2026-01-02T03:04:05.123456", "valid"),
    ("2026-01-02T03:04:05.1234567", "valid"),
    ("2026-01-02T03:04:05.123456789", "valid"),
    ("2026-01-02T03:04:05,5", "valid"),
    ("2026-01-02T03", "valid"),
    ("2026-01-02T03:04", "valid"),
    ("2026-01-02T0304", "valid"),
    ("2026-01-02T030405", "valid"),
    ("2026-01-02T03+05:30", "valid"),
    ("2026-01-02T03:04:05+05", "valid"),
    ("2026-01-02T03:04:05+0530", "valid"),
    ("2026-01-02T03:04:05-05:30", "valid"),
    ("2026-01-02T03:04:05-00:00:00.000000", "valid"),
    ("20260102T030405Z", "valid"),
    ("0001-01-01T00:00:00Z", "valid"),
    ("9999-12-31T23:59:59.999999+00:00", "valid"),
    ("2026-W01-1", "valid"),
    ("2026W011", "valid"),
    ("2026-W01", "valid"),
    ("2026W01", "valid"),
    ("2020-W53-7", "valid"),
    ("2021-W01-1", "valid"),
    ("2022-W01-1", "valid"),
    ("2023-W01-1", "valid"),
    ("2027-W01-1", "valid"),
    ("2010-W52-7", "valid"),
    ("2026-W53", "valid"),
    ("2026-01-02T03.5", "valid"),
    ("2026-01-02T0304.5", "valid"),
    ("2026-01-02T03:04:05+05.5", "valid"),
    ("2026-01-02T03:04:05+05:30.5", "valid"),
    ("2026-01-02T03:04:05:06", "valid"),
    ("2026-01-02T03:04:05\0", "valid"),
    ("2026W013 130Z", "valid"),
    ("2026-01-02T03:04:05+00:00:00.000001", "valid"),
    ("", "date_length"),
    ("2026", "date_length"),
    ("2026-1-01", "date_digits"),
    ("٢٠٢٦-٠١-٠٢", "non_ascii_digits"),
    ("２０２６０１０２", "non_ascii_digits"),
    ("2026-00-02", "month"),
    ("2026-13-02", "month"),
    ("2026-01-00", "day"),
    ("2026-01-32", "day"),
    ("2025-02-29", "day"),
    ("0000-01-01", "year"),
    ("2021-W53-1", "week"),
    ("2026-W00-1", "week"),
    ("2026-W54-1", "week"),
    ("2026-W01-0", "weekday"),
    ("2026-W01-8", "weekday"),
    ("2026-W01-", "separator"),
    ("2026W01-1", "time_short"),
    ("2026-0102", "date_separator"),
    ("2026-01-02T", "time_short"),
    ("2026-01-02T0", "time_short"),
    ("2026-01-02T03:0", "time_incomplete"),
    ("2026-01-02T0@:00", "time_digits"),
    ("2026-01-02T03:0405", "time_separator"),
    ("2026-01-02T24:00", "hour"),
    ("2026-01-02T23:60", "minute"),
    ("2026-01-02T23:59:60", "second"),
    ("2026-01-02T03:04:05.", "trailing_text"),
    ("2026-01-02T03:04:05.x", "fraction"),
    ("2026-01-02T03:04:05.123456x", "fraction_digits"),
    ("2026-01-02T03:04:05junk", "time_separator"),
    ("2026-01-02T03:04:05z", "trailing_text"),
    ("2026-01-02T03:04Z05", "offset_z_position"),
    ("2026-01-02T03:00+", "offset"),
    ("2026-01-02T03:00+0", "offset"),
    ("2026-01-02T03:00+000", "offset"),
    ("2026-01-02T03:00+0x", "offset_digits"),
    ("2026-01-02T03:00+24:00", "offset_range"),
    ("2026-01-02T03:00+00:00:00.x", "offset_fraction"),
    ("2026-01-02T03:00+00:00junk", "offset_trailing_text"),
)


def epoch_micros(value: datetime) -> int:
    if value.tzinfo is not None:
        value = value.astimezone(timezone.utc)
    epoch = datetime(1970, 1, 1, tzinfo=value.tzinfo)
    delta = value - epoch
    return (delta.days * 86_400 + delta.seconds) * 1_000_000 + delta.microseconds


def make_vector(text: str, branch: str) -> dict[str, object]:
    try:
        parsed = datetime.fromisoformat(text)
    except ValueError:
        if branch == "valid":
            raise AssertionError(f"valid vector was rejected: {text!r}") from None
        return {"text": text, "branch": branch, "parses": False, "aware": False}
    if branch != "valid":
        raise AssertionError(f"rejection vector was accepted: {text!r} ({branch})")
    vector: dict[str, object] = {
        "text": text,
        "branch": branch,
        "parses": True,
        "aware": parsed.tzinfo is not None,
    }
    vector["utc_epoch_micros" if parsed.tzinfo is not None else "naive_micros"] = epoch_micros(
        parsed
    )
    return vector


def generated() -> str:
    vectors = [make_vector(text, branch) for text, branch in INPUTS]
    return json.dumps(vectors, ensure_ascii=False, indent=2) + "\n"


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--check", action="store_true", help="diff the committed vectors instead of rewriting them"
    )
    arguments = parser.parse_args(argv)
    if sys.version_info[:2] != PINNED_PYTHON:
        parser.error(
            f"core pins Python {PINNED_PYTHON[0]}.{PINNED_PYTHON[1]}; running {sys.version.split()[0]}"
        )
    expected = generated()
    if arguments.check:
        actual = OUTPUT.read_text(encoding="utf-8")
        if actual != expected:
            sys.stdout.writelines(
                difflib.unified_diff(
                    actual.splitlines(keepends=True),
                    expected.splitlines(keepends=True),
                    fromfile=str(OUTPUT),
                    tofile=f"{OUTPUT} (regenerated)",
                )
            )
            return 1
        print(f"fromisoformat vectors unchanged: {len(INPUTS)}")
        return 0
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT.write_text(expected, encoding="utf-8")
    print(f"wrote {len(INPUTS)} fromisoformat vectors to {OUTPUT.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
