---
schema_version: 1
artifact_type: spec-kitty.analysis-report
command: /spec-kitty.analyze
mission_slug: events-write-core-01M6TP6W
mission_id: 01M6TP6WAF3H5PGQMQT9HCPNQ0
generated_at: '2026-11-07T11:58:09.063436+00:00'
analyzer_agent: unknown
input_artifacts:
  spec.md:
    path: kitty-specs/events-write-core-01M6TP6W/spec.md
    sha256: 04f43f785cadab47efc8408de6220aa0a326308678be2e64e98a54f1fa98cad1
  plan.md:
    path: kitty-specs/events-write-core-01M6TP6W/plan.md
    sha256: 7a2103d6b73a4557c9a1ce9245d7a96b97c63ae4f897fe654c07956c63a57faa
  tasks.md:
    path: kitty-specs/events-write-core-01M6TP6W/tasks.md
    sha256: eac00f152b3686bef8db3d9858cba5405d9061c6e5d41122ca04c92be6b4d227
  charter:
    path: .kittify/charter/charter.yaml
    sha256: 600f73eed353417fafe3cbe3fde6e10e2d5451eec6edb4afccd78f488c5ed6ee
verdict: ready
issue_counts:
  high: 0
  medium: 4
  critical: 0
  low: 5
  info: 0
findings:
- id: A1
  severity: medium
  category: inconsistency
  summary: "plan.md still carries pre-tasks-squad statements that tasks.md rulings R1/R2/R10 reversed: corpus path under fixtures/, write-core-parity job owned by WP04, FFM metadata in event-log owned by WP02, 'about 9 work packages', WP03->WP07 instead of WP03->WP06, no WP10 row in the Concern Map."
- id: A2
  severity: medium
  category: inconsistency
  summary: spec.md FR-010 allowlists only command-cli's state-directory adapter, but research.md section 11 decision 16 (and the WP02/WP08 rule) allowlist three packages (event-log adapter.out.ledger, adapter.out.statuslog, command-cli adapter.out.filesystem).
