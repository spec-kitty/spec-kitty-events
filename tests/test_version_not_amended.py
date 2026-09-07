"""Guard: catch reuse of any package version already declared on main.

See ``contracts/versioning-and-compatibility.md`` and PROGRAM.md §2 ("a shared
package's version number is spent once"). Issue #170 found ``8.2.0`` declared
at two distinct trees on ``main`` because a later commit changed
``from_zeitgeist_attrs``'s decode behaviour under ``src/`` without bumping the
patch. This is the CI check issue #170 asked for as a follow-up (issue #175).

``scripts/check_version_not_amended.py`` compares the working tree against
``origin/main`` (or ``main``): if ``src/`` differs, the package version must
not equal any version previously declared on main. The historical walk builds
only that spent-version set; it does not reject ordinary historical commits
that shared a version before the next bump, avoiding issue #175's noisy
25-of-56 replay strategy (56 = the ``--follow`` ``pyproject.toml`` commit
count at issue #179's filing, where that figure was recorded). The synthetic
regressions below cover the walk's decision branches, and — per issue #213 —
the real repository's own history is exercised too: worktree-based tests
force a ``src/`` diff so the complete walk actually runs against this repo,
and every merge-declared version must be found in the spent-version set.
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


def _git_merge_conflicting(cwd: Path, branch: str) -> None:
    """Merge ``branch`` expecting a conflict (git exits 1 mid-merge)."""
    subprocess.run(
        ["git", "merge", branch],
        cwd=cwd,
        capture_output=True,
        text=True,
        check=False,
    )


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

    # An ordinary metadata commit legitimately keeps 1.0.0, reproducing the
    # repeated-version history that made issue #175's replay strategy noisy.
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


def test_fails_on_in_place_amendment(tmp_path, monkeypatch) -> None:
    root = tmp_path / "repo"
    root.mkdir()
    _init_repo_with_base_commit(root)

    # src/ changes, version does not — the exact shape of #170's b67b7e0.
    (root / "src" / "mod.py").write_text("x = 2\n", encoding="utf-8")

    monkeypatch.setattr(_checker, "_REPO_ROOT", root)
    message = _checker.check()
    assert message is not None
    assert "mod.py" in message
    assert "1.0.0" in message


def test_fails_when_stale_historical_version_is_reused(tmp_path, monkeypatch) -> None:
    root = tmp_path / "repo"
    root.mkdir()
    _init_repo_with_base_commit(root)

    (root / "pyproject.toml").write_text('[project]\nversion = "1.1.0"\n', encoding="utf-8")
    _git(root, "add", "pyproject.toml")
    _git(root, "commit", "-q", "-m", "bump to 1.1.0")

    # The proposed source change differs from main's tip version, but reuses
    # 1.0.0 from an older main commit.
    (root / "pyproject.toml").write_text('[project]\nversion = "1.0.0"\n', encoding="utf-8")
    (root / "src" / "mod.py").write_text("x = 2\n", encoding="utf-8")

    monkeypatch.setattr(_checker, "_REPO_ROOT", root)
    message = _checker.check()
    assert message is not None
    assert "already declared" in message
    assert "'1.0.0'" in message
    assert "tip" in message
    assert "'1.1.0'" in message


def test_fails_when_stale_version_was_declared_only_in_a_merge_commit(
    tmp_path, monkeypatch
) -> None:
    root = tmp_path / "repo"
    root.mkdir()
    _init_repo_with_base_commit(root)

    # Both sides bump the version line; the conflict is resolved to a value
    # no non-merge commit ever declares, so only the merge commit carries it.
    _git(root, "checkout", "-q", "-b", "side")
    (root / "pyproject.toml").write_text('[project]\nversion = "1.1.0"\n', encoding="utf-8")
    _git(root, "commit", "-q", "-a", "-m", "side bumps to 1.1.0")
    _git(root, "checkout", "-q", "main")
    (root / "pyproject.toml").write_text('[project]\nversion = "1.0.5"\n', encoding="utf-8")
    _git(root, "commit", "-q", "-a", "-m", "main bumps to 1.0.5")
    _git_merge_conflicting(root, "side")  # conflicts on the version line
    (root / "pyproject.toml").write_text('[project]\nversion = "1.2.0"\n', encoding="utf-8")
    _git(root, "add", "pyproject.toml")
    _git(root, "commit", "-q", "-m", "Merge branch 'side'; re-spend as 1.2.0")

    # Keep the merge off main's tip: the tip-rescue setdefault() would
    # otherwise re-add 1.2.0 through base_sha and mask the walk gap.
    (root / "pyproject.toml").write_text('[project]\nversion = "1.3.0"\n', encoding="utf-8")
    _git(root, "commit", "-q", "-a", "-m", "bump to 1.3.0")

    # Reusing the merge-only declared version with a src/ change must fail;
    # `git log --follow` never lists the merge commit, so the old walk passed.
    (root / "pyproject.toml").write_text('[project]\nversion = "1.2.0"\n', encoding="utf-8")
    (root / "src" / "mod.py").write_text("x = 2\n", encoding="utf-8")

    monkeypatch.setattr(_checker, "_REPO_ROOT", root)
    message = _checker.check()
    assert message is not None
    assert "already declared" in message
    assert "'1.2.0'" in message


def test_fails_when_stale_version_was_spent_on_a_merge_reverted_branch(
    tmp_path, monkeypatch
) -> None:
    root = tmp_path / "repo"
    root.mkdir()
    _init_repo_with_base_commit(root)

    # A side branch spends 1.2.0; the merge resolution reverts the bump, so
    # the merge is TREESAME to main on pyproject.toml and default history
    # simplification prunes the side branch from a plain `git log` walk.
    _git(root, "checkout", "-q", "-b", "topic")
    (root / "pyproject.toml").write_text('[project]\nversion = "1.2.0"\n', encoding="utf-8")
    _git(root, "commit", "-q", "-a", "-m", "topic re-spends as 1.2.0")
    _git(root, "checkout", "-q", "main")
    (root / "pyproject.toml").write_text('[project]\nversion = "1.1.0"\n', encoding="utf-8")
    _git(root, "commit", "-q", "-a", "-m", "main bumps to 1.1.0")
    _git_merge_conflicting(root, "topic")  # conflicts on the version line
    (root / "pyproject.toml").write_text('[project]\nversion = "1.1.0"\n', encoding="utf-8")
    _git(root, "add", "pyproject.toml")
    _git(root, "commit", "-q", "-m", "Merge branch 'topic'; keep 1.1.0")

    # Keep the merge off main's tip, as with the merge-only fixture above.
    (root / "pyproject.toml").write_text('[project]\nversion = "1.3.0"\n', encoding="utf-8")
    _git(root, "commit", "-q", "-a", "-m", "bump to 1.3.0")

    # Reusing the branch-spent version must fail. A plain `git log --
    # pyproject.toml` walk prunes the merge (TREESAME to main on that path)
    # and with it the whole topic branch, so only --full-history finds it.
    (root / "pyproject.toml").write_text('[project]\nversion = "1.2.0"\n', encoding="utf-8")
    (root / "src" / "mod.py").write_text("x = 2\n", encoding="utf-8")

    monkeypatch.setattr(_checker, "_REPO_ROOT", root)
    message = _checker.check()
    assert message is not None
    assert "already declared" in message
    assert "'1.2.0'" in message


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
    assert "fetch the full history" in message


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
    """Smoke test: the script runs clean against the actual checkout."""
    result = subprocess.run(
        [sys.executable, str(_SCRIPT)], capture_output=True, text=True, cwd=_REPO_ROOT
    )
    assert result.returncode == 0, result.stderr


def _real_repo_base_ref() -> str:
    base_ref = _checker._resolve_base_ref()
    assert base_ref is not None, "these tests run inside this repo's own checkout"
    return base_ref


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


def test_real_repo_src_change_reusing_tip_version_fails(tmp_path, monkeypatch) -> None:
    """Issue #213: force a real ``src/`` diff so ``check()`` runs the complete
    history walk against this repository's own history, not just the early
    "nothing under src/ changed" exit the plain smoke test takes.

    The base tip's version is by construction already declared, so the guard
    must fail — deterministically, regardless of what this branch itself did
    to ``pyproject.toml``.
    """
    base_ref = _real_repo_base_ref()
    base_sha = _git(_REPO_ROOT, "rev-parse", base_ref)
    tip_version = _checker._version_at(base_sha)

    worktree = _add_real_repo_worktree(tmp_path)
    try:
        _touch_tracked_src_file(worktree)
        _set_worktree_version(worktree, tip_version)

        monkeypatch.setattr(_checker, "_REPO_ROOT", worktree)
        message = _checker.check()
        assert message is not None
        assert "already declared" in message
        assert f"'{tip_version}'" in message
    finally:
        _remove_real_repo_worktree(worktree)


def test_real_repo_src_change_with_never_declared_version_passes(tmp_path, monkeypatch) -> None:
    """The pass leg of the real-history walk: a version absent from every
    version-bearing ``pyproject.toml`` commit reachable from the base ref lets
    a real ``src/`` change through. The sentinel is derived from the walk's own
    output, so it can never collide with history as the repo grows."""
    base_ref = _real_repo_base_ref()
    declared = _checker._declared_versions(base_ref)
    assert declared, "this repo's history declares package versions"
    fresh = next(f"0.213.{i}" for i in range(1000) if f"0.213.{i}" not in declared)

    worktree = _add_real_repo_worktree(tmp_path)
    try:
        _touch_tracked_src_file(worktree)
        _set_worktree_version(worktree, fresh)

        monkeypatch.setattr(_checker, "_REPO_ROOT", worktree)
        assert _checker.check() is None
    finally:
        _remove_real_repo_worktree(worktree)


def test_real_history_walk_includes_merge_declared_versions() -> None:
    """Every version declared at a merge commit that touched ``pyproject.toml``
    must be in the walk's spent-version set — the exact gap PR #212's squad
    pass 2 found when the walk used ``--follow`` (merge commits pruned, seven
    declared versions invisible). The merge list is derived independently via
    ``--merges --full-history``, so a walk that regresses to pruning merges
    fails here against the real repository history."""
    base_ref = _real_repo_base_ref()
    merges = _git(
        _REPO_ROOT,
        "log",
        "--merges",
        "--full-history",
        "--format=%H",
        base_ref,
        "--",
        "pyproject.toml",
    ).split()
    assert merges, "this repo's history has merge commits touching pyproject.toml"

    declared = _checker._declared_versions(base_ref)
    missing = []
    for commit in merges:
        try:
            version = _checker._version_at(commit)
        except (RuntimeError, subprocess.CalledProcessError):
            continue  # pre-package history may lack a version line
        if version not in declared:
            missing.append(f"{version!r} at merge {commit[:8]}")
    assert not missing, (
        "versions declared at merge commits are missing from the walk's "
        f"spent-version set: {', '.join(missing)} — the walk is again "
        "pruning merge commits"
    )
