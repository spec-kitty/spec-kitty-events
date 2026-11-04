"""The schema-page generator and its drift check (issue #195, mission
docs-site-overhaul-01M6GBDP WP04).

``scripts/schema_pages.py --write`` emits one committed ``kind: Schematic`` page per
in-scope JSON schema (payloads into their family tree from the committed map, the other
``schemas/`` files into the shared hub, the dotted producer rows and the two CloudEvents
envelope profiles into the producer-row hub, the normative envelope into
``docs/api/envelope/``) and fills the hubs' ``BEGIN/END GENERATED: schemas`` blocks.
``--check`` fails closed — naming the file — on missing pages, orphan pages, drifted
content, unmapped payloads, excluded-tree leakage, unknown cross-file ``$ref`` targets
and route collisions. The tests below build fixture mini-trees (``--root`` is the test
seam); no network, fully deterministic.
"""

from __future__ import annotations

import datetime as dt
import json
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "scripts"))
import schema_pages as sp  # noqa: E402

_REPO_ROOT = Path(__file__).resolve().parent.parent

# The stdlib datetime CLASS, captured before any monkeypatching of the stdlib module.
_REAL_DATETIME = dt.datetime

_FAM_A = "mission-lifecycle"
_FAM_B = "work-and-status"

_ENVELOPE_README = """\
---
title: The envelope
description: Fake fixture hub.
doc_status: active
updated: 2026-11-03T00:00:00Z
type: API
kind: Hub
---

# The envelope

<!-- BEGIN GENERATED: schemas -->
<!-- END GENERATED: schemas -->
"""

_PRODUCER_README = _ENVELOPE_README.replace("The envelope", "Producer rows").replace(
    "type: API", "type: Architecture"
)


def _schema(description: str, *, discriminator: bool = False) -> str:
    doc: dict[str, object] = {
        "$schema": "https://json-schema.org/draft/2020-12/schema",
        "type": "object",
        "properties": {"id": {"type": "string"}},
    }
    if description:
        doc["description"] = description
    if discriminator:
        doc["oneOf"] = []
        doc["discriminator"] = {"propertyName": "origin_surface"}
    return json.dumps(doc, indent=1) + "\n"


def _hub(path: Path, title: str, *, markers: bool) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    block = f"{sp.BEGIN}\n{sp.END}\n" if markers else ""
    path.write_text(
        f"---\ntitle: {title}\ndescription: Fake fixture hub page for the tests.\n"
        f"doc_status: active\nupdated: 2026-11-03T00:00:00Z\ntype: Integration\n"
        f"kind: Hub\n---\n\n# {title}\n\nAuthored prose.\n\n{block}",
        encoding="utf-8",
    )


