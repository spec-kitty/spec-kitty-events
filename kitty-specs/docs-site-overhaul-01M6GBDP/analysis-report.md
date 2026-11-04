---
schema_version: 1
artifact_type: spec-kitty.analysis-report
command: /spec-kitty.analyze
mission_slug: docs-site-overhaul-01M6GBDP
mission_id: 01M6GBDPFCFK09N2AVSD2VWTW3
generated_at: '2026-11-03T10:38:44.503299+00:00'
analyzer_agent: unknown
input_artifacts:
  spec.md:
    path: kitty-specs/docs-site-overhaul-01M6GBDP/spec.md
    sha256: 29a46e17bbf730894ce17c426a933e70b9a6456fbb6093aea41bec0d77d7fdf5
  plan.md:
    path: kitty-specs/docs-site-overhaul-01M6GBDP/plan.md
    sha256: 17b73e706ff3f59ee7803cff3cbd1c42a4dbf177d2c69fe42bae544dff7bd652
  tasks.md:
    path: kitty-specs/docs-site-overhaul-01M6GBDP/tasks.md
    sha256: 93dc0b342f56cfc1f76cbfb38d99ec266e8dabef6f776a84cc114920b7240379
  charter:
    path: .kittify/charter/charter.yaml
    sha256: 600f73eed353417fafe3cbe3fde6e10e2d5451eec6edb4afccd78f488c5ed6ee
verdict: ready
issue_counts:
  critical: 0
  medium: 0
  low: 3
  high: 0
  info: 0
findings:
- id: C1
  severity: low
  category: underspecification
  summary: "spec US1 acceptance scenario 2 (follow a cross-file sub-model link) has no instance in today's corpus (zero cross-file $refs, grep-verified); adjudication recorded as squad finding S2: WP04's generator emits '## Uses' conditionally and the shared-types hub carries discovery until the first cross-file ref lands."
- id: C2
  severity: low
  category: underspecification
  summary: Producer-row dotted page stems ('mission.created.v1') have an unverified Astro/doc-kitty route shape; WP04 pins this as a T019 exit criterion before mass generation, with a single normalisation fallback.
- id: U1
  severity: low
  category: coverage
  summary: 'WP prompts cite #181/#193/#194/#195 bare; issue-matrix rows will be required at WP approval time (non-gating here, per #3469 heads-up).'
---

## Specification Analysis Report

Mission: docs-site-overhaul-01M6GBDP (one branch `issue-122-docs-site-overhaul`; one PR closing #181 #193 #194 #195).
Inputs analysed: spec.md (FR-001..021, NFR-001..005, C-001..007, SC-001..006), plan.md, tasks.md, the 7 WP prompts,
research.md (25 pre-spec findings + 20 post-tasks squad dispositions), charter check in plan.md.

| ID | Category | Severity | Location(s) | Summary | Recommendation |
|----|----------|----------|-------------|---------|----------------|
| C1 | Underspecification | LOW | spec.md US1 AS2; plan.md A2; research.md S2 | Cross-file sub-model links conditional (corpus carries zero cross-file `$ref`s) | Accepted adjudication; `## Uses` emitted conditionally; shared hub covers discovery; re-check at WP04. |
| C2 | Underspecification | LOW | WP04 T019 | Dotted-stem route shape unverified | Spike is a T019 exit criterion before the 168-page run. |
| U1 | Coverage (process) | INFO | tasks/WP0*.md bodies | Bare issue numbers in prompts | Register issue-matrix rows per WP before approvals. |

**Coverage Summary Table**

| Requirement | Has WP? | WP(s) | Notes |
|---|---|---|---|
| FR-001/002/020(frag) | yes | WP01 | |
| FR-003/004/011, FR-020(mode/paths), NFR-001/002/005 | yes | WP02 | probe constants pinned ≤120 s (S6) |
| FR-009/010, SC-002(part) | yes | WP03 | bundle via out-of-repo recipe (S1) |
| FR-005..008, NFR-003, SC-001/005 | yes | WP04 | error-SVG gate hardened (S4) |
| FR-012/013, SC-002(part)/SC-003(part) | yes | WP05 | |
| FR-014..019, SC-003 | yes | WP04/WP05/WP06 | 3-way split of FR-014 recorded in plan + tasks |
| FR-021, all NFR/SC sweep | yes | WP07 | NFR timing evidence merge-blocking (S3) |
| C-001..007 | respected in constraints | all | verified against WP prompts |

**Charter Alignment Issues:** none. Quality gates appear in every WP verification; SHA-pinned actions and
digest-pinned images enforced by new tests; no contract/schema content changes (C-004); ADR discipline
(C-007) honoured (new ADR-0006 + dated 0005 amendment).

**Unmapped Tasks:** none (finalize validation passed clean; every FR has ≥1 WP).

**Metrics:**
- Total Requirements (FR+NFR+SC, tracked): 21 FR + 5 NFR + 6 SC = 32
- Total WPs: 7 (38 subtasks)
- Coverage: 100% of FRs mapped; every NFR/SC named in ≥1 WP
- Ambiguities: 2 (both LOW, adjudicated/exit-criterial)
- Duplications: 0
- Critical: 0

**Next actions:** none blocking. Proceed to the implement-review loop for WP01. Before WP-level approvals,
register issue-matrix rows for #181/#193/#194/#195 against their owning WPs.
