#!/usr/bin/env python3
"""Generate the committed ``kind: Schematic`` pages for every in-scope JSON schema
(spec-kitty-events#195, mission docs-site-overhaul-01M6GBDP WP04).

Page owners and routes (plan.md A2):

- ``src/spec_kitty_events/schemas/*_payload.schema.json`` ->
  ``docs/events/<family>/<stem>.md``; the family comes from the committed map
  ``scripts/schema_pages_map.json`` (fail closed: an unmapped payload schema, or a
  map entry naming no on-disk file, exits 1 naming the file).
- The remaining ``schemas/*.schema.json`` -> ``docs/events/shared/<stem>.md``
  (explicitly listed under ``shared`` in the map).
- ``src/spec_kitty_events/schemas/event.schema.json`` (the map's
  ``normative_envelope``) -> ``docs/api/envelope/event-envelope.md``.
- ``src/spec_kitty_events/producer_contract/data/*.schema.json`` ->
  ``docs/architecture/producer-row-schemas/<stem>.md``. Stems are DOTTED
  (``mission.created.v1``); the T019 spike proved Astro/doc-kitty route dotted
  stems verbatim (``dist/.../mission.created.v1/index.html``, root-absolute
  links into them resolve), so NO dots-to-dashes normalisation is applied here.
- The two producer-contract envelopes -> ``producer-envelope-write.md`` /
  ``producer-envelope-read.md`` in the producer-row hub.

Every family/producer/envelope hub carries a ``<!-- BEGIN GENERATED: schemas
-->`` … ``<!-- END GENERATED: schemas -->`` block listing its pages; content
outside the markers is authored and preserved. ``--write`` updates pages and
blocks; ``--check`` regenerates without touching the tree and fails naming the
file for each of: missing page, orphan page, content differs, hub link
missing. ``updated:`` on a page stays stable while its generated content is
unchanged (content-hash compare; a changed page gets the run date).

``schemas/generate.py``, ``schemas/__init__.py`` and the whole of
``producer_contract/composed/``, ``producer_contract/registry.json`` and
``producer_contract/fixtures/`` are excluded by construction: enumeration
globs only ``schemas/*.schema.json``, ``producer_contract/data/*.schema.json``
and two named envelope files. The staged copies under ``site/schemas/`` are
made by ``site/scripts/stage-docs-inputs.mjs`` (never committed, C-001).

Standard library only: the docs workflow runs this with a bare venv python.
``--root <dir>`` is the test seam; Makefile and CI run it without arguments.
"""

from __future__ import annotations

import argparse
import datetime as _dt
import json
import re
import sys
from dataclasses import dataclass, field
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent

SCHEMAS_DIR = Path("src/spec_kitty_events/schemas")
ROWS_DIR = Path("src/spec_kitty_events/producer_contract/data")
PRODUCER_DIR = Path("src/spec_kitty_events/producer_contract")
MAP_FILE = Path("scripts/schema_pages_map.json")
WRITE_ENVELOPE = "envelope.schema.json"
READ_ENVELOPE = "envelope.read.schema.json"

BEGIN = "<!-- BEGIN GENERATED: schemas -->"
END = "<!-- END GENERATED: schemas -->"

FAMILY_IDS = (
    "mission-lifecycle",
    "work-and-status",
    "review-audit-decisions",
    "runtime-observation",
    "collaboration",
    "semantics-and-analytics",
)
SHARED_ID = "shared"
PRODUCER_HUB = "producer-rows"
ENVELOPE_HUB = "api-envelope"

INTEGRATION, ARCHITECTURE, API = "Integration", "Architecture", "API"

SECTION_TYPE = {
    PRODUCER_HUB: ARCHITECTURE,
    ENVELOPE_HUB: API,
}

# The exact label strings pinned by FR-006 (tests assert them verbatim).
NORMATIVE_LABEL = "**Normative envelope 3.0.0.**"
PROFILE_LABEL = "**CloudEvents profile** (non-normative until 12.0.0)"


class SchemaPagesFailure(Exception):
    """A fail-closed problem (unmapped schema, route collision, drift)."""


def _fail(problems: list[str]) -> None:
    block = "\n".join(f"SCHEMA-PAGES DRIFT: {p}" for p in problems)
    raise SchemaPagesFailure(block)


