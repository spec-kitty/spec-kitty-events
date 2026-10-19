#!/usr/bin/env python3
"""Fail if this checkout changes ``src/`` while reusing an already-*released* version.

PROGRAM.md §2: "a shared package's version number is spent once — an
in-place amendment to an already-adopted version is forbidden ... bump the
patch number the moment [a consumer has adopted it]." Issue #170 found
``8.2.0`` declared at two distinct trees on ``main`` because a later commit
changed ``from_zeitgeist_attrs``'s decode behaviour under ``src/`` without
bumping the version — this script is the follow-up CI check issue #170 asked
for (issue #175).

Operator ruling 2026-10-19: a version is no longer spent the moment it appears
at a ``main`` SHA. External and internal consumers now pin *release tags*, not
``main`` SHAs, so an unreleased version sitting on ``main`` is still open to
change — it freezes only once it is released, i.e. once a ``v<version>`` git
tag exists. The policy therefore moved from "declared on main" to
"released as a tag": the old ``pyproject.toml`` main-declaration/merge-walk
machinery (issue #175's spent-version set, extended for merge commits in
issues #212/#213) is gone, because a version's presence in main history no
longer settles whether it is frozen.

Compares the working tree against ``origin/main`` (or ``main`` if there is no
``origin`` remote): if anything under ``src/`` differs AND the working-tree
version has already been released as a ``v<version>`` tag, the amendment is
rejected. Docs-only, test-only, or config-only changes are unaffected, and an
unreleased version is free to change in place until it is tagged.
"""

from __future__ import annotations

import re
import subprocess
import sys
from pathlib import Path

_REPO_ROOT = Path(__file__).resolve().parent.parent
_VERSION_RE = re.compile(r'^version\s*=\s*"([^"]+)"', re.M)


def _run(*args: str) -> str:
    return subprocess.run(
        ["git", *args],
        cwd=_REPO_ROOT,
        capture_output=True,
        text=True,
        check=True,
    ).stdout.strip()


def _resolve_base_ref() -> str | None:
    """The commit this checkout will merge into, or None if none is resolvable."""
    for ref in ("origin/main", "main"):
        try:
            _run("rev-parse", "--verify", ref)
        except (subprocess.CalledProcessError, FileNotFoundError):
            continue
        return ref
    return None


def _version_from_text(text: str, *, where: str) -> str:
    match = _VERSION_RE.search(text)
    if match is None:
        raise RuntimeError(f'no `version = "..."` line found in pyproject.toml at {where}')
    return match.group(1)


def _version_at(ref: str) -> str:
    return _version_from_text(_run("show", f"{ref}:pyproject.toml"), where=ref)


def _released_versions() -> set[str]:
    """Versions already released, derived from the ``v<version>`` git tags."""
    tags = _run("tag", "--list", "v*").splitlines()
    return {tag[1:] for tag in tags if tag.startswith("v")}


def _current_version() -> str:
    path = _REPO_ROOT / "pyproject.toml"
    return _version_from_text(path.read_text(encoding="utf-8"), where="the working tree")


def check() -> str | None:
    """Return a failure message, or None if the check passes."""
    base_ref = _resolve_base_ref()
    if base_ref is None:
        return None  # no main ref to compare against (e.g. standalone checkout)

    base_sha = _run("rev-parse", base_ref)
    changed = [
        line for line in _run("diff", "--name-only", base_sha, "--", "src/").splitlines() if line
    ]
    if not changed:
        return None  # no functional source changed; keeping the version is fine

    if _run("rev-parse", "--is-shallow-repository") == "true":
        return (
            "src/ changed, but this is a shallow checkout, so the guard cannot "
            "prove whether the proposed version is already released (its release "
            "tags are not present) — fetch the full history and tags before "
            "validating the package version"
        )

    head_version = _current_version()
    if head_version not in _released_versions():
        return None  # this version is unreleased, so amending it in place is fine

    return (
        f"src/ changed ({len(changed)} file(s)) but pyproject.toml's version "
        f"({head_version!r}) is already released — tag v{head_version} exists. "
        "A released version is frozen; declare a new version. "
        "PROGRAM.md §2: consumers pin release tags, not main SHAs. Changed files:\n  "
        + "\n  ".join(changed)
    )


def main() -> int:
    message = check()
    if message is None:
        print("version-not-amended: ok")
        return 0
    print(f"version-not-amended: FAIL\n{message}", file=sys.stderr)
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
