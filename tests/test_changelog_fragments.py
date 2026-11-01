"""Changelog fragments (issue #175, ADR 0005).

A pull request records its changelog entry as ``docs/changelog/unreleased/<issue>-<slug>.md``
instead of editing ``CHANGELOG.md``, so parallel pull requests stop colliding on
the top of ``## [Unreleased]``. ``scripts/changelog.py collect`` folds the
fragments into ``CHANGELOG.md`` at release time, in the shape the file already
has, and deletes them. These tests pin the fragment format, the rendering, the
lint guard against hand edits of ``[Unreleased]``, and the release-time check.
"""

from __future__ import annotations

import re
import subprocess
import sys
from pathlib import Path

import pytest
import yaml

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "scripts"))
import changelog as cl  # noqa: E402

_REPO_ROOT = Path(__file__).resolve().parent.parent

_BASE_CHANGELOG = """\
# Changelog

All notable changes.

## [Unreleased]

No package change yet.

### Added

- An existing added entry.

### Fixed

- An existing fix.

## [1.0.0] - 2026-10-01

The first release.

### Added

- Everything.
"""


def _git(cwd: Path, *args: str) -> str:
    return subprocess.run(
        ["git", *args], cwd=cwd, capture_output=True, text=True, check=True
    ).stdout.strip()


def _write_fragment(root: Path, name: str, section: str, body: str) -> Path:
    path = root / "docs/changelog/unreleased" / name
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(f"---\nsection: {section}\n---\n\n{body}", encoding="utf-8")
    return path


def _repo(tmp_path: Path, changelog: str = _BASE_CHANGELOG) -> Path:
    root = tmp_path / "repo"
    root.mkdir()
    (root / "CHANGELOG.md").write_text(changelog, encoding="utf-8")
    (root / "docs/changelog/unreleased").mkdir(parents=True)
    (root / "docs/changelog/unreleased" / "README.md").write_text(
        "How to write a fragment.\n", encoding="utf-8"
    )
    return root


def _commit_base(root: Path) -> None:
    _git(root, "init", "-q")
    _git(root, "config", "user.email", "test@example.com")
    _git(root, "config", "user.name", "Test")
    _git(root, "add", "-A")
    _git(root, "commit", "-q", "-m", "base")
    _git(root, "branch", "-M", "main")


# --- fragment format -----------------------------------------------------------------


def test_a_well_formed_fragment_parses(tmp_path: Path) -> None:
    root = _repo(tmp_path)
    path = _write_fragment(root, "175-fragments.md", "Added", "- A new thing (#175).\n")
    fragment = cl.parse_fragment(path)
    assert fragment.section == "Added"
    assert fragment.body == "- A new thing (#175)."
    assert fragment.issue == 175


def test_the_readme_is_not_a_fragment(tmp_path: Path) -> None:
    root = _repo(tmp_path)
    _write_fragment(root, "175-fragments.md", "Added", "- A new thing.\n")
    assert [f.path.name for f in cl.load_fragments(root)] == ["175-fragments.md"]
    assert cl.check(root) == []


@pytest.mark.parametrize(
    "name",
    ["fragments.md", "175_fragments.md", "175-Fragments.md", "175-.md", "175-x.txt", "pr-175-x.md"],
)
def test_a_badly_named_fragment_is_refused(tmp_path: Path, name: str) -> None:
    root = _repo(tmp_path)
    _write_fragment(root, name, "Added", "- A new thing.\n")
    errors = cl.check(root)
    assert len(errors) == 1
    assert name in errors[0]


def test_an_unknown_section_is_refused(tmp_path: Path) -> None:
    root = _repo(tmp_path)
    _write_fragment(root, "175-x.md", "Improved", "- A thing.\n")
    (error,) = cl.check(root)
    assert "Improved" in error and "Added" in error


def test_missing_frontmatter_is_refused(tmp_path: Path) -> None:
    root = _repo(tmp_path)
    (root / "docs/changelog/unreleased" / "175-x.md").write_text("- A thing.\n", encoding="utf-8")
    (error,) = cl.check(root)
    assert "frontmatter" in error