# ---------------------------------------------------------------------------
# Small pure helpers
# ---------------------------------------------------------------------------


def _frontmatter_of(text: str) -> list[str]:
    lines = text.splitlines()
    if not lines or lines[0].strip() != "---":
        return []
    for i in range(1, len(lines)):
        if lines[i].strip() == "---":
            return lines[1:i]
    return []


def _frontmatter_value(text: str, key: str) -> str | None:
    for line in _frontmatter_of(text):
        if line.startswith(f"{key}:"):
            return line[len(f"{key}:") :].strip().strip('"').strip("'")
    return None


def _yaml_dq(value: str) -> str:
    """A deterministic YAML double-quoted scalar (JSON escapes are valid YAML)."""
    return json.dumps(value, ensure_ascii=False)


_SENTENCE_END = re.compile(r"\.\s")


def _first_sentence(text: str) -> str:
    flat = " ".join(text.split())
    m = _SENTENCE_END.search(flat)
    return flat[: m.start() + 1] if m else flat


def _trim_180(text: str) -> str:
    """Cap at 180 chars, cutting at a word boundary (never an ellipsis)."""
    if len(text) <= 180:
        return text
    cut = text[:180]
    if " " in cut:
        cut = cut[: cut.rfind(" ")]
    return cut.rstrip(" ,;:")


def _camelize(stem: str) -> str:
    return "".join(part[:1].upper() + part[1:] for part in stem.split("_"))


def _humanize_row_stem(stem: str) -> str:
    """``mission.created.v1`` -> ``mission.created v1`` (space before the version)."""
    return re.sub(r"\.v(\d+)$", r" v\1", stem)


def _route_of(page_rel: Path) -> str:
    """POSIX route of a docs-relative page path (mirrors the toolkit: README -> index)."""
    p = page_rel.as_posix().removeprefix("docs/")[: -len(".md")]
    if p.endswith("/README"):
        p = p[: -len("/README")]
    return "/" + p + "/"


def _walk_refs(node: object) -> list[str]:
    """Every cross-file ``$ref`` in a schema document (in-file ``#/…`` refs excluded)."""
    refs: list[str] = []
    if isinstance(node, dict):
        value = node.get("$ref")
        if isinstance(value, str) and value.split("#", 1)[0].strip():
            refs.append(value.split("#", 1)[0])
        for child in node.items():
            refs.extend(_walk_refs(child[1]))
    elif isinstance(node, list):
        for child in node:
            refs.extend(_walk_refs(child))
    return refs


# ---------------------------------------------------------------------------
# The page model
# ---------------------------------------------------------------------------


@dataclass
class Page:
    """One generated schematic page."""

    source_rel: str  # repo path of the schema, posix
    page_rel: str  # repo path of the generated page, posix
    staged_rel: str  # schematic_path (staged path under site/)
    title: str
    description: str
    section_type: str
    hub_id: str
    what_line: str  # the one-line "what this is" sentence
    uses: list[Page] = field(default_factory=list)

    @property
    def route(self) -> str:
        return _route_of(Path(self.page_rel))

    @property
    def stem(self) -> str:
        return Path(self.source_rel).name[: -len(".schema.json")]


@dataclass
class Hub:
    """A hub page carrying a BEGIN/END GENERATED block."""

    id: str
    path_rel: str  # docs-relative repo path
    append_block: bool  # family .md pages: append at end when markers absent
    hub_title: str = ""  # read from the hub file's frontmatter
    members: list[Page] = field(default_factory=list)

    @property
    def route(self) -> str:
        return _route_of(Path(self.path_rel))


def _description_of(doc: dict, fallback: str) -> str:
    raw = doc.get("description")
    if isinstance(raw, str) and raw.strip():
        return _trim_180(_first_sentence(raw))
    return fallback


# ---------------------------------------------------------------------------
# Enumeration and plan building
# ---------------------------------------------------------------------------


