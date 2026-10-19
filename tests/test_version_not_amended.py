"""Guard: catch in-place amendment of an already-*released* package version.

See ``contracts/versioning-and-compatibility.md`` and PROGRAM.md §2 ("a shared
package's version number is spent once"). Issue #170 found ``8.2.0`` declared
at two distinct trees on ``main`` because a later commit changed
``from_zeitgeist_attrs``'s decode behaviour under ``src/`` without bumping the
patch. This is the CI check issue #170 asked for as a follow-up (issue #175).

Operator ruling 2026-10-19: consumers pin *release tags*, not ``main`` SHAs, so
a version is spent only once it is released — once a ``v<version>`` git tag
exists. An unreleased version on ``main`` is still open to change.
``scripts/check_version_not_amended.py`` therefore compares the working tree
against ``origin/main`` (or ``main``): if ``src/`` differs AND the working-tree
version is already tagged, the amendment is rejected; an untagged version may
be amended in place, and docs/test/config-only changes are always fine. The
former main-declaration/merge-walk machinery (issues #175/#212/#213) is gone —
presence in main history no longer settles whether a version is frozen.
"""

from __future__ import annotations

import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "scripts"))
import check_version_not_amended as _checker  # noqa: E402

_REPO_ROOT = Path(__file__).resolve().parent.parent
_SCRIPT = _REPO_ROOT / "scripts" / "check_version_not_amended.py"


def _git(cwd: Path, *args: str) -> str:
    return subprocess.run(
        ["git", *args], cwd=cwd, capture_output=True, text=True, check=True
    ).stdout.strip()


def _init_repo_with_base_commit(root: Path, extra_files: dict[str, str] | None = None) -> None:
    (root / "src").mkdir()
    _git(root, "init", "-q")
    _git(root, "config", "user.email", "test@example.com")
    _git(root, "config", "user.name", "Test")
    (root / "pyproject.toml").write_text('[project]\nversion = "1.0.0"\n', encoding="utf-8")
    (root / "src" / "mod.py").write_text("x = 1\n", encoding="utf-8")
    for name, content in (extra_files or {}).items():
        (root / name).write_text(content, encoding="utf-8")
    _git(root, "add", "-A")
    _git(root, "commit", "-q", "-m", "base")
    _git(root, "branch", "-M", "main")


def test_passes_when_version_bumped(tmp_path, monkeypatch) -> None:
    root = tmp_path / "repo"
    root.mkdir()
    _init_repo_with_base_commit(root)

    (root / "pyproject.toml").write_text('[project]\nversion = "1.1.0"\n', encoding="utf-8")
    (root / "src" / "mod.py").write_text("x = 2\n", encoding="utf-8")

    monkeypatch.setattr(_checker, "_REPO_ROOT", root)
    assert _checker.check() is None


def test_passes_fresh_version_after_history_repeats_current_version(tmp_path, monkeypatch) -> None:
    root = tmp_path / "repo"
    root.mkdir()
    _init_repo_with_base_commit(root)

    # An ordinary metadata commit legitimately keeps 1.0.0 on main; nothing is
    # tagged, so the repeated version on main is irrelevant to the guard.
    (root / "pyproject.toml").write_text(
        '[project]\nversion = "1.0.0"\ndescription = "metadata change"\n',
        encoding="utf-8",
    )
    _git(root, "add", "pyproject.toml")
    _git(root, "commit", "-q", "-m", "metadata change at 1.0.0")

    (root / "pyproject.toml").write_text('[project]\nversion = "1.1.0"\n', encoding="utf-8")
    (root / "src" / "mod.py").write_text("x = 2\n", encoding="utf-8")

    monkeypatch.setattr(_checker, "_REPO_ROOT", root)
    assert _checker.check() is None


def test_passes_when_unreleased_version_is_amended(tmp_path, monkeypatch) -> None:
    root = tmp_path / "repo"
    root.mkdir()
    _init_repo_with_base_commit(root)

    # src/ changes, version stays 1.0.0 — the shape #170 forbade — but 1.0.0 was
    # never released (no v1.0.0 tag), so under the 2026-10-19 ruling it is still
    # open to change in place.
    (root / "src" / "mod.py").write_text("x = 2\n", encoding="utf-8")

    monkeypatch.setattr(_checker, "_REPO_ROOT", root)
    assert _checker.check() is None


def test_fails_when_released_version_is_amended(tmp_path, monkeypatch) -> None:
    root = tmp_path / "repo"
    root.mkdir()
    _init_repo_with_base_commit(root)

    # Release 1.0.0, then amend src/ while keeping the version at 1.0.0. A
    # released version is frozen, so this must fail.
    _git(root, "tag", "v1.0.0")
    (root / "src" / "mod.py").write_text("x = 2\n", encoding="utf-8")

    monkeypatch.setattr(_checker, "_REPO_ROOT", root)
    message = _checker.check()
    assert message is not None
    assert "1.0.0" in message
    assert "released" in message
    assert "v1.0.0" in message
    assert "mod.py" in message