- id: A3
  severity: medium
  category: coverage
  summary: WP07 T042 creates Authority.java and AuthorityInput.java at domain/authority/ top level, which WP06 owns via domain/authority/*.java; WP07 owned_files only list the dependency/, claim/ and batch/ sub-packages and the coordination table declares no such edit.
- id: A4
  severity: medium
  category: coverage
  summary: No WP owns src/spec_kitty_events/conformance/README.md (lists the fixture families and packaged runners, e.g. ledger_append) or a docs page for the new public spec_kitty_events.status_log module; charter requires docs kept in sync with the two new fixture families.
- id: A5
  severity: low
  category: coverage
  summary: WP09 T055 edits contracts/mission-write/CHANGELOG.md but the file is absent from WP09 owned_files (declared only as an out-of-map edit in tasks.md).
- id: A6
  severity: low
  category: inconsistency
  summary: WP02 requirement_refs still lists FR-006 and plan Concern Map assigns native metadata to WP02, while R10 moved the FFM metadata and native smoke to WP08.
- id: A7
  severity: low
  category: ambiguity
  summary: NFR-004 requires 'JaCoCo class floors met for every new class' without stating the floor values or where they live; testability depends on the per-module jacoco-class-floor.properties.
- id: A8
  severity: low
  category: inconsistency
  summary: "Terminology drift: data-model.md puts operation on UnitRequest and names PersistenceFailed, plan.md WriteRequest carries a single 'operation', tasks use PersistenceFailedException; WriteRefusalReason (contract) vs RefusalReason (Java)."
- id: A9
  severity: low
  category: ambiguity
  summary: NFR-001 p95 <= 1,000 ms 'on CI runners' does not name the runner class or the gate's variance handling, so a flaky or vacuous gate is possible.
---

## Specification Analysis Report

| ID | Category | Severity | Location(s) | Summary | Recommendation |
|----|----------|----------|-------------|---------|----------------|
| A1 | Inconsistency | medium | plan.md Project Structure, Concern Map, Dependency Graph, Coordination Points, Scale/Scope vs tasks.md R1/R2/R10 | Plan is stale against folded rulings (corpus under `fixtures/`, WP04 owning the CI job, event-log native metadata, 9 WPs, WP03->WP07, no WP10) | Align plan.md with tasks.md (or state tasks.md supersedes) before implement |
| A2 | Inconsistency | medium | spec.md FR-010; research.md s11 D16; WP02, WP08 | FR-010 allowlist is one adapter in the spec, three packages in research and WPs | Amend FR-010 wording to the three-package list |
| A3 | Coverage | medium | WP06 owned_files; WP07 T042 and owned_files | `Authority.java`/`AuthorityInput.java` sit in WP06's glob, outside WP07's ownership | Add the two files to WP07 owned_files and the coordination table, or place them in `batch/` |
| A4 | Coverage | medium | conformance/README.md; WP01, WP09 owned_files; charter Documentation | Conformance README and status_log public-API docs have no owner | Give WP01 (README) and WP09 (docs reference) the files |
| A5 | Coverage | low | WP09 T055 | CHANGELOG pass not in owned_files | List it in WP09 owned_files |
| A6 | Inconsistency | low | WP02 requirement_refs; plan Concern Map | FR-006 attributed to WP02 after R10 | Drop FR-006 from WP02 refs, fix plan row |
| A7 | Ambiguity | low | spec NFR-004 | "class floors" unspecified | Cite the properties file and values |
| A8 | Inconsistency | low | data-model.md, plan.md, WP02, WP03 | Naming drift for operation placement and exception types | Normalize names in data-model.md |
| A9 | Ambiguity | low | spec NFR-001 | Runner class and variance handling unnamed | Name the runner label and use best-of-N or documented tolerance |

No duplication findings of substance (FR-007/FR-028 overlap is deliberate: refusal vs rollback).

## Coverage Summary

| Requirement group | Covered by | Gaps |
|---|---|---|
| FR-001..003 | WP01, WP05, WP10 | none |
| FR-004..007 | WP02 (FR-006 smoke in WP08) | A6 |
| FR-008, 009, 017..020, 026 | WP08 | none |
| FR-010 | WP02, WP08 | A2 |
| FR-011..016, 022..025, 027, 028 | WP03, WP04, WP06, WP07 | A3 |
| FR-021 | WP09 | A4, A5 |
| NFR-001..006 | WP08, WP04, WP01, WP01/02/05/06/07/08, WP08/WP02/WP04/WP10, WP02 | A7, A9 |
| SC-001..006 | WP01/05, WP04/06/07, WP08, WP02, WP02, WP08/WP10 | none |
| C-001..C-010 | all mapped (C-001 WP01/02/04/10, C-002 WP03/08, C-003 WP04/06/07, C-004 WP07, C-005 WP08/09, C-006 WP02, C-007 WP09, C-008 WP03, C-009 WP02/06/08, C-010 WP06/08) | none |

Every FR, NFR, SC and C maps to at least one WP; every task T001..T058 maps to a WP with requirement refs.

## Charter Alignment Issues

None blocking. mypy --strict, packaged conformance gate, both-binding fixtures (Python and Java runners), ArchUnit (new rules with planted violations), strict dependency verification (no new artifact), SHA-pinned actions, no core dependency (test-only pin) are all addressed. Only the docs-in-sync item (A4) is open.

## Unmapped Tasks

None.

## Metrics

Requirements: 28 FR, 6 NFR, 6 SC, 10 C. WPs: 10. Tasks: 58. Coverage of requirements by WPs: 100%. Critical 0, High 0, Medium 4, Low 5.

## Next Actions

No blockers. Optionally fix A1 to A4 (artifact edits only) before /spec-kitty.implement; A5 to A9 can be handled in the implementing WPs.
