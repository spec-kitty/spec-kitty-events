"""Generated section indexes (issue #176, ADR 0005 D2).

``docs/changelog/README.md`` and ``docs/adr/README.md`` used to carry hand-kept
lists that every pull request adding a page appended to, and whose ``updated:``
line it bumped, so parallel pull requests collided on them. The lists are now
generated between markers by ``scripts/docs_index.py`` at docs-site build time
(``npm run validate``'s ``prevalidate``); the committed README keeps only the
empty markers, and ``--check`` (``make lint``) holds it there.
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "scripts"))
import docs_index as di  # noqa: E402

_REPO_ROOT = Path(__file__).resolve().parent.parent

_README = """\
---
title: Index
---

# Index

Intro.

<!-- BEGIN GENERATED: index -->
<!-- END GENERATED: index -->

Outro.
"""


def _page(path: Path, title: str, order: int | None = None, body: str = "") -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    sidebar = f"sidebar:\n  order: {order}\n" if order is not None else ""
    path.write_text(
        f"---\ntitle: {title}\ndoc_status: active\n{sidebar}---\n\n{body}", encoding="utf-8"
    )


def _tree(tmp_path: Path) -> Path:
    root = tmp_path / "repo"
    for section in ("changelog", "adr"):
        (root / "docs" / section).mkdir(parents=True)
        (root / "docs" / section / "README.md").write_text(_README, encoding="utf-8")
    return root


# --- changelog list ------------------------------------------------------------------


def test_changelog_lines_are_newest_first_by_sidebar_order_then_name(tmp_path: Path) -> None:
    root = _tree(tmp_path)
    d = root / "docs" / "changelog"
    _page(d / "2026-10-12-a.md", '"Older"', -202610121)
    _page(d / "2026-10-19-b.md", "'Same day, second'", -202610192)
    _page(d / "2026-10-19-a.md", "Same day, second too", -202610192)
    _page(d / "2026-10-19-c.md", '"Same day, fourth: \\"quoted\\""', -202610194)

    assert di.changelog_lines(d) == [
        '- [2026-10-19 — Same day, fourth: "quoted"](./2026-10-19-c.md)',
        "- [2026-10-19 — Same day, second too](./2026-10-19-a.md)",
        "- [2026-10-19 — Same day, second](./2026-10-19-b.md)",
        "- [2026-10-12 — Older](./2026-10-12-a.md)",
    ]


def test_a_changelog_page_without_an_order_or_a_date_is_an_error(tmp_path: Path) -> None:
    root = _tree(tmp_path)
    d = root / "docs" / "changelog"
    _page(d / "2026-10-12-a.md", "No order")
    _page(d / "undated.md", "No date", -1)
    with pytest.raises(di.IndexError_) as exc:
        di.changelog_lines(d)
    assert "2026-10-12-a.md" in str(exc.value) and "undated.md" in str(exc.value)


# --- ADR table -----------------------------------------------------------------------


def test_adr_rows_come_from_the_filename_title_and_body(tmp_path: Path) -> None:
    root = _tree(tmp_path)
    d = root / "docs" / "adr"
    _page(
        d / "0002-two.md",
        "'ADR-0002: two'",
        body="**Status:** Proposed. Pending.\n\n**Date:** 2026-11-01\n",
    )
    _page(
        d / "0001-one.md",
        "'ADR: one'",
        body="**Status:** Accepted\n\n**Date:** 2026-10-27 (proposed 2026-10-13)\n",
    )
    assert di.adr_lines(d) == [
        "| ID | Title | Status | Date |",
        "|----|-------|--------|------|",
        "| [0001](./0001-one.md) | ADR: one | Accepted | 2026-10-27 |",
        "| [0002](./0002-two.md) | ADR-0002: two | Proposed | 2026-11-01 |",
    ]


def test_an_adr_without_a_status_is_an_error(tmp_path: Path) -> None:
    root = _tree(tmp_path)
    _page(root / "docs" / "adr" / "0001-one.md", "One", body="**Date:** 2026-10-27\n")
    with pytest.raises(di.IndexError_, match="0001-one.md"):
        di.adr_lines(root / "docs" / "adr")


def test_a_pipe_in_an_adr_title_is_escaped(tmp_path: Path) -> None:
    root = _tree(tmp_path)
    _page(
        root / "docs" / "adr" / "0001-one.md",
        "'a | b'",
        body="**Status:** Accepted\n**Date:** 2026-10-27\n",
    )
    assert (
        di.adr_lines(root / "docs" / "adr")[-1]
        == r"| [0001](./0001-one.md) | a \| b | Accepted | 2026-10-27 |"
    )


# --- write, clean, check -------------------------------------------------------------


def test_write_fills_the_markers_and_clean_empties_them(tmp_path: Path) -> None:
    root = _tree(tmp_path)
    _page(root / "docs" / "changelog" / "2026-10-12-a.md", "A", -202610121)
    _page(
        root / "docs" / "adr" / "0001-one.md",
        "One",
        body="**Status:** Accepted\n**Date:** 2026-10-27\n",
    )
    readme = root / "docs" / "changelog" / "README.md"

    assert di.main(["--root", str(root), "--write"]) == 0
    text = readme.read_text(encoding="utf-8")
    assert (
        "<!-- BEGIN GENERATED: index -->\n"
        "- [2026-10-12 — A](./2026-10-12-a.md)\n"
        "<!-- END GENERATED: index -->\n\nOutro.\n"
    ) in text
    assert di.main(["--root", str(root), "--write"]) == 0
    assert readme.read_text(encoding="utf-8") == text

    assert di.main(["--root", str(root), "--clean"]) == 0
    assert readme.read_text(encoding="utf-8") == _README


def test_check_passes_empty_markers_and_well_formed_pages(tmp_path: Path) -> None:
    root = _tree(tmp_path)
    _page(root / "docs" / "changelog" / "2026-10-12-a.md", "A", -202610121)
    assert di.check(root) == []


def test_check_refuses_a_committed_list(tmp_path: Path) -> None:
    root = _tree(tmp_path)
    _page(root / "docs" / "changelog" / "2026-10-12-a.md", "A", -202610121)
    di.main(["--root", str(root), "--write"])
    errors = di.check(root)
    assert sorted(e.split(":")[0] for e in errors) == [
        "docs/adr/README.md",
        "docs/changelog/README.md",
    ]
    assert all("--clean" in e for e in errors)


def test_check_refuses_missing_markers(tmp_path: Path) -> None:
    root = _tree(tmp_path)
    (root / "docs" / "adr" / "README.md").write_text("# ADRs\n\n- a hand list\n", encoding="utf-8")
    (error,) = di.check(root)
    assert "docs/adr/README.md" in error and "BEGIN GENERATED" in error


def test_check_reports_a_page_the_generator_cannot_index(tmp_path: Path) -> None:
    root = _tree(tmp_path)
    _page(root / "docs" / "changelog" / "2026-10-12-a.md", "No order")
    (error,) = di.check(root)
    assert "2026-10-12-a.md" in error


# --- the real repository -------------------------------------------------------------


def test_the_repository_indexes_are_committed_empty_and_generate() -> None:
    assert di.check(_REPO_ROOT) == []
    assert len(di.changelog_lines(_REPO_ROOT / "docs" / "changelog")) >= 14
    assert len(di.adr_lines(_REPO_ROOT / "docs" / "adr")) >= 6


def test_the_docsite_generates_the_indexes_before_it_validates() -> None:
    scripts = json.loads((_REPO_ROOT / "site" / "package.json").read_text(encoding="utf-8"))[
        "scripts"
    ]
    assert scripts["prevalidate"] == "python3 ../scripts/docs_index.py --write"
