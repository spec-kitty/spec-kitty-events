"""Test-only bridge to core's real status lock and atomic status append at the pinned SHA."""

from __future__ import annotations

import argparse
import json
import random
import time
from pathlib import Path

from specify_cli.status import locking, store

_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"


def _event_id(writer: int, index: int) -> str:
    value = writer * 1_000_000 + index
    digits = ""
    while value > 0:
        digits = _ALPHABET[value % 32] + digits
        value //= 32
    return digits.rjust(26, "0")


def _row(writer: int, index: int) -> dict[str, object]:
    return {
        "actor": f"status-interop-{writer}",
        "event_id": _event_id(writer, index),
        "index": index,
        "writer": writer,
    }


def _repository_root(gcd: Path) -> Path:
    return gcd.parent


def _await(path: Path) -> None:
    while not path.exists():
        time.sleep(0.01)


def _lock_path(args: argparse.Namespace) -> None:
    mission = args.mission_dir.resolve()
    path = locking.feature_status_lock_path(_repository_root(args.gcd), mission.name)
    print(json.dumps({"lock_path": str(path)}, sort_keys=True))


def _append(args: argparse.Namespace) -> None:
    if args.ready is not None:
        args.ready.touch()
    if args.start is not None:
        _await(args.start)
    log = args.mission_dir / store.EVENTS_FILENAME
    jitter = random.Random(args.writer)
    for index in range(args.count):
        with locking.feature_status_lock(
            _repository_root(args.gcd),
            args.mission_dir.name,
            timeout=locking.BOUNDED_STATUS_LOCK_TIMEOUT_SECONDS,
        ):
            store.append_raw_rows_atomic(log, [_row(args.writer, index)])
        time.sleep(jitter.uniform(0, 0.005))
    print(json.dumps({"appended": args.count, "writer": args.writer}, sort_keys=True))


def _hold(args: argparse.Namespace) -> None:
    with locking.feature_status_lock(
        _repository_root(args.gcd),
        args.mission_dir.name,
        timeout=locking.BOUNDED_STATUS_LOCK_TIMEOUT_SECONDS,
    ):
        args.ready.touch()
        _await(args.release)
    print(json.dumps({"released": True}, sort_keys=True))


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser()
    commands = parser.add_subparsers(dest="command", required=True)
    for name in ("lock-path", "append", "hold"):
        command = commands.add_parser(name)
        command.add_argument("--gcd", type=Path, required=True)
        command.add_argument("--mission-dir", type=Path, required=True)
        if name == "append":
            command.add_argument("--writer", type=int, required=True)
            command.add_argument("--count", type=int, required=True)
            command.add_argument("--ready", type=Path)
            command.add_argument("--start", type=Path)
        if name == "hold":
            command.add_argument("--ready", type=Path, required=True)
            command.add_argument("--release", type=Path, required=True)
    return parser


def main() -> None:
    args = _parser().parse_args()
    args.gcd = args.gcd.resolve()
    args.mission_dir = args.mission_dir.resolve()
    if args.command == "lock-path":
        _lock_path(args)
    elif args.command == "append":
        _append(args)
    else:
        _hold(args)


if __name__ == "__main__":
    main()