@pytest.mark.parametrize(
    ("body", "message"),
    [
        ("", "empty"),
        ("\n\n", "empty"),
        ("Prose without a bullet.\n", "bullet"),
        ("- A bullet.\n\n### Added\n\n- Another.\n", "headings"),
    ],
)
def test_a_body_must_be_bullets_without_headings(tmp_path: Path, body: str, message: str) -> None:
    root = _repo(tmp_path)
    _write_fragment(root, "175-x.md", "Added", body)
    (error,) = cl.check(root)
    assert message in error


def test_nested_bullets_and_continuation_lines_are_kept(tmp_path: Path) -> None:
    root = _repo(tmp_path)
    body = "- Top level\n  continued here.\n  - A nested point.\n- A second entry.\n"
    path = _write_fragment(root, "175-x.md", "Changed", body)
    assert cl.check(root) == []
    assert cl.parse_fragment(path).body == body.rstrip("\n")


def test_fragments_sort_by_issue_number_then_name(tmp_path: Path) -> None:
    root = _repo(tmp_path)
    for name in ["200-b.md", "31-z.md", "200-a.md"]:
        _write_fragment(root, name, "Added", "- x\n")
    assert [f.path.name for f in cl.load_fragments(root)] == ["31-z.md", "200-a.md", "200-b.md"]


# --- collect -------------------------------------------------------------------------


def test_collect_appends_to_existing_subsections_and_adds_new_ones_in_order(tmp_path: Path) -> None:
    root = _repo(tmp_path)
    _write_fragment(root, "180-fix.md", "Fixed", "- Fixed it (#180).\n")
    _write_fragment(root, "175-add.md", "Added", "- Added it (#175).\n")
    _write_fragment(root, "176-break.md", "Breaking", "- Broke it (#176).\n")
    _write_fragment(root, "177-change.md", "Changed", "- Changed it (#177).\n")

    text = cl.render((root / "CHANGELOG.md").read_text(encoding="utf-8"), cl.load_fragments(root))

    expected_unreleased = """\
## [Unreleased]

No package change yet.

### Breaking

- Broke it (#176).

### Added

- An existing added entry.
- Added it (#175).

### Changed

- Changed it (#177).

### Fixed

- An existing fix.
- Fixed it (#180).

## [1.0.0] - 2026-10-01
"""
    assert expected_unreleased in text
    assert text.startswith("# Changelog\n\nAll notable changes.\n\n## [Unreleased]\n")
    assert text.endswith("### Added\n\n- Everything.\n")


def test_collect_with_a_version_cuts_a_release_section(tmp_path: Path) -> None:
    root = _repo(tmp_path)
    _write_fragment(root, "175-add.md", "Added", "- Added it (#175).\n")

    text = cl.render(
        (root / "CHANGELOG.md").read_text(encoding="utf-8"),
        cl.load_fragments(root),
        version="1.1.0",
        date="2026-11-02",
    )

    assert "## [Unreleased]\n\n## [1.1.0] - 2026-11-02\n\nNo package change yet.\n" in text
    assert "- An existing added entry.\n- Added it (#175).\n" in text
    assert text.index("## [1.1.0] - 2026-11-02") < text.index("## [1.0.0] - 2026-10-01")


def test_collect_keeps_unknown_existing_subsections_after_the_known_ones(tmp_path: Path) -> None:
    changelog = _BASE_CHANGELOG.replace(
        "### Fixed\n\n- An existing fix.\n",
        "### Fixed\n\n- An existing fix.\n\n### Compatibility\n\n- Kept as written.\n",
    )
    root = _repo(tmp_path, changelog)
    _write_fragment(root, "175-sec.md", "Security", "- Hardened (#175).\n")

    text = cl.render(changelog, cl.load_fragments(root))

    unreleased = text.split("## [1.0.0]")[0]
    assert unreleased.index("### Fixed") < unreleased.index("### Security")
    assert unreleased.index("### Security") < unreleased.index("### Compatibility")
    assert "### Compatibility\n\n- Kept as written.\n" in unreleased