def _load_map(root: Path) -> dict:
    path = root / MAP_FILE
    problems: list[str] = []
    data = json.loads(path.read_text(encoding="utf-8"))
    families: dict[str, dict] = data["families"]
    missing_files: set[str] = set()
    claimed: dict[str, str] = {}

    disk = {p.name for p in (root / SCHEMAS_DIR).glob("*.schema.json")}

    normative = data["normative_envelope"]
    if normative not in disk:
        problems.append(f"{normative} (map's normative_envelope names no file on disk)")

    for fam, bucket in families.items():
        for name in bucket["schemas"]:
            if name not in disk:
                missing_files.add(name)
            elif name in claimed:
                problems.append(f"{name} (claimed by both '{claimed[name]}' and '{fam}')")
            else:
                claimed[name] = fam
                if name.endswith("_payload.schema.json") and fam == SHARED_ID:
                    problems.append(f"{name} (a payload schema may not live under 'shared')")
    for name in missing_files:
        problems.append(f"{name} (map entry names a file that does not exist)")
    event_types: dict[str, str] = data.get("event_types", {})
    for stem in sorted(event_types):
        if f"{stem}.schema.json" not in claimed or not stem.endswith("_payload"):
            problems.append(f"{stem} (map's event_types names no mapped payload schema)")
    for name in sorted(disk - {normative} - set(claimed)):
        flavour = (
            "payload schema not in the family map"
            if name.endswith("_payload.schema.json")
            else ("schema not in the family map")
        )
        problems.append(f"name={name} ({flavour})")
    if problems:
        _fail(problems)
    return {
        "families": families,
        "normative_envelope": normative,
        "event_types": event_types,
    }


def _payload_what(stem: str, doc: dict, event_types: dict[str, str]) -> str:
    event = event_types.get(stem) or _camelize(stem[: -len("_payload")])
    line = f"Payload schema for `{event}` events."
    discriminator = doc.get("discriminator")
    if isinstance(discriminator, dict) and discriminator.get("propertyName"):
        line += f" Variants by `{discriminator['propertyName']}`."
    return line


