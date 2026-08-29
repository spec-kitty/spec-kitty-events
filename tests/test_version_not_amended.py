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
25-of-56 replay strategy.
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