def test_collect_into_an_empty_unreleased_section(tmp_path: Path) -> None:
    changelog = "# Changelog\n\n## [Unreleased]\n\n## [1.0.0] - 2026-10-01\n\n- x\n"
    root = _repo(tmp_path, changelog)
    _write_fragment(root, "175-add.md", "Added", "- Added it.\n")
    text = cl.render(changelog, cl.load_fragments(root))
    assert text == (
        "# Changelog\n\n## [Unreleased]\n\n### Added\n\n- Added it.\n\n"
        "## [1.0.0] - 2026-10-01\n\n- x\n"
    )


def test_collect_without_fragments_and_without_a_version_is_a_no_op() -> None:
    assert cl.render(_BASE_CHANGELOG, []) == _BASE_CHANGELOG


def test_collect_refuses_a_version_that_already_has_a_section(tmp_path: Path) -> None:
    with pytest.raises(cl.ChangelogError, match=r"1\.0\.0"):
        cl.render(_BASE_CHANGELOG, [], version="1.0.0", date="2026-11-02")


def test_collect_refuses_a_changelog_without_an_unreleased_section(tmp_path: Path) -> None:
    with pytest.raises(cl.ChangelogError, match="Unreleased"):
        cl.render("# Changelog\n\n## [1.0.0] - 2026-10-01\n", [])


def test_the_collect_command_writes_the_changelog_and_deletes_the_fragments(tmp_path: Path) -> None:
    root = _repo(tmp_path)
    fragment = _write_fragment(root, "175-add.md", "Added", "- Added it (#175).\n")

    assert (
        cl.main(["--root", str(root), "collect", "--version", "1.1.0", "--date", "2026-11-02"]) == 0
    )

    assert not fragment.exists()
    assert (root / "docs/changelog/unreleased" / "README.md").exists()
    assert "## [1.1.0] - 2026-11-02" in (root / "CHANGELOG.md").read_text(encoding="utf-8")