def _tree(tmp_path: Path) -> Path:
    """A fixture mini-tree: 2 families, 3 flat schemas (2 payloads + 1 shared),
    1 row, 2 producer envelopes, the normative envelope, and the excluded trees."""
    root = tmp_path / "repo"
    schemas = root / "src" / "spec_kitty_events" / "schemas"
    pd = root / "src" / "spec_kitty_events" / "producer_contract"
    schemas.mkdir(parents=True)
    (schemas / "__pycache__").mkdir()
    (pd / "data").mkdir(parents=True)
    (pd / "composed").mkdir()
    (pd / "fixtures").mkdir()

    (schemas / "alpha_payload.schema.json").write_text(
        _schema("Typed payload for Alpha events."), encoding="utf-8"
    )
    (schemas / "beta_payload.schema.json").write_text(
        _schema("Typed payload for Beta events."), encoding="utf-8"
    )
    (schemas / "shared_thing.schema.json").write_text(
        _schema("A shared building block. Second sentence, ignored."), encoding="utf-8"
    )
    (schemas / "event.schema.json").write_text(
        _schema("Immutable event with causal metadata."), encoding="utf-8"
    )
    (pd / "data" / "alpha.done.v1.schema.json").write_text(
        _schema("An Alpha was done."), encoding="utf-8"
    )
    (pd / "envelope.schema.json").write_text(_schema("The write profile."), encoding="utf-8")
    (pd / "envelope.read.schema.json").write_text(_schema("The read profile."), encoding="utf-8")
    # Excluded by rule (FR-005 edge cases): these must never get pages.
    (pd / "composed" / "extra.schema.json").write_text(_schema("Composed."), encoding="utf-8")
    (pd / "registry.json").write_text("{}", encoding="utf-8")
    (pd / "fixtures" / "fx.schema.json").write_text(_schema("Fixture."), encoding="utf-8")

    for fam in sp.FAMILY_IDS:
        _hub(root / "docs" / "events" / f"{fam}.md", f"Hub {fam}", markers=False)
    _hub(root / "docs" / "events" / "shared" / "README.md", "Shared types", markers=True)
    (root / "docs" / "architecture" / "producer-row-schemas").mkdir(parents=True)
    (root / "docs" / "architecture" / "producer-row-schemas" / "README.md").write_text(
        _PRODUCER_README, encoding="utf-8"
    )
    (root / "docs" / "api" / "envelope").mkdir(parents=True)
    (root / "docs" / "api" / "envelope" / "README.md").write_text(
        _ENVELOPE_README, encoding="utf-8"
    )

    page_map = {
        "version": 1,
        "normative_envelope": "event.schema.json",
        "families": {
            _FAM_A: {"hub": f"docs/events/{_FAM_A}.md", "schemas": ["alpha_payload.schema.json"]},
            _FAM_B: {"hub": f"docs/events/{_FAM_B}.md", "schemas": ["beta_payload.schema.json"]},
            "shared": {"hub": "docs/events/shared/", "schemas": ["shared_thing.schema.json"]},
        },
    }
    (root / "scripts").mkdir()
    (root / "scripts" / "schema_pages_map.json").write_text(
        json.dumps(page_map, indent=2) + "\n", encoding="utf-8"
    )
    return root


# The exact expected page set.
EXPECTED_PAGES = {
    f"docs/events/{_FAM_A}/alpha_payload.md",
    f"docs/events/{_FAM_B}/beta_payload.md",
    "docs/events/shared/shared_thing.md",
    "docs/api/envelope/event-envelope.md",
    "docs/architecture/producer-row-schemas/alpha.done.v1.md",
    "docs/architecture/producer-row-schemas/producer-envelope-write.md",
    "docs/architecture/producer-row-schemas/producer-envelope-read.md",
}


def _write(root: Path) -> int:
    return sp.main(["--write", "--root", str(root)])


def _check(root: Path) -> int:
    return sp.main(["--check", "--root", str(root)])


def _generated_markdown(root: Path) -> dict[str, str]:
    out: dict[str, str] = {}
    for base in ("docs/events", "docs/architecture", "docs/api"):
        for path in sorted((root / base).rglob("*.md")):
            out[str(path.relative_to(root))] = path.read_text(encoding="utf-8")
    return out


def _freeze_run_date(monkeypatch: pytest.MonkeyPatch, date: str) -> None:
    """Pin the generator's run date (updated: <date>T00:00:00Z)."""
    frozen = _REAL_DATETIME.fromisoformat(f"{date}T00:00:00+00:00")

    class _FrozenDateTime:
        @staticmethod
        def now(tz: dt.tzinfo | None = None) -> dt.datetime:
            del tz
            return frozen

    monkeypatch.setattr(sp._dt, "datetime", _FrozenDateTime)


# --- the happy path -----------------------------------------------------------------


def test_write_produces_exactly_the_expected_file_set_and_check_is_clean(
    tmp_path: Path,
) -> None:
    root = _tree(tmp_path)
    assert _write(root) == 0
    for rel in EXPECTED_PAGES:
        assert (root / rel).is_file(), rel
    assert _check(root) == 0


def test_regeneration_is_byte_identical(tmp_path: Path) -> None:
    root = _tree(tmp_path)
    assert _write(root) == 0
    snapshot = _generated_markdown(root)
    assert _write(root) == 0
    assert _generated_markdown(root) == snapshot