def build_plan(root: Path) -> tuple[list[Page], dict[str, Hub]]:
    """The full expected set of schematic pages and hub blocks over ``root``."""
    family_map = _load_map(root)
    families = family_map["families"]

    hubs: dict[str, Hub] = {
        fam: Hub(id=fam, path_rel=f"docs/events/{fam}.md", append_block=True) for fam in FAMILY_IDS
    }
    hubs[SHARED_ID] = Hub(id=SHARED_ID, path_rel="docs/events/shared/README.md", append_block=False)
    hubs[PRODUCER_HUB] = Hub(
        id=PRODUCER_HUB,
        path_rel="docs/architecture/producer-row-schemas/README.md",
        append_block=False,
    )
    hubs[ENVELOPE_HUB] = Hub(
        id=ENVELOPE_HUB, path_rel="docs/api/envelope/README.md", append_block=False
    )

    pages: list[Page] = []
    problems: list[str] = []
    source_by_name: dict[str, str] = {}

    # Sets A: the flat package schemas (payload families + shared + normative envelope).
    for fam, bucket in families.items():
        section = SECTION_TYPE.get(fam, INTEGRATION)
        base = f"docs/events/{fam}" if fam != SHARED_ID else "docs/events/shared"
        for name in bucket["schemas"]:
            source_rel = (SCHEMAS_DIR / name).as_posix()
            doc = json.loads((root / source_rel).read_text(encoding="utf-8"))
            stem = name[: -len(".schema.json")]
            payload = name.endswith("_payload.schema.json")
            fallback = f"JSON Schema {stem}."
            if payload:
                fallback = f"Payload schema for `{_camelize(stem[: -len('_payload')])}` events."
            description = _description_of(doc, fallback)
            pages.append(
                Page(
                    source_rel=source_rel,
                    page_rel=f"{base}/{stem}.md",
                    staged_rel=f"schemas/{name}",
                    title=f"Schema: {stem}",
                    description=description,
                    section_type=section,
                    hub_id=fam,
                    what_line=(
                        _payload_what(stem, doc, family_map["event_types"])
                        if payload
                        else "A shared building block of the event model."
                    ),
                )
            )
            source_by_name[name] = source_rel

    # Set D: the normative envelope.
    normative_name = family_map["normative_envelope"]
    source_rel = (SCHEMAS_DIR / normative_name).as_posix()
    doc = json.loads((root / source_rel).read_text(encoding="utf-8"))
    pages.append(
        Page(
            source_rel=source_rel,
            page_rel="docs/api/envelope/event-envelope.md",
            staged_rel=f"schemas/{normative_name}",
            title="Schema: event envelope",
            description=_description_of(doc, "The normative 3.0.0 event envelope."),
            section_type=API,
            hub_id=ENVELOPE_HUB,
            what_line="",
        )
    )
    source_by_name[normative_name] = source_rel

    # Set B: the 36 producer rows (dotted stems).
    for path in sorted((root / ROWS_DIR).glob("*.schema.json")):
        source_rel = (ROWS_DIR / path.name).as_posix()
        doc = json.loads(path.read_text(encoding="utf-8"))
        stem = path.name[: -len(".schema.json")]
        pages.append(
            Page(
                source_rel=source_rel,
                page_rel=f"docs/architecture/producer-row-schemas/{stem}.md",
                staged_rel=f"schemas/producer-rows/{path.name}",
                title=f"Schema: {_humanize_row_stem(stem)} (producer row)",
                description=_description_of(doc, f"Producer row {stem}."),
                section_type=ARCHITECTURE,
                hub_id=PRODUCER_HUB,
                what_line=f"Data schema for the `{stem}` producer row (the CloudEvents `data` member).",
            )
        )
        source_by_name[path.name] = source_rel

    # Set C: the two producer envelope profiles.
    for name, page_stem, role in (
        (WRITE_ENVELOPE, "producer-envelope-write", "write"),
        (READ_ENVELOPE, "producer-envelope-read", "read"),
    ):
        path = root / PRODUCER_DIR / name
        if not path.is_file():
            problems.append(f"{path.relative_to(root)} (producer envelope missing on disk)")
            continue
        source_rel = (PRODUCER_DIR / name).as_posix()
        doc = json.loads(path.read_text(encoding="utf-8"))
        pages.append(
            Page(
                source_rel=source_rel,
                page_rel=f"docs/architecture/producer-row-schemas/{page_stem}.md",
                staged_rel=f"schemas/{name}",
                title=f"Schema: producer envelope ({role} profile)",
                description=_description_of(doc, f"The CloudEvents {role} profile."),
                section_type=ARCHITECTURE,
                hub_id=PRODUCER_HUB,
                what_line="",
            )
        )
        source_by_name[name] = source_rel

    if problems:
        _fail(problems)

    # Uses: cross-file $refs must name a schema that itself has a page.
    route_by_source = {p.source_rel: p for p in pages}
    for page in pages:
        doc = json.loads((root / page.source_rel).read_text(encoding="utf-8"))
        uses: list[Page] = []
        for ref in sorted(set(_walk_refs(doc))):
            name = Path(ref).name
            target = route_by_source.get(source_by_name.get(name, ""))
            if target is None:
                problems.append(
                    f"{page.source_rel} (cross-file $ref '{ref}' names a schema without a page)"
                )
            elif target is not page:
                uses.append(target)
        page.uses = uses

    if problems:
        _fail(problems)

    # Route-collision guard (spec edge case): one page per route, one route per page.
    by_route: dict[str, list[Page]] = {}
    for page in pages:
        by_route.setdefault(page.route, []).append(page)
    for route, clash in sorted(by_route.items()):
        if len(clash) > 1:
            sources = ", ".join(sorted(p.source_rel for p in clash))
            problems.append(f"{route} (route collision: {sources})")
    if problems:
        _fail(problems)

    for page in pages:
        hubs[page.hub_id].members.append(page)

    pages.sort(key=lambda p: p.page_rel)
    return pages, hubs


# ---------------------------------------------------------------------------
# Emission
# ---------------------------------------------------------------------------


def _cross_links() -> str:
    write = "/architecture/producer-row-schemas/producer-envelope-write/"
    read = "/architecture/producer-row-schemas/producer-envelope-read/"
    norm = "/api/envelope/event-envelope/"
    return write, read, norm