def test_the_preview_command_writes_nothing(
    tmp_path: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    root = _repo(tmp_path)
    fragment = _write_fragment(root, "175-add.md", "Added", "- Added it (#175).\n")

    assert cl.main(["--root", str(root), "preview"]) == 0

    assert fragment.exists()
    assert (root / "CHANGELOG.md").read_text(encoding="utf-8") == _BASE_CHANGELOG
    out = capsys.readouterr().out
    assert out.startswith("## [Unreleased]\n")
    assert "- Added it (#175)." in out
    assert "## [1.0.0]" not in out


def test_a_version_needs_a_date(tmp_path: Path) -> None:
    root = _repo(tmp_path)
    with pytest.raises(SystemExit):
        cl.main(["--root", str(root), "collect", "--version", "1.1.0"])


# --- guard: no hand edits of [Unreleased] --------------------------------------------


def test_guard_passes_a_pull_request_that_only_adds_a_fragment(tmp_path: Path) -> None:
    root = _repo(tmp_path)
    _commit_base(root)
    _write_fragment(root, "175-add.md", "Added", "- Added it.\n")
    assert cl.guard(root, "main") == []


def test_guard_refuses_a_hand_edit_of_unreleased(tmp_path: Path) -> None:
    root = _repo(tmp_path)
    _commit_base(root)
    changelog = root / "CHANGELOG.md"
    changelog.write_text(
        changelog.read_text(encoding="utf-8").replace(
            "- An existing fix.\n", "- An existing fix.\n- A hand-added fix.\n"
        ),
        encoding="utf-8",
    )
    (error,) = cl.guard(root, "main")
    assert "docs/changelog/unreleased/" in error


def test_guard_allows_a_collect_that_consumes_fragments(tmp_path: Path) -> None:
    root = _repo(tmp_path)
    _write_fragment(root, "175-add.md", "Added", "- Added it.\n")
    _commit_base(root)
    assert cl.main(["--root", str(root), "collect"]) == 0
    assert cl.guard(root, "main") == []


def test_guard_allows_a_release_cut_with_no_fragments(tmp_path: Path) -> None:
    root = _repo(tmp_path)
    _commit_base(root)
    assert (
        cl.main(["--root", str(root), "collect", "--version", "1.1.0", "--date", "2026-11-02"]) == 0
    )
    assert cl.guard(root, "main") == []


def test_guard_refuses_a_hand_edit_hidden_behind_a_release_cut(tmp_path: Path) -> None:
    # A new version heading in the tail must not excuse hand edits to [Unreleased]'s own
    # subsections: those stay in the section body, so the release-cut allowance only holds
    # when the body has been emptied (as a real collect leaves it).
    root = _repo(tmp_path)
    _commit_base(root)
    changelog = root / "CHANGELOG.md"
    changelog.write_text(
        changelog.read_text(encoding="utf-8")
        .replace("- An existing fix.\n", "- An existing fix.\n- Snuck in by hand.\n")
        .replace("## [1.0.0] - 2026-10-01", "## [2.0.0] - 2026-11-02\n\nFake cut.\n\n## [1.0.0] - 2026-10-01"),
        encoding="utf-8",
    )
    (error,) = cl.guard(root, "main")
    assert "docs/changelog/unreleased/" in error


def test_guard_fails_closed_when_the_changelog_is_absent_at_the_merge_base(tmp_path: Path) -> None:
    # The changelog did not exist at the branch point: a hand-edited [Unreleased] must still
    # be refused, not waved through because the base text could not be read.
    root = tmp_path / "repo"
    root.mkdir()
    (root / "docs/changelog/unreleased").mkdir(parents=True)
    (root / "docs/changelog/unreleased" / "README.md").write_text("readme\n", encoding="utf-8")
    _commit_base(root)
    (root / "CHANGELOG.md").write_text(_BASE_CHANGELOG, encoding="utf-8")
    (error,) = cl.guard(root, "main")
    assert "docs/changelog/unreleased/" in error


def test_guard_allows_edits_to_released_sections(tmp_path: Path) -> None:
    root = _repo(tmp_path)
    _commit_base(root)
    changelog = root / "CHANGELOG.md"
    changelog.write_text(
        changelog.read_text(encoding="utf-8").replace(
            "The first release.", "The first release, fixed."
        ),
        encoding="utf-8",
    )
    assert cl.guard(root, "main") == []


def test_guard_cli_resolves_main_and_fails_closed(tmp_path: Path) -> None:
    root = _repo(tmp_path)
    _commit_base(root)
    changelog = root / "CHANGELOG.md"
    changelog.write_text(
        changelog.read_text(encoding="utf-8").replace("- An existing fix.", "- An edited fix."),
        encoding="utf-8",
    )
    assert cl.main(["--root", str(root), "guard"]) == 1


# --- release check -------------------------------------------------------------------


def test_release_check_passes_a_collected_release(tmp_path: Path) -> None:
    root = _repo(tmp_path)
    assert cl.release_check(root, "1.0.0") == []


def test_release_check_refuses_pending_fragments(tmp_path: Path) -> None:
    root = _repo(tmp_path)
    _write_fragment(root, "175-add.md", "Added", "- Added it.\n")
    (error,) = cl.release_check(root, "1.0.0")
    assert "175-add.md" in error and "collect" in error


def test_release_check_refuses_a_version_without_a_section(tmp_path: Path) -> None:
    root = _repo(tmp_path)
    (error,) = cl.release_check(root, "1.1.0")
    assert "## [1.1.0]" in error


# --- the real repository -------------------------------------------------------------


def test_the_repository_fragments_are_well_formed() -> None:
    assert cl.check(_REPO_ROOT) == []


def test_the_repository_changelog_renders() -> None:
    text = (_REPO_ROOT / "CHANGELOG.md").read_text(encoding="utf-8")
    rendered = cl.render(text, cl.load_fragments(_REPO_ROOT))
    assert rendered.startswith(text.split("## [Unreleased]")[0])
    assert "## [Unreleased]" in rendered


# --- the release workflow ------------------------------------------------------------

_PYPI = _REPO_ROOT / ".github" / "workflows" / "publish-pypi.yml"
_SHA_PIN = re.compile(r"^[^@\s]+@[0-9a-f]{40}$")
_PIN_LINE = re.compile(r"uses: [^@\s]+@[0-9a-f]{40} # v\d+\.\d+\.\d+$")


def _pypi_build_steps() -> list[dict[str, object]]:
    workflow = yaml.safe_load(_PYPI.read_text(encoding="utf-8"))
    steps: list[dict[str, object]] = workflow["jobs"]["build"]["steps"]
    return steps


def test_publish_pypi_runs_the_release_check_before_it_builds() -> None:
    steps = _pypi_build_steps()
    runs = [str(step.get("run", "")) for step in steps]
    check = next(i for i, run in enumerate(runs) if "scripts/changelog.py release-check" in run)
    build = next(i for i, run in enumerate(runs) if "python -m build" in run)
    assert check < build
    # Unconditional: a workflow_dispatch publish is checked against pyproject's version.
    assert "if" not in steps[check]
    assert '"${GITHUB_REF_NAME#v}"' in runs[check] and "pyproject.toml" in runs[check]


def test_publish_pypi_pins_every_action_by_commit_sha() -> None:
    # Charter, Quality Gates: a workflow changed after the 2026-10-29 amendment pins every action.
    text = _PYPI.read_text(encoding="utf-8")
    workflow = yaml.safe_load(text)
    uses = [
        step["uses"]
        for job in workflow["jobs"].values()
        for step in job.get("steps", [])
        if "uses" in step
    ]
    assert uses
    assert all(_SHA_PIN.match(u) for u in uses), uses
    pin_lines = [line.strip() for line in text.splitlines() if re.search(r"uses: [^.]", line)]
    assert all(_PIN_LINE.search(line) for line in pin_lines), pin_lines


# --- pre-PR squad findings: guard against the merge-base, not main's tip ------------


def _branch(root: Path, name: str = "feature") -> None:
    _git(root, "checkout", "-q", "-b", name)


def _commit_all(root: Path, message: str) -> None:
    _git(root, "add", "-A")
    _git(root, "commit", "-q", "-m", message)


def test_guard_passes_a_stale_branch_after_main_cut_a_release(tmp_path: Path) -> None:
    root = _repo(tmp_path)
    _write_fragment(root, "1-a.md", "Added", "- From main.\n")
    _commit_base(root)
    _branch(root)
    _write_fragment(root, "2-b.md", "Fixed", "- From the branch.\n")
    _commit_all(root, "branch fragment")
    _git(root, "checkout", "-q", "main")
    assert (
        cl.main(["--root", str(root), "collect", "--version", "1.1.0", "--date", "2026-11-02"]) == 0
    )
    _commit_all(root, "release 1.1.0")
    _git(root, "checkout", "-q", "feature")

    assert cl.guard(root, "main") == []


def test_guard_refuses_a_hand_edit_on_a_branch_behind_a_new_fragment(tmp_path: Path) -> None:
    root = _repo(tmp_path)
    _commit_base(root)
    _branch(root)
    _git(root, "checkout", "-q", "main")
    _write_fragment(root, "200-b.md", "Added", "- Landed on main meanwhile.\n")
    _commit_all(root, "main gains a fragment")
    _git(root, "checkout", "-q", "feature")
    changelog = root / "CHANGELOG.md"
    changelog.write_text(
        changelog.read_text(encoding="utf-8").replace(
            "- An existing fix.\n", "- An existing fix.\n- HAND EDIT\n"
        ),
        encoding="utf-8",
    )

    (error,) = cl.guard(root, "main")
    assert "by hand" in error


def test_guard_allows_an_edit_of_the_opening_paragraph(tmp_path: Path) -> None:
    root = _repo(tmp_path)
    _commit_base(root)
    changelog = root / "CHANGELOG.md"
    changelog.write_text(
        changelog.read_text(encoding="utf-8").replace(
            "No package change yet.", "The version moves to 1.0.1."
        ),
        encoding="utf-8",
    )
    assert cl.guard(root, "main") == []


def test_guard_refuses_a_bogus_version_heading_that_is_not_a_release_cut(tmp_path: Path) -> None:
    root = _repo(tmp_path)
    _commit_base(root)
    changelog = root / "CHANGELOG.md"
    changelog.write_text(
        changelog.read_text(encoding="utf-8").replace(
            "- An existing fix.\n", "- An existing fix.\n- HAND\n"
        )
        + "\n## [9.9.9] - 2026-11-02\n",
        encoding="utf-8",
    )
    assert len(cl.guard(root, "main")) == 1


def test_guard_refuses_a_hand_edit_that_deletes_a_fragment_without_collecting_it(
    tmp_path: Path,
) -> None:
    root = _repo(tmp_path)
    fragment = _write_fragment(root, "175-add.md", "Added", "- Added it.\n")
    _commit_base(root)
    fragment.unlink()
    changelog = root / "CHANGELOG.md"
    changelog.write_text(
        changelog.read_text(encoding="utf-8").replace(
            "- An existing fix.\n", "- An existing fix.\n- HAND\n"
        ),
        encoding="utf-8",
    )
    assert len(cl.guard(root, "main")) == 1


def test_guard_fails_closed_without_a_merge_base(tmp_path: Path) -> None:
    root = _repo(tmp_path)
    _commit_base(root)
    _git(root, "checkout", "-q", "--orphan", "unrelated")
    _commit_all(root, "unrelated root")
    (error,) = cl.guard(root, "main")
    assert "merge-base" in error


def test_guard_without_a_base_warns_locally_and_fails_in_ci(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str]
) -> None:
    root = _repo(tmp_path)
    monkeypatch.delenv("CI", raising=False)
    assert cl.guard(root, None) == []
    assert "skipped" in capsys.readouterr().err
    monkeypatch.setenv("CI", "true")
    (error,) = cl.guard(root, None)
    assert "origin/main" in error