def test_fails_closed_when_source_changes_in_shallow_checkout(tmp_path, monkeypatch) -> None:
    source = tmp_path / "source"
    source.mkdir()
    _init_repo_with_base_commit(source)
    (source / "pyproject.toml").write_text('[project]\nversion = "1.1.0"\n', encoding="utf-8")
    _git(source, "add", "pyproject.toml")
    _git(source, "commit", "-q", "-m", "bump to 1.1.0")

    root = tmp_path / "shallow"
    _git(tmp_path, "clone", "-q", "--depth", "1", source.as_uri(), str(root))
    (root / "pyproject.toml").write_text('[project]\nversion = "1.0.0"\n', encoding="utf-8")
    (root / "src" / "mod.py").write_text("x = 2\n", encoding="utf-8")

    monkeypatch.setattr(_checker, "_REPO_ROOT", root)
    message = _checker.check()
    assert message is not None
    assert "shallow checkout" in message
    assert "fetch the full history and tags" in message


def test_passes_when_only_docs_change(tmp_path, monkeypatch) -> None:
    root = tmp_path / "repo"
    root.mkdir()
    _init_repo_with_base_commit(root, extra_files={"README.md": "hello\n"})

    (root / "README.md").write_text("hello world\n", encoding="utf-8")  # docs-only

    monkeypatch.setattr(_checker, "_REPO_ROOT", root)
    assert _checker.check() is None


def test_passes_when_no_main_ref_resolvable(tmp_path, monkeypatch) -> None:
    root = tmp_path / "repo"
    root.mkdir()
    (root / "src").mkdir()
    _git(root, "init", "-q")
    _git(root, "config", "user.email", "test@example.com")
    _git(root, "config", "user.name", "Test")
    (root / "pyproject.toml").write_text('[project]\nversion = "1.0.0"\n', encoding="utf-8")
    (root / "src" / "mod.py").write_text("x = 1\n", encoding="utf-8")
    _git(root, "add", "-A")
    _git(root, "commit", "-q", "-m", "base")
    # No branch named "main" and no "origin" remote — nothing to compare against.

    monkeypatch.setattr(_checker, "_REPO_ROOT", root)
    assert _checker.check() is None


def test_script_passes_against_this_repo() -> None:
    """Smoke test: the script runs clean against the actual checkout (the tip
    version is untagged, so an amendment here is still allowed)."""
    result = subprocess.run(
        [sys.executable, str(_SCRIPT)], capture_output=True, text=True, cwd=_REPO_ROOT
    )
    assert result.returncode == 0, result.stderr


def _add_real_repo_worktree(tmp_path: Path) -> Path:
    worktree = tmp_path / "real-repo-worktree"
    _git(_REPO_ROOT, "worktree", "add", "-q", "--detach", str(worktree), "HEAD")
    return worktree


def _remove_real_repo_worktree(worktree: Path) -> None:
    _git(_REPO_ROOT, "worktree", "remove", "--force", str(worktree))


def _touch_tracked_src_file(worktree: Path) -> None:
    """Leave an uncommitted modification under ``src/`` (a new, untracked file
    would be invisible to ``git diff``, so an existing tracked file is edited)."""
    target = sorted((worktree / "src" / "spec_kitty_events").glob("*.py"))[0]
    target.write_text(
        target.read_text(encoding="utf-8") + "\n# version-not-amended real-repo probe\n",
        encoding="utf-8",
    )


def _set_worktree_version(worktree: Path, version: str) -> None:
    pyproject = worktree / "pyproject.toml"
    text = pyproject.read_text(encoding="utf-8")
    assert _checker._VERSION_RE.search(text) is not None, (
        "pyproject.toml must carry a version line to rewrite"
    )
    pyproject.write_text(
        _checker._VERSION_RE.sub(f'version = "{version}"', text, count=1),
        encoding="utf-8",
    )


def test_real_repo_unreleased_version_with_src_change_passes(tmp_path, monkeypatch) -> None:
    """Issue #213: force a real ``src/`` diff so ``check()`` runs against this
    repository's own history and tags, not just the early "nothing under src/
    changed" exit the plain smoke test takes.

    The real head carries an unreleased version (11.2.0 is untagged), so a real
    ``src/`` change against it must pass under the 2026-10-19 release-tag ruling.
    """
    worktree = _add_real_repo_worktree(tmp_path)
    try:
        monkeypatch.setattr(_checker, "_REPO_ROOT", worktree)
        head_version = _checker._current_version()
        assert head_version not in _checker._released_versions(), (
            "this test requires the current tip version to be unreleased (untagged)"
        )

        _touch_tracked_src_file(worktree)
        _set_worktree_version(worktree, head_version)

        assert _checker.check() is None
    finally:
        _remove_real_repo_worktree(worktree)