def _page_body(page: Page, hubs: dict[str, Hub], run_date: str) -> str:
    lines: list[str] = ["---"]
    for key, value in (
        ("title", _yaml_dq(page.title)),
        ("description", _yaml_dq(page.description)),
        ("doc_status", "active"),
        ("updated", run_date),
        ("type", page.section_type),
        ("kind", "Schematic"),
        ("schematic_path", page.staged_rel),
    ):
        lines.append(f"{key}: {value}")
    lines.append("---")
    lines.append("")
    lines.append(f"# {page.title}")
    lines.append("")

    write_role = page.page_rel.endswith("producer-envelope-write.md")
    read_role = page.page_rel.endswith("producer-envelope-read.md")
    normative = page.page_rel.endswith("event-envelope.md")
    write, read, norm = _cross_links()
    if normative:
        lines.append(
            f"{NORMATIVE_LABEL} The on-wire envelope every event family rides in. "
            f"The CloudEvents [write profile]({write}) and [read profile]({read}) of the "
            "producer contract are non-normative until 12.0.0."
        )
    elif write_role:
        lines.append(
            f"{PROFILE_LABEL} — the WRITE profile: attribute rules for CloudEvents rows a "
            f"Spec Kitty producer writes. The normative envelope is "
            f"[the 3.0.0 event envelope]({norm}); the read-side sibling is the "
            f"[read profile]({read})."
        )
    elif read_role:
        lines.append(
            f"{PROFILE_LABEL} — the READ profile: attribute rules for CloudEvents rows as a "
            f"reader meets them, including upcast legacy rows. The normative envelope is "
            f"[the 3.0.0 event envelope]({norm}); the write-side sibling is the "
            f"[write profile]({write})."
        )
    else:
        lines.append(page.what_line)
    lines.append("")
    lines.append(
        f"Generated from `{page.source_rel}` by `scripts/schema_pages.py`; never hand-edit (C-001)."
    )
    lines.append("")

    hub = hubs[page.hub_id]
    if page.hub_id in FAMILY_IDS:
        lines.append("## Family")
        lines.append("")
        lines.append(f"Part of the [{hub.hub_title}]({hub.route}) event family.")
    else:
        lines.append("## Family")
        lines.append("")
        lines.append(f"Listed on the [{hub.hub_title}]({hub.route}) hub.")
    lines.append("")

    uses: list[Page] = getattr(page, "uses", [])
    if uses:
        lines.append("## Uses")
        lines.append("")
        for target in sorted(uses, key=lambda p: p.route):
            lines.append(f"- [{target.title}]({target.route})")
        lines.append("")

    return "\n".join(lines).rstrip("\n") + "\n"


def _hub_block(hub: Hub) -> str:
    out = [BEGIN, "## Schemas", ""]
    for page in sorted(hub.members, key=lambda p: p.route):
        out.append(f"- [{page.title}]({page.route}) — {page.description}")
    out.append(END)
    return "\n".join(out)


def update_hub_text(old: str, hub: Hub) -> str:
    """Replace (or, for family pages, append) the generated block in a hub's text."""
    block = _hub_block(hub)
    if BEGIN in old and END in old:
        pre, rest = old.split(BEGIN, 1)
        _, post = rest.split(END, 1)
        return (pre + block + post).rstrip("\n") + "\n"
    if hub.append_block:
        return old.rstrip("\n") + "\n\n" + block + "\n"
    raise SchemaPagesFailure(
        f"SCHEMA-PAGES DRIFT: {hub.path_rel} (hub link missing: no BEGIN/END GENERATED markers)"
    )


def _read_hub_titles(root: Path, hubs: dict[str, Hub]) -> None:
    problems: list[str] = []
    for hub in hubs.values():
        path = root / hub.path_rel
        if not path.is_file():
            problems.append(f"{hub.path_rel} (hub link missing: hub page does not exist)")
            continue
        title = _frontmatter_value(path.read_text(encoding="utf-8"), "title")
        if not title:
            problems.append(f"{hub.path_rel} (hub page has no frontmatter title)")
        else:
            hub.hub_title = title
    if problems:
        _fail(problems)


# ---------------------------------------------------------------------------
# --write / --check
# ---------------------------------------------------------------------------


def _expected_pages(root: Path) -> tuple[list[Page], dict[str, Hub]]:
    pages, hubs = build_plan(root)
    _read_hub_titles(root, hubs)
    return pages, hubs