def test_guard_cli_reports_a_missing_changelog_as_an_error_line(
    tmp_path: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    root = _repo(tmp_path)
    _commit_base(root)
    (root / "CHANGELOG.md").unlink()
    assert cl.main(["--root", str(root), "guard"]) == 1
    assert capsys.readouterr().err.startswith("error: ")


# --- pre-PR squad findings: parsing, rendering and the CLI ---------------------------


def test_a_duplicate_unreleased_subsection_is_refused_rather_than_merged_lossily() -> None:
    changelog = _BASE_CHANGELOG.replace("### Fixed\n", "### Added\n\n- Again.\n\n### Fixed\n")
    fragment = cl.Fragment(path=Path("1-a.md"), issue=1, section="Added", body="- new")
    with pytest.raises(cl.ChangelogError, match="Added"):
        cl.render(changelog, [fragment])


@pytest.mark.parametrize("name", [".175-hidden.md", "sub"])
def test_hidden_files_and_directories_are_reported(tmp_path: Path, name: str) -> None:
    root = _repo(tmp_path)
    target = root / "docs/changelog/unreleased" / name
    if name == "sub":
        target.mkdir()
    else:
        target.write_text("---\nsection: Added\n---\n\n- x\n", encoding="utf-8")
    (error,) = cl.check(root)
    assert name in error
    assert cl.release_check(root, "1.0.0") != []


def test_a_gitkeep_is_not_a_fragment(tmp_path: Path) -> None:
    root = _repo(tmp_path)
    (root / "docs/changelog/unreleased" / ".gitkeep").write_text("", encoding="utf-8")
    assert cl.check(root) == []


def test_a_bom_and_crlf_fragment_parses(tmp_path: Path) -> None:
    root = _repo(tmp_path)
    path = root / "docs/changelog/unreleased" / "175-x.md"
    path.write_bytes("﻿---\r\nsection: Fixed\r\n---\r\n\r\n- Fixed (#175).\r\n".encode())
    assert cl.parse_fragment(path).body == "- Fixed (#175)."


def test_a_hash_line_inside_a_code_fence_is_not_a_heading(tmp_path: Path) -> None:
    root = _repo(tmp_path)
    _write_fragment(root, "175-x.md", "Added", "- Run it:\n\n```sh\n# a comment\nmake lint\n```\n")
    assert cl.check(root) == []


def test_a_level_two_line_inside_a_fence_does_not_end_unreleased() -> None:
    changelog = _BASE_CHANGELOG.replace(
        "- An existing fix.\n", "- An existing fix.\n\n```md\n## not a heading\n```\n"
    )
    fragment = cl.Fragment(path=Path("1-a.md"), issue=1, section="Fixed", body="- new fix")
    text = cl.render(changelog, [fragment])
    assert "```md\n## not a heading\n```\n- new fix\n" in text


@pytest.mark.parametrize(
    "argv",
    [
        ["collect", "--version", "1.1.0", "--date", "2026-99-99"],
        ["collect", "--version", "v1.1.0", "--date", "2026-11-02"],
        ["collect", "--date", "2026-11-02"],
    ],
)
def test_the_cli_refuses_bad_release_arguments(tmp_path: Path, argv: list[str]) -> None:
    root = _repo(tmp_path)
    with pytest.raises(SystemExit):
        cl.main(["--root", str(root), *argv])
    assert (root / "CHANGELOG.md").read_text(encoding="utf-8") == _BASE_CHANGELOG


def test_an_empty_release_cut_is_refused_unless_allowed(tmp_path: Path) -> None:
    changelog = "# Changelog\n\n## [Unreleased]\n\n## [1.0.0] - 2026-10-01\n\n- x\n"
    with pytest.raises(cl.ChangelogError, match="empty"):
        cl.render(changelog, [], version="1.1.0", date="2026-11-02")
    text = cl.render(changelog, [], version="1.1.0", date="2026-11-02", allow_empty=True)
    assert "## [Unreleased]\n\n## [1.1.0] - 2026-11-02\n\n## [1.0.0]" in text


def test_collect_with_a_malformed_fragment_writes_nothing(tmp_path: Path) -> None:
    root = _repo(tmp_path)
    good = _write_fragment(root, "175-good.md", "Added", "- Good.\n")
    _write_fragment(root, "176-bad.md", "Improved", "- Bad.\n")
    assert cl.main(["--root", str(root), "collect"]) == 1
    assert good.exists()
    assert (root / "CHANGELOG.md").read_text(encoding="utf-8") == _BASE_CHANGELOG


def test_collect_keeps_every_original_line_and_adds_each_fragment_once() -> None:
    text = (_REPO_ROOT / "CHANGELOG.md").read_text(encoding="utf-8")
    fragments = [
        cl.Fragment(path=Path(f"{i}-p.md"), issue=i, section=s, body=f"- probe {s} ({i})")
        for i, s in enumerate(cl.SECTIONS, start=1)
    ]
    rendered = cl.render(text, fragments)
    original = [line for line in text.splitlines() if line.strip()]
    remaining = iter(line for line in rendered.splitlines() if line.strip())
    assert all(any(line == r for r in remaining) for line in original)  # an ordered subsequence
    for fragment in fragments:
        assert rendered.count(fragment.body) == 1


# --- operator ruling 1: the fragment folder is not part of the docs site -------------

_DOCS_SITE = _REPO_ROOT / ".github" / "workflows" / "docs-site.yml"


def test_the_docs_job_drops_the_fragment_folder_before_any_npm_step() -> None:
    # Toolkit 0.5.0 walks every .md under docs/, so fragments would be pages that fail the
    # frontmatter gate. Until a toolkit release carries `exclude` (doc-kitty ADR-0070), the
    # docs job removes the folder from its disposable checkout first.
    workflow = yaml.safe_load(_DOCS_SITE.read_text(encoding="utf-8"))
    steps = workflow["jobs"]["build"]["steps"]
    runs = [str(step.get("run", "")) for step in steps]
    drop = next(i for i, run in enumerate(runs) if "rm -rf docs/changelog/unreleased" in run)
    npm = [i for i, run in enumerate(runs) if "npm " in run]
    assert npm and drop < min(npm)


def test_a_fragment_only_change_does_not_trigger_the_docs_build() -> None:
    on = yaml.load(_DOCS_SITE.read_text(encoding="utf-8"), Loader=yaml.BaseLoader)["on"]
    for event in ("pull_request", "push"):
        paths = on[event]["paths"]
        assert paths.index("!docs/changelog/unreleased/**") > paths.index("docs/**"), event


def test_the_local_doc_kitty_gates_leave_the_fragment_folder_out() -> None:
    agents = (_REPO_ROOT / "AGENTS.md").read_text(encoding="utf-8")
    for script in ("validate-frontmatter.mjs", "check-links.mjs"):
        line = next(line for line in agents.splitlines() if script in line)
        assert "--exclude docs/changelog/unreleased" in line, line


def test_the_fragment_readme_is_not_a_docs_page() -> None:
    readme = (_REPO_ROOT / "docs" / "changelog" / "unreleased" / "README.md").read_text(
        encoding="utf-8"
    )
    assert not readme.startswith("---"), "the folder holds entries, not pages: no doc frontmatter"
