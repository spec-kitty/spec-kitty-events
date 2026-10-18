"""Every tracked conformance fixture must be shipped in the wheel (#120).

Matches each git-tracked non-Python file under the conformance package against the
``[tool.setuptools.package-data]`` globs, so a new fixture directory cannot be
forgotten in packaging (the packaged gate runs from an installed wheel).
"""

from __future__ import annotations

import fnmatch
import subprocess
import tomllib
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PKG = "src/spec_kitty_events/"


def _globs() -> list[str]:
    data = tomllib.loads((ROOT / "pyproject.toml").read_text(encoding="utf-8"))
    return list(data["tool"]["setuptools"]["package-data"]["spec_kitty_events"])


def _matches(rel: str, pattern: str) -> bool:
    # setuptools globs do not let ``*`` cross a path separator.
    return rel.count("/") == pattern.count("/") and fnmatch.fnmatchcase(rel, pattern)


def test_every_tracked_conformance_file_is_packaged() -> None:
    out = subprocess.run(
        ["git", "ls-files", PKG + "conformance"],
        cwd=ROOT,
        check=True,
        capture_output=True,
        text=True,
    ).stdout.split()
    globs = _globs()
    missing = [
        rel
        for rel in (f[len(PKG) :] for f in out)
        if not rel.endswith((".py", ".pyc")) and not any(_matches(rel, g) for g in globs)
    ]
    assert not missing, f"not matched by package-data globs: {missing}"
