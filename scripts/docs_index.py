#!/usr/bin/env python3
"""Generate the section index lists the docs used to keep by hand.

Issue #176, ADR 0005 D2. ``docs/changelog/README.md`` and ``docs/adr/README.md``
listed their pages by hand, so every pull request that added a page appended a
line there and bumped the README's ``updated:``, and parallel pull requests
collided. The committed README now holds only an empty marker pair::

    <!-- BEGIN GENERATED: index -->
    <!-- END GENERATED: index -->

and this script fills it from the pages themselves at docs-site build time
(``site/package.json`` ``prevalidate``, so before ``npm run validate`` checks
the links). Nothing is written back to ``main``.

- changelog: one ``- [YYYY-MM-DD — <title>](./<file>)`` line per page, the date from
  the file name, newest first by ``sidebar.order`` (then the file name).
- adr: a ``ID | Title | Status | Date`` table, the ID from the file name, the title
  from the frontmatter, Status and Date from the ``**Status:**``/``**Date:**`` lines.

``--write`` fills the markers, ``--clean`` empties them again (run it before you
commit after a local docs build), ``--check`` (``make lint``) fails if a committed
README carries a list or lacks the markers, or if a page cannot be indexed.

Standard library only: the docs job runs it with the runner's ``python3``.
"""

from __future__ import annotations

import argparse
import re
import sys
from collections.abc import Callable
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
BEGIN = "<!-- BEGIN GENERATED: index -->"
END = "<!-- END GENERATED: index -->"

_DATE_NAME_RE = re.compile(r"^(\d{4}-\d{2}-\d{2})-")
_ADR_NAME_RE = re.compile(r"^(\d{4})-[a-z0-9-]+\.md$")
_STATUS_RE = re.compile(r"^\*\*Status:\*\*\s*([A-Za-z]+)", re.M)
_ADR_DATE_RE = re.compile(r"^\*\*Date:\*\*\s*(\d{4}-\d{2}-\d{2})", re.M)


class IndexError_(Exception):
    """A page the generator cannot index, or a README without its markers."""


def _frontmatter(path: Path) -> list[str]:
    lines = path.read_text(encoding="utf-8").splitlines()
    if not lines or lines[0].strip() != "---":
        return []
    for i in range(1, len(lines)):
        if lines[i].strip() == "---":
            return lines[1:i]
    return []


def _unquote(value: str) -> str:
    value = value.strip()
    if len(value) >= 2 and value[0] == value[-1] == "'":
        return value[1:-1].replace("''", "'")
    if len(value) >= 2 and value[0] == value[-1] == '"':
        return re.sub(r'\\(["\\])', r"\1", value[1:-1])
    return value


def _title(path: Path) -> str | None:
    """The one-line title, or None when it is absent or in a form this reader does not parse
    (a block scalar, or an unquoted value carrying a ``#`` comment)."""
    for line in _frontmatter(path):
        if line.startswith("title:"):
            raw = line[len("title:") :].strip()
            if raw[:1] in ("|", ">") or (raw[:1] not in ("'", '"') and " #" in raw):
                return None
            return _unquote(raw) or None
    return None


def _sidebar_order(path: Path) -> int | None:
    in_sidebar = False
    child_indent: int | None = None
    for line in _frontmatter(path):
        if line.startswith("sidebar:"):
            in_sidebar, child_indent = True, None
        elif line and not line[0].isspace():
            in_sidebar = False
        elif in_sidebar and line.strip():
            indent = len(line) - len(line.lstrip())
            if child_indent is None:
                child_indent = indent
            if indent == child_indent and line.strip().startswith("order:"):
                try:
                    return int(line.split(":", 1)[1].strip())
                except ValueError:
                    return None
    return None


def _pages(directory: Path) -> list[Path]:
    pages = [*directory.glob("*.md"), *directory.glob("*.mdx")]
    return sorted(p for p in pages if p.stem != "README")


def changelog_lines(directory: Path) -> list[str]:
    entries: list[tuple[int, str, str]] = []
    errors: list[str] = []
    for page in _pages(directory):
        date = _DATE_NAME_RE.match(page.name)
        title = _title(page)
        order = _sidebar_order(page)
        if date is None or title is None or order is None:
            errors.append(
                f"{page.name}: a changelog page needs a YYYY-MM-DD- file name prefix, a title "
                "and sidebar.order"
            )
            continue
        text = title.replace("[", r"\[").replace("]", r"\]")
        entries.append((order, page.name, f"- [{date.group(1)} — {text}](./{page.name})"))
    if errors:
        raise IndexError_("; ".join(errors))
    return [line for _, _, line in sorted(entries)]


def adr_lines(directory: Path) -> list[str]:
    rows: list[str] = []
    errors: list[str] = []
    for page in _pages(directory):
        number = _ADR_NAME_RE.match(page.name)
        title = _title(page)
        body = page.read_text(encoding="utf-8")
        status = _STATUS_RE.search(body)
        date = _ADR_DATE_RE.search(body)
        if number is None or title is None or status is None or date is None:
            errors.append(
                f"{page.name}: an ADR needs an NNNN-<slug>.md name, a title, a **Status:** line "
                "and a **Date:** YYYY-MM-DD line"
            )
            continue
        cells = [f"[{number.group(1)}](./{page.name})", title, status.group(1), date.group(1)]
        rows.append("| " + " | ".join(c.replace("|", r"\|") for c in cells) + " |")
    if errors:
        raise IndexError_("; ".join(errors))
    return ["| ID | Title | Status | Date |", "|----|-------|--------|------|", *rows]


INDEXES: dict[str, Callable[[Path], list[str]]] = {
    "docs/changelog": changelog_lines,
    "docs/adr": adr_lines,
}


def _span(text: str, readme: str) -> tuple[int, int]:
    begin, end = text.find(BEGIN), text.find(END)
    if begin < 0 or end < begin:
        raise IndexError_(f"{readme}: it needs the marker pair '{BEGIN}' / '{END}'")
    return begin + len(BEGIN), end


def _fill(text: str, readme: str, lines: list[str]) -> str:
    start, end = _span(text, readme)
    inner = "\n" + "".join(f"{line}\n" for line in lines)
    return text[:start] + inner + text[end:]


def check(root: Path) -> list[str]:
    errors: list[str] = []
    for section, generate in INDEXES.items():
        readme = f"{section}/README.md"
        try:
            text = (root / readme).read_text(encoding="utf-8")
            start, end = _span(text, readme)
            if text[start:end].strip():
                errors.append(
                    f"{readme}: the generated list is committed; run "
                    "'python3 scripts/docs_index.py --clean' (the docs build regenerates it)"
                )
            generate(root / section)
        except IndexError_ as exc:
            errors.append(str(exc))
    return errors


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="docs_index.py", description=__doc__.split("\n\n")[0])
    parser.add_argument("--root", type=Path, default=REPO_ROOT)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--write", action="store_true", help="fill the markers (docs build)")
    mode.add_argument("--clean", action="store_true", help="empty the markers (before a commit)")
    mode.add_argument("--check", action="store_true", help="the committed READMEs carry no list")
    args = parser.parse_args(argv)
    root: Path = args.root

    if args.check:
        errors = check(root)
        for error in errors:
            print(f"error: {error}", file=sys.stderr)
        return 1 if errors else 0
    try:
        for section, generate in INDEXES.items():
            path = root / section / "README.md"
            text = path.read_text(encoding="utf-8")
            lines = generate(root / section) if args.write else []
            path.write_text(_fill(text, f"{section}/README.md", lines), encoding="utf-8")
    except IndexError_ as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
