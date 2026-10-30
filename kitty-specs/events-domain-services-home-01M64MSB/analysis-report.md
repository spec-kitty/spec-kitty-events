---
schema_version: 1
artifact_type: spec-kitty.analysis-report
command: /spec-kitty.analyze
mission_slug: events-domain-services-home-01M64MSB
mission_id: 01M64MSBA3GZ253WAPX3FE9AQX
generated_at: '2026-10-29T22:38:46.086247+00:00'
analyzer_agent: unknown
input_artifacts:
  spec.md:
    path: kitty-specs/events-domain-services-home-01M64MSB/spec.md
    sha256: e66e925c3c7e62dfe297428115cd26643bde3f1e66312a3a92131aa531ac6cc3
  plan.md:
    path: kitty-specs/events-domain-services-home-01M64MSB/plan.md
    sha256: 372551574b43ee3df58eaaaa577eb1e6ae18d07a971385af3dbf4cd586ed6574
  tasks.md:
    path: kitty-specs/events-domain-services-home-01M64MSB/tasks.md
    sha256: 6630806a8e15daa73edf9638deaf1850b87299b071be8ed49292bee022065ca4
  charter:
    path: .kittify/charter/charter.yaml
    sha256: 600f73eed353417fafe3cbe3fde6e10e2d5451eec6edb4afccd78f488c5ed6ee
verdict: ready
issue_counts:
  medium: 2
  high: 0
  low: 3
  critical: 0
  info: 0
findings:
- id: C1
  severity: low
  category: charter
  summary: 'Re-analysis after WP02: the charter now carries the scoped test-only core-pin exception; only WP02 approval before merge is still required.'
- id: F1
  severity: medium
  category: inconsistency
  summary: WP04's T019 test reads the asset list from the kitty-specs mission contract; after the mission the durable source is ADR 0003.
- id: E1
  severity: medium
  category: coverage
  summary: FR-007's 'moved core material' (event-ledger doc) is only fully delivered at cutover (WP07 T033), yet FR-007 maps to the pre-cutover WP05/WP06.
- id: E2
  severity: low
  category: coverage
  summary: The plan's performance goal (Gradle ≤ Maven + 20%) has no NFR in spec; T009/T014 record wall time but nothing asserts it.
- id: B1
  severity: low
  category: ambiguity
  summary: The spec's User Story 3 says the workflows are 'inert until service/ exists'; the release gate fails loudly instead of no-op (intended, but worded differently).
---

## Specification Analysis Report

Re-run 2026-10-29 after the WP02 charter amendment and the pre-PR squad folds (plan.md, research.md) changed the inputs; the squad dispositions are in research.md. The charter now holds the Package Boundary test-only exception (core-pin), which is consistent with plan.md's Charter Check. No new findings.

| ID | Category | Severity | Location(s) | Summary | Recommendation |
|----|----------|----------|-------------|---------|----------------|
| C1 | Charter | LOW | plan.md Charter Check; tasks WP02, WP04 | The core-pin CI checkout (D5) conflicts with Package Boundary until WP02's exception lands. WP04 writes the guarded jobs before that. | Same PR, single lane: review WP02 before WP04 merges. The jobs only activate at cutover (WP07, after WP02). No task change needed; the reviewer of WP04 checks that WP02 is approved. |
| F1 | Inconsistency | MEDIUM | WP04 T019 | The test couples to `kitty-specs/.../contracts/service-release-assets.md`. | Implementer: make ADR 0003's asset table (WP01) the parse source, or embed the list in the script and cross-check against the ADR. |
| E1 | Coverage | MEDIUM | spec FR-007; WP05, WP07 T033 | Pre-cutover docs summarise and link the core doc; the history-preserving move happens at cutover. | Accept as phased; WP05 pages state that the code and doc move at cutover. |
| E2 | Coverage | LOW | plan.md Technical Context | Performance goal not asserted. | Record the timings in WP03's hand-off; no gate. |
| B1 | Ambiguity | LOW | spec User Story 3; WP04 T018 | "Inert" vs a fail-closed release gate. | Keep fail-closed (a release must not silently no-op). |

**Coverage Summary Table:**

| Requirement | Has Task? | Task IDs | Notes |
|---|---|---|---|
| FR-001 | yes | T001, T004 | |
| FR-002 | yes | T002 | |
| FR-003 | yes | T003 | |
| FR-004 | yes | T005–T008 | |
| FR-005 | yes | T009–T014 | |
| FR-006 | yes | T020 | |
| FR-007 | yes | T021–T027, T033 | phased (E1) |
| FR-008 | yes | T015–T019 | |
| FR-009 | yes | T028 | |
| FR-010 | yes | T030–T034 | cutover-gated |
| FR-011 | yes | T035–T037 | cutover-gated |
| NFR-001 | yes | T014, T034 | |
| NFR-002 | yes | T009, T014 | |
| NFR-003 | yes | T013, T019 | |
| NFR-004 | yes | T004, T025, T029 | |

**Charter Alignment Issues:** C1 is sequencing only; the amendment is part of this mission (WP02).

**Unmapped Tasks:** none.

**Metrics:** 15 requirements (11 FR, 4 NFR) plus 5 constraints; 37 tasks; coverage 100%; ambiguity 1; duplication 0; critical 0.

### Next Actions
No high or critical findings. Proceed to implementation. F1 is folded into WP04's implementation as reviewer guidance, and C1 is enforced by reviewing WP02 before WP04.