def test_page_frontmatter_carries_the_pinned_fields(tmp_path: Path) -> None:
    root = _tree(tmp_path)
    assert _write(root) == 0
    page = (root / f"docs/events/{_FAM_A}/alpha_payload.md").read_text(encoding="utf-8")
    assert 'title: "Schema: alpha_payload"' in page
    assert 'description: "Typed payload for Alpha events."' in page
    assert "\ntype: Integration\n" in page
    assert "\nkind: Schematic\n" in page
    assert "\nschematic_path: schemas/alpha_payload.schema.json\n" in page
    row = (root / "docs/architecture/producer-row-schemas/alpha.done.v1.md").read_text(
        encoding="utf-8"
    )
    assert 'title: "Schema: alpha.done v1 (producer row)"' in row
    assert "\ntype: Architecture\n" in row
    assert "schematic_path: schemas/producer-rows/alpha.done.v1.schema.json\n" in row
    # A shared page's description is the schema description's first sentence only.
    shared = (root / "docs/events/shared/shared_thing.md").read_text(encoding="utf-8")
    assert 'description: "A shared building block."' in shared
    frontmatter = shared.split("---\n")[1]
    assert "Second sentence" not in frontmatter


def test_hubs_link_every_member_and_preserve_authored_content(tmp_path: Path) -> None:
    root = _tree(tmp_path)
    assert _write(root) == 0
    fam_hub = (root / "docs" / "events" / f"{_FAM_A}.md").read_text(encoding="utf-8")
    assert sp.BEGIN in fam_hub and sp.END in fam_hub
    assert f"- [Schema: alpha_payload](/events/{_FAM_A}/alpha_payload/)" in fam_hub
    assert "Authored prose." in fam_hub.rsplit(sp.BEGIN, 1)[0]
    producer = (root / "docs" / "architecture" / "producer-row-schemas" / "README.md").read_text(
        encoding="utf-8"
    )
    assert (
        "- [Schema: alpha.done v1 (producer row)]"
        "(/architecture/producer-row-schemas/alpha.done.v1/)" in producer
    )


# --- the drift-check negatives (SC-005, US4) -----------------------------------------


