#!/usr/bin/env python3
"""Fail if this checkout reuses a package version from main while changing ``src/``.

PROGRAM.md §2: "a shared package's version number is spent once — an
in-place amendment to an already-adopted version is forbidden ... bump the
patch number the moment [a consumer has adopted it]." Issue #170 found
``8.2.0`` declared at two distinct trees on ``main`` because a later commit
changed ``from_zeitgeist_attrs``'s decode behaviour under ``src/`` without
bumping the version — this script is the follow-up CI check issue #170 asked
for (issue #175).

Compares the working tree against ``origin/main`` (or ``main`` if there is no
``origin`` remote): if anything under ``src/`` differs, the proposed package
version must not have appeared anywhere in that ref's ``pyproject.toml``
history. Docs-only, test-only, or config-only changes are unaffected — the
version is free to stay put until something under ``src/`` actually changes.

This does walk commits that changed ``pyproject.toml``, but only to build the
set of versions already declared on main. It does not judge those historical
commits themselves, so ordinary commits that legitimately share one version
do not become false positives (the noisy strategy rejected by issue #175).
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


def _declared_versions(ref: str) -> dict[str, str]:
    """Map every package version declared in ``ref``'s history to one commit."""
    versions: dict[str, str] = {}
    # --full-history, not --follow: --follow prunes merge commits, and a merge
    # resolution can itself declare a version (or revert a side branch's bump)
    # that no non-merge commit ever carries — the "re-spend as X.Y.Z" shapes
    # this repo's history is full of.
    commits = _run("log", "--full-history", "--format=%H", ref, "--", "pyproject.toml").splitlines()
    for commit in commits:
        try:
            version = _version_at(commit)
        except (RuntimeError, subprocess.CalledProcessError):
            # Pre-package history may contain pyproject.toml without a
            # project version, or a commit where the file is absent.
            continue
        versions.setdefault(version, commit)
    return versions


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
            f"prove that the proposed version is unused across {base_ref}'s full history — "
            "fetch the full history before validating the package version"
        )

    head_version = _current_version()
    base_version = _version_at(base_sha)
    declared_versions = _declared_versions(base_sha)
    declared_versions.setdefault(base_version, base_sha)
    prior_commit = declared_versions.get(head_version)
    if prior_commit is None:
        return None  # a new version was declared

    return (
        f"src/ changed ({len(changed)} file(s)) but pyproject.toml's version "
        f"({head_version!r}) was already declared on {base_ref} at "
        f"{prior_commit[:8]} ({base_ref}'s tip {base_sha[:8]} declares "
        f"{base_version!r}) — this reuses an already-spent version. "
        "PROGRAM.md §2: declare a new version. Changed files:\n  " + "\n  ".join(changed)
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
