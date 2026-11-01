#!/usr/bin/env python3
"""Changelog fragments: one file per change, folded into ``CHANGELOG.md`` at release time.

Issue #175, ADR 0005. Parallel pull requests used to append to the top of
``## [Unreleased]`` in ``CHANGELOG.md``; every merge put the other open pull
requests into conflict. A pull request now adds ``changelog.d/<issue>-<slug>.md``::

    ---
    section: Added
    ---

    - What changed and why it matters, one bullet or several (#175).

and never edits ``[Unreleased]`` by hand. Commands:

``check``
    Validate every fragment: its name, its ``section`` and its body. Run by ``make lint``.
``guard [--base REF]``
    Fail if ``[Unreleased]`` differs from the base (default ``origin/main``, then ``main``)
    unless the change consumes fragments or cuts a release section. Run by ``make lint``.
``preview``
    Print ``[Unreleased]`` as ``collect`` would write it; writes nothing.
``collect [--version X.Y.Z --date YYYY-MM-DD]``
    Fold every fragment into ``[Unreleased]`` and delete it. With ``--version`` the result
    becomes ``## [X.Y.Z] - DATE`` under a fresh, empty ``[Unreleased]``. Run in the release
    pull request: with ``--version`` for a PyPI ``v*`` release, without it for a
    ``service-v*`` release (that line records itself under ``[Unreleased]``).
``release-check --version X.Y.Z``
    Fail if fragments are still pending or ``CHANGELOG.md`` has no ``## [X.Y.Z]`` section.
    Run by ``publish-pypi.yml`` before it builds.

The released history is never rewritten: ``collect`` only touches ``[Unreleased]``.
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
FRAGMENT_DIR = "changelog.d"
CHANGELOG = "CHANGELOG.md"

# The order collect writes subsections in. Breaking and Migration lead, as they do in the
# released sections; the rest is Keep a Changelog's order.
SECTIONS = (
    "Breaking",
    "Migration",
    "Added",
    "Changed",
    "Deprecated",
    "Removed",
    "Fixed",
    "Security",
)

_NAME_RE = re.compile(r"^(?P<issue>[1-9][0-9]*)-[a-z0-9]+(?:-[a-z0-9]+)*\.md$")
_HEADING_RE = re.compile(r"^\s{0,3}#{1,6}\s")
_UNRELEASED = "## [Unreleased]"
_VERSION_HEADING_RE = re.compile(r"^## \[(?P<version>[^\]]+)\]", re.M)
_SKIPPED = {"README.md"}


class ChangelogError(Exception):
    """A fragment or the changelog is not in the expected shape."""


@dataclass(frozen=True)
class Fragment:
    path: Path
    issue: int
    section: str
    body: str


def _fragment_paths(root: Path) -> list[Path]:
    directory = root / FRAGMENT_DIR
    if not directory.is_dir():
        return []
    return sorted(
        p
        for p in directory.iterdir()
        if p.is_file() and not p.name.startswith(".") and p.name not in _SKIPPED
    )


def parse_fragment(path: Path) -> Fragment:
    """Parse one fragment file, or raise ``ChangelogError`` naming what is wrong."""
    name = f"{FRAGMENT_DIR}/{path.name}"
    match = _NAME_RE.match(path.name)
    if match is None:
        raise ChangelogError(
            f"{name}: the name must be <issue>-<slug>.md, slug in lowercase kebab-case"
        )
    text = path.read_text(encoding="utf-8")
    lines = text.splitlines()
    if not lines or lines[0].strip() != "---" or "---" not in (line.strip() for line in lines[1:]):
        raise ChangelogError(
            f"{name}: it must open with a frontmatter block (--- / section: <name> / ---)"
        )
    end = next(i for i in range(1, len(lines)) if lines[i].strip() == "---")
    meta: dict[str, str] = {}
    for line in lines[1:end]:
        if not line.strip():
            continue
        key, sep, value = line.partition(":")
        if not sep:
            raise ChangelogError(f"{name}: frontmatter line {line!r} is not 'key: value'")
        meta[key.strip()] = value.strip().strip("\"'")
    unknown = set(meta) - {"section"}
    if unknown:
        raise ChangelogError(
            f"{name}: unknown frontmatter key(s) {sorted(unknown)}; only 'section' is read"
        )
    section = meta.get("section", "")
    if section not in SECTIONS:
        raise ChangelogError(f"{name}: section {section!r} is not one of {', '.join(SECTIONS)}")
    body = "\n".join(lines[end + 1 :]).strip("\n")
    if not body.strip():
        raise ChangelogError(
            f"{name}: the body is empty; write the entry as one or more '- ' bullets"
        )
    if not body.startswith("- "):
        raise ChangelogError(f"{name}: the body must start with a '- ' bullet")
    for line in body.splitlines():
        if _HEADING_RE.match(line):
            raise ChangelogError(
                f"{name}: no headings in a fragment ({line.strip()!r}); the section sets it"
            )
    return Fragment(path=path, issue=int(match.group("issue")), section=section, body=body)


def load_fragments(root: Path) -> list[Fragment]:
    """Every well-formed fragment, ordered by issue number then file name."""
    fragments = []
    for path in _fragment_paths(root):
        try:
            fragments.append(parse_fragment(path))
        except ChangelogError:
            continue
    return sorted(fragments, key=lambda f: (f.issue, f.path.name))


def check(root: Path) -> list[str]:
    """One error per malformed fragment; empty when every fragment is well formed."""
    errors = []
    for path in _fragment_paths(root):
        try:
            parse_fragment(path)
        except ChangelogError as exc:
            errors.append(str(exc))
    return errors


# --- rendering -----------------------------------------------------------------------


def _split(text: str) -> tuple[str, list[str], str]:
    """``(head, unreleased_lines, tail)``: the lines strictly inside ``[Unreleased]``."""
    lines = text.splitlines(keepends=True)
    start = next((i for i, line in enumerate(lines) if line.rstrip() == _UNRELEASED), None)
    if start is None:
        raise ChangelogError(f"{CHANGELOG} has no '{_UNRELEASED}' section")
    end = next((i for i in range(start + 1, len(lines)) if lines[i].startswith("## ")), len(lines))
    return (
        "".join(lines[:start]),
        [line.rstrip("\n") for line in lines[start + 1 : end]],
        "".join(lines[end:]),
    )


def _parse_unreleased(lines: list[str]) -> tuple[str, list[tuple[str, str]]]:
    intro: list[str] = []
    subsections: list[tuple[str, list[str]]] = []
    for line in lines:
        if line.startswith("### "):
            subsections.append((line[4:].strip(), []))
        elif subsections:
            subsections[-1][1].append(line)
        else:
            intro.append(line)
    return "\n".join(intro).strip("\n"), [
        (name, "\n".join(body).strip("\n")) for name, body in subsections
    ]


def _insert_at(existing: list[str], name: str) -> int:
    rank = SECTIONS.index(name)
    known = [(i, SECTIONS.index(n)) for i, n in enumerate(existing) if n in SECTIONS]
    later = [i for i, r in known if r > rank]
    if later:
        return later[0]
    return known[-1][0] + 1 if known else len(existing)


def render(
    text: str, fragments: list[Fragment], *, version: str | None = None, date: str | None = None
) -> str:
    """``CHANGELOG.md`` with ``fragments`` folded into ``[Unreleased]``, cut as ``version`` if given."""
    head, lines, tail = _split(text)
    if version is not None:
        if any(m.group("version") == version for m in _VERSION_HEADING_RE.finditer(text)):
            raise ChangelogError(f"{CHANGELOG} already has a '## [{version}]' section")
        if not date:
            raise ChangelogError("a release section needs a date")
    if not fragments and version is None:
        return text

    intro, subsections = _parse_unreleased(lines)
    names = [name for name, _ in subsections]
    bodies = dict(subsections)
    for fragment in fragments:
        if fragment.section not in bodies:
            names.insert(_insert_at(names, fragment.section), fragment.section)
            bodies[fragment.section] = ""
        bodies[fragment.section] = "\n".join(
            b for b in (bodies[fragment.section], fragment.body) if b
        )

    blocks = [intro] if intro else []
    blocks += [f"### {name}\n\n{bodies[name]}" if bodies[name] else f"### {name}" for name in names]
    section = "".join(f"{block}\n\n" for block in blocks)
    if version is not None:
        unreleased = f"{_UNRELEASED}\n\n## [{version}] - {date}\n\n{section}"
    else:
        unreleased = f"{_UNRELEASED}\n\n{section}"
    return head + unreleased + tail


def unreleased_section(text: str) -> str:
    """The ``[Unreleased]`` section of ``text``, heading included."""
    _, lines, _ = _split(text)
    return "\n".join([_UNRELEASED, *lines]).rstrip("\n") + "\n"


# --- guard and release check ---------------------------------------------------------


def _git(root: Path, *args: str) -> str:
    return subprocess.run(
        ["git", *args], cwd=root, capture_output=True, text=True, check=True
    ).stdout


def resolve_base_ref(root: Path) -> str | None:
    for ref in ("origin/main", "main"):
        try:
            _git(root, "rev-parse", "--verify", "--quiet", ref)
        except (subprocess.CalledProcessError, FileNotFoundError):
            continue
        return ref
    return None


def guard(root: Path, base_ref: str | None) -> list[str]:
    """Refuse a hand edit of ``[Unreleased]``; a collect or a release cut is allowed."""
    if base_ref is None:
        return []
    try:
        base_text = _git(root, "show", f"{base_ref}:{CHANGELOG}")
    except subprocess.CalledProcessError:
        return []
    text = (root / CHANGELOG).read_text(encoding="utf-8")
    if unreleased_section(base_text) == unreleased_section(text):
        return []
    base_versions = {m.group("version") for m in _VERSION_HEADING_RE.finditer(base_text)}
    if any(m.group("version") not in base_versions for m in _VERSION_HEADING_RE.finditer(text)):
        return []
    base_fragments = _git(root, "ls-tree", "--name-only", base_ref, f"{FRAGMENT_DIR}/").split()
    if any(Path(p).name not in _SKIPPED and not (root / p).exists() for p in base_fragments):
        return []
    return [
        f"{CHANGELOG}: [Unreleased] was edited by hand. Add a fragment under {FRAGMENT_DIR}/ "
        f"instead (see {FRAGMENT_DIR}/README.md); only 'scripts/changelog.py collect' writes [Unreleased]."
    ]


def release_check(root: Path, version: str) -> list[str]:
    errors = []
    pending = [p.name for p in _fragment_paths(root)]
    if pending:
        errors.append(
            f"{len(pending)} fragment(s) still pending in {FRAGMENT_DIR}/ ({', '.join(pending)}); "
            f"run 'scripts/changelog.py collect --version {version} --date <YYYY-MM-DD>' in the release PR"
        )
    text = (root / CHANGELOG).read_text(encoding="utf-8")
    if not any(m.group("version") == version for m in _VERSION_HEADING_RE.finditer(text)):
        errors.append(f"{CHANGELOG} has no '## [{version}]' section")
    return errors


# --- command line --------------------------------------------------------------------


def _report(errors: list[str]) -> int:
    for error in errors:
        print(f"error: {error}", file=sys.stderr)
    return 1 if errors else 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="changelog.py", description=__doc__.split("\n\n")[0])
    parser.add_argument(
        "--root", type=Path, default=REPO_ROOT, help="repository root (default: this checkout)"
    )
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("check", help="validate every fragment")
    guard_cmd = sub.add_parser("guard", help="refuse a hand edit of [Unreleased]")
    guard_cmd.add_argument("--base", help="base ref (default: origin/main, then main)")
    sub.add_parser("preview", help="print [Unreleased] as collect would write it")
    collect_cmd = sub.add_parser(
        "collect", help="fold the fragments into CHANGELOG.md and delete them"
    )
    collect_cmd.add_argument("--version", help="cut a '## [VERSION] - DATE' release section")
    collect_cmd.add_argument(
        "--date", help="the release date, YYYY-MM-DD (required with --version)"
    )
    release_cmd = sub.add_parser(
        "release-check", help="no pending fragments and a section for VERSION"
    )
    release_cmd.add_argument("--version", required=True)
    args = parser.parse_args(argv)
    root: Path = args.root

    if args.command == "check":
        return _report(check(root))
    if args.command == "guard":
        return _report(guard(root, args.base or resolve_base_ref(root)))
    if args.command == "release-check":
        return _report(release_check(root, args.version))

    errors = check(root)
    if errors:
        return _report(errors)
    fragments = load_fragments(root)
    text = (root / CHANGELOG).read_text(encoding="utf-8")
    try:
        if args.command == "preview":
            print(unreleased_section(render(text, fragments)), end="")
            return 0
        if args.version and not args.date:
            parser.error("--version needs --date")
        if args.date and not re.fullmatch(r"\d{4}-\d{2}-\d{2}", args.date):
            parser.error("--date must be YYYY-MM-DD")
        rendered = render(text, fragments, version=args.version, date=args.date)
    except ChangelogError as exc:
        return _report([str(exc)])
    (root / CHANGELOG).write_text(rendered, encoding="utf-8")
    for fragment in fragments:
        fragment.path.unlink()
    print(f"collected {len(fragments)} fragment(s) into {CHANGELOG}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