def _page_bytes(root: Path, page: Page, hubs: dict[str, Hub], run_date: str) -> tuple[str, str]:
    """Expected page text. Reuses the committed ``updated:`` when nothing else changed."""
    today = f"{run_date}T00:00:00Z"
    fresh = _page_body(page, hubs, today)
    path = root / page.page_rel
    if path.is_file():
        old = path.read_text(encoding="utf-8")
        old_updated = _frontmatter_value(old, "updated")
        if old_updated:
            kept = _page_body(page, hubs, old_updated)
            if kept == old:
                return kept, "unchanged"
    return fresh, "changed"


def _managed_dirs() -> list[str]:
    """The generator-owned docs trees (hubs' README.md files exempt from orphan checks)."""
    dirs = [f"docs/events/{fam}" for fam in FAMILY_IDS]
    dirs.append("docs/events/shared")
    dirs.append("docs/architecture/producer-row-schemas")
    dirs.append("docs/api/envelope")
    return dirs


def _find_orphans(root: Path, pages: list[Page]) -> list[str]:
    expected = {p.page_rel for p in pages}
    problems: list[str] = []
    for directory in _managed_dirs():
        base = root / directory
        if not base.is_dir():
            continue
        for path in sorted(base.rglob("*.md")):
            rel = path.relative_to(root).as_posix()
            if rel in expected or path.name == "README.md":
                continue
            problems.append(f"{rel} (orphan page: no schema maps to this page)")
    return problems


def _check_or_write(root: Path, write: bool) -> tuple[int, list[str]]:
    run_date = _dt.datetime.now(_dt.timezone.utc).date().isoformat()
    pages, hubs = _expected_pages(root)
    problems: list[str] = []
    changed_pages = 0
    changed_hubs = 0

    expected_by_rel = {p.page_rel: p for p in pages}
    for rel, page in sorted(expected_by_rel.items()):
        path = root / rel
        expected, _ = _page_bytes(root, page, hubs, run_date)
        if not path.is_file():
            if not write:
                problems.append(f"{rel} (missing page: no page for schema {page.source_rel})")
                continue
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(expected, encoding="utf-8")
            changed_pages += 1
        elif path.read_text(encoding="utf-8") != expected:
            if not write:
                problems.append(f"{rel} (content differs from the generator output)")
            else:
                path.write_text(expected, encoding="utf-8")
                changed_pages += 1
    problems.extend(_find_orphans(root, pages))

    for hub in sorted(hubs.values(), key=lambda h: h.path_rel):
        path = root / hub.path_rel
        old = path.read_text(encoding="utf-8")
        try:
            new = update_hub_text(old, hub)
        except SchemaPagesFailure as exc:
            # Markers missing from a fixed hub README: fail closed in both modes.
            problems.append(str(exc).removeprefix("SCHEMA-PAGES DRIFT: "))
            continue
        if old != new:
            if write:
                path.write_text(new, encoding="utf-8")
                changed_hubs += 1
            else:
                block_lines = set(_hub_block(hub).splitlines())
                old_lines = set(old.splitlines())
                missing = [ln for ln in block_lines if ln.startswith("- [") and ln not in old_lines]
                problem = "hub link missing" if missing else "content differs"
                problems.append(f"{hub.path_rel} ({problem}: {missing[0] if missing else 'block'})")

    return (changed_pages + changed_hubs, problems)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--write", action="store_true", help="write pages and hub blocks")
    mode.add_argument("--check", action="store_true", help="fail on any drift, writing nothing")
    parser.add_argument(
        "--root",
        type=Path,
        default=REPO_ROOT,
        help="repository root to operate on (test seam; default: this script's repo)",
    )
    args = parser.parse_args(argv)
    root = args.root.resolve()
    try:
        changed, problems = _check_or_write(root, write=args.write)
    except SchemaPagesFailure as exc:
        print(str(exc), file=sys.stderr)
        return 1
    if problems:
        print("\n".join(f"SCHEMA-PAGES DRIFT: {p}" for p in problems), file=sys.stderr)
        return 1
    verb = "written" if args.write else "checked"
    print(f"schema_pages: {verb}; {changed} file(s) {'changed' if args.write else 'clean'}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