def test_a_schema_without_a_page_fails_naming_it(
    tmp_path: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    root = _tree(tmp_path)
    assert _write(root) == 0
    (root / "src" / "spec_kitty_events" / "schemas" / "gamma_payload.schema.json").write_text(
        _schema("Typed payload for Gamma events."), encoding="utf-8"
    )
    page_map = json.loads((root / "scripts" / "schema_pages_map.json").read_text(encoding="utf-8"))
    page_map["families"][_FAM_A]["schemas"].append("gamma_payload.schema.json")
    (root / "scripts" / "schema_pages_map.json").write_text(
        json.dumps(page_map, indent=2) + "\n", encoding="utf-8"
    )
    assert _check(root) == 1
    assert f"docs/events/{_FAM_A}/gamma_payload.md (missing page" in capsys.readouterr().err


def test_an_orphan_page_fails_naming_it(tmp_path: Path, capsys: pytest.CaptureFixture[str]) -> None:
    root = _tree(tmp_path)
    assert _write(root) == 0
    (root / "src" / "spec_kitty_events" / "schemas" / "shared_thing.schema.json").unlink()
    page_map = json.loads((root / "scripts" / "schema_pages_map.json").read_text(encoding="utf-8"))
    page_map["families"]["shared"]["schemas"].remove("shared_thing.schema.json")
    (root / "scripts" / "schema_pages_map.json").write_text(
        json.dumps(page_map, indent=2) + "\n", encoding="utf-8"
    )
    assert _check(root) == 1
    assert "docs/events/shared/shared_thing.md (orphan page" in capsys.readouterr().err


def test_an_unmapped_payload_fails_naming_it(
    tmp_path: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    root = _tree(tmp_path)
    (root / "src" / "spec_kitty_events" / "schemas" / "gamma_payload.schema.json").write_text(
        _schema("Typed payload for Gamma events."), encoding="utf-8"
    )
    assert _check(root) == 1
    err = capsys.readouterr().err
    assert "gamma_payload.schema.json" in err
    assert "payload schema not in the family map" in err


def test_a_map_entry_naming_a_missing_file_fails(
    tmp_path: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    root = _tree(tmp_path)
    page_map = json.loads((root / "scripts" / "schema_pages_map.json").read_text(encoding="utf-8"))
    page_map["families"][_FAM_A]["schemas"].append("nonexistent_payload.schema.json")
    (root / "scripts" / "schema_pages_map.json").write_text(
        json.dumps(page_map, indent=2) + "\n", encoding="utf-8"
    )
    assert _check(root) == 1
    err = capsys.readouterr().err
    assert "nonexistent_payload.schema.json (map entry names a file that does not exist)" in err


def test_composed_registry_fixtures_and_pycache_never_get_pages(tmp_path: Path) -> None:
    root = _tree(tmp_path)
    assert _write(root) == 0
    pages = _generated_markdown(root)
    assert set(pages) == EXPECTED_PAGES | {f"docs/events/{fam}.md" for fam in sp.FAMILY_IDS} | {
        "docs/events/shared/README.md",
        "docs/architecture/producer-row-schemas/README.md",
        "docs/api/envelope/README.md",
    }
    assert all("composed" not in rel and "fixtures" not in rel for rel in pages)


# --- envelope labelling (FR-006) -----------------------------------------------------

_NORMATIVE_ROUTE = "/api/envelope/event-envelope/"
_WRITE_ROUTE = "/architecture/producer-row-schemas/producer-envelope-write/"
_READ_ROUTE = "/architecture/producer-row-schemas/producer-envelope-read/"


def _three_envelope_pages(root: Path) -> tuple[str, str, str]:
    normative = (root / "docs/api/envelope/event-envelope.md").read_text(encoding="utf-8")
    write_p = (
        root / "docs/architecture/producer-row-schemas/producer-envelope-write.md"
    ).read_text(encoding="utf-8")
    read_p = (root / "docs/architecture/producer-row-schemas/producer-envelope-read.md").read_text(
        encoding="utf-8"
    )
    return normative, write_p, read_p


def test_envelope_pages_carry_the_pinned_labels_write_read_distinguished(
    tmp_path: Path,
) -> None:
    root = _tree(tmp_path)
    assert _write(root) == 0
    normative, write_p, read_p = _three_envelope_pages(root)
    assert sp.NORMATIVE_LABEL in normative
    assert sp.PROFILE_LABEL in write_p and "WRITE profile" in write_p
    assert sp.PROFILE_LABEL in read_p and "READ profile" in read_p


def test_envelope_label_is_the_first_body_line_and_all_three_cross_link(
    tmp_path: Path,
) -> None:
    root = _tree(tmp_path)
    assert _write(root) == 0
    normative, write_p, read_p = _three_envelope_pages(root)

    def first_paragraph(text: str) -> str:
        body = text.split("---", 2)[2]
        return next(line for line in body.splitlines() if line and not line.startswith("#"))

    assert first_paragraph(normative).startswith(sp.NORMATIVE_LABEL)
    assert first_paragraph(write_p).startswith(sp.PROFILE_LABEL)
    assert first_paragraph(read_p).startswith(sp.PROFILE_LABEL)
    # All three cross-link: each page links the other two routes.
    own = {
        id(normative): _NORMATIVE_ROUTE,
        id(write_p): _WRITE_ROUTE,
        id(read_p): _READ_ROUTE,
    }
    for page in (normative, write_p, read_p):
        for route in {_NORMATIVE_ROUTE, _WRITE_ROUTE, _READ_ROUTE} - {own[id(page)]}:
            assert route in page, route


# --- Uses: cross-file $refs engage the rule; the ref-free corpus emits none -----------


def test_a_cross_file_ref_engages_uses_and_removes_its_hub_omission_stamp(
    tmp_path: Path,
) -> None:
    root = _tree(tmp_path)
    alpha = root / "src" / "spec_kitty_events" / "schemas" / "alpha_payload.schema.json"
    doc = json.loads(alpha.read_text(encoding="utf-8"))
    doc["properties"]["thing"] = {"$ref": "shared_thing.schema.json"}  # type: ignore[index]
    alpha.write_text(json.dumps(doc, indent=1) + "\n", encoding="utf-8")
    assert _write(root) == 0
    page = (root / f"docs/events/{_FAM_A}/alpha_payload.md").read_text(encoding="utf-8")
    assert "## Uses" in page
    assert "[Schema: shared_thing](/events/shared/shared_thing/)" in page
    # Squad S2 vacuity guard: with exactly one ref, exactly one page gains Uses; all
    # other schematic pages (the ref-free corpus) carry no Uses section.
    all_pages = _generated_markdown(root)
    schematic = {rel: text for rel, text in all_pages.items() if "\nkind: Schematic\n" in text}
    assert set(schematic) == EXPECTED_PAGES
    with_uses = {rel for rel, text in schematic.items() if "## Uses" in text}
    assert with_uses == {f"docs/events/{_FAM_A}/alpha_payload.md"}
    # And once the ref goes, so does the section.
    alpha.write_text(_schema("Typed payload for Alpha events."), encoding="utf-8")
    assert _write(root) == 0
    assert "## Uses" not in (root / f"docs/events/{_FAM_A}/alpha_payload.md").read_text(
        encoding="utf-8"
    )


def test_a_cross_file_ref_to_an_unpaged_schema_fails_naming_it(
    tmp_path: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    root = _tree(tmp_path)
    alpha = root / "src" / "spec_kitty_events" / "schemas" / "alpha_payload.schema.json"
    doc = json.loads(alpha.read_text(encoding="utf-8"))
    doc["properties"]["thing"] = {"$ref": "nowhere.schema.json"}  # type: ignore[index]
    alpha.write_text(json.dumps(doc, indent=1) + "\n", encoding="utf-8")
    assert _check(root) == 1
    assert "cross-file $ref 'nowhere.schema.json'" in capsys.readouterr().err


# --- edge cases ---------------------------------------------------------------------


def test_a_route_collision_fails_naming_both_pages(
    tmp_path: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    root = _tree(tmp_path)
    data_dir = root / "src" / "spec_kitty_events" / "producer_contract" / "data"
    (data_dir / "producer-envelope-write.schema.json").write_text(
        _schema("Collision bait."), encoding="utf-8"
    )
    assert _check(root) == 1
    err = capsys.readouterr().err
    assert "route collision" in err
    assert "data/producer-envelope-write.schema.json" in err
    assert "producer_contract/envelope.schema.json" in err


def test_a_hub_without_markers_fails_as_hub_link_missing(
    tmp_path: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    root = _tree(tmp_path)
    readme = root / "docs" / "api" / "envelope" / "README.md"
    readme.write_text(
        readme.read_text(encoding="utf-8").replace(sp.BEGIN, "").replace(sp.END, ""),
        encoding="utf-8",
    )
    assert _write(root) == 1
    assert "docs/api/envelope/README.md (hub link missing" in capsys.readouterr().err


# --- updated: stability (content-hash rule) ------------------------------------------


def test_updated_stays_stable_when_the_content_is_unchanged(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    root = _tree(tmp_path)
    _freeze_run_date(monkeypatch, "2026-08-01")
    assert _write(root) == 0
    page = root / f"docs/events/{_FAM_A}/alpha_payload.md"
    before = page.read_text(encoding="utf-8")
    assert "\nupdated: 2026-08-01T00:00:00Z\n" in before

    # A content change flips updated to the new run date.
    _freeze_run_date(monkeypatch, "2026-09-02")
    alpha = root / "src" / "spec_kitty_events" / "schemas" / "alpha_payload.schema.json"
    alpha.write_text(_schema("Typed payload for Alpha events, improved."), encoding="utf-8")
    assert _write(root) == 0
    after = page.read_text(encoding="utf-8")
    assert "\nupdated: 2026-09-02T00:00:00Z\n" in after
    assert "improved" in after

    # An unchanged tree at a LATER run date keeps the stable date; --check stays clean.
    _freeze_run_date(monkeypatch, "2026-12-31")
    assert _write(root) == 0
    assert page.read_text(encoding="utf-8") == after
    assert _check(root) == 0
