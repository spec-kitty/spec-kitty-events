---
work_package_id: WP04
title: Java 25 binding
dependencies:
- WP03
requirement_refs:
- FR-013
- FR-014
- C-006
- SC-002
planning_base_branch: issue-92-cloudevents-envelope
merge_target_branch: issue-92-cloudevents-envelope
branch_strategy: Planning artifacts for this mission were generated on issue-92-cloudevents-envelope. During /spec-kitty.implement this WP may branch from a dependency-specific base, but completed changes must merge back into issue-92-cloudevents-envelope unless the human explicitly redirects the landing branch.
subtasks:
- T019
- T020
- T021
- T022
- T023
history:
- timestamp: '2026-10-27T18:00:00Z'
  lane: planned
  agent: system
  action: Prompt generated via /spec-kitty.tasks
agent_profile: java-jenny
authoritative_surface: java/
create_intent:
- java/pom.xml
- scripts/ensure_jdk25.sh
execution_mode: code_change
owned_files:
- java/**
- scripts/ensure_jdk25.sh
- Makefile
- .gitignore
role: implementer
tags: []
tracker_refs: []
---
# Work Package Prompt: WP04 – Java 25 binding

## ⚡ Do This First: Load Agent Profile

Load your assigned profile before reading anything else: `/spk-charter-profile-load java-jenny`
(or `spec-kitty agent profile show java-jenny`), and adopt its identity, scope and boundaries.

## Branch Strategy

Planning base and merge target: `issue-92-cloudevents-envelope` (single_branch topology). The
execution workspace comes from `lanes.json`. Implement with
`spec-kitty agent action implement WP04 --agent claude --mission cloudevents-envelope-registry-upcaster-01M5Z6VM`.

## Context

Spec: `../spec.md`; plan: `../plan.md`; research: `../research.md`; contract:
`../contracts/upcaster-contract.md`; ADR: `docs/adr/0001-cloudevents-envelope.md`; design:
`docs/architecture/cloudevents-envelope.md` sections 3–9.

## Objective

A Maven module (`java/`, Java 25) that loads `src/spec_kitty_events/producer_contract/` in place
and passes the same corpus byte for byte.

## Subtasks

### T019 JDK and wrapper
`scripts/ensure_jdk25.sh` per research R-7 (prints a JAVA_HOME; downloads Temurin 25 to
`.cache/jdk-25` with SHA-256 check when no JDK ≥ 25 is found). Maven Wrapper (`mvnw`,
`.mvn/wrapper/maven-wrapper.properties` with `distributionSha256Sum`).

### T020 Skeleton
`ai.spec-kitty:spec-kitty-events-java`, package `ai.speckitty.events.cloudevents`,
`maven.compiler.release=25`, deps per research R-6. Resources: the producer contract directory
as a resource root (`../src/spec_kitty_events/producer_contract`, targetPath
`spec_kitty_events/producer_contract`); tests read the corpus from the same root.

### T021 Canonical writer
Own writer over Jackson `JsonNode`: keys sorted by code point, no whitespace, strings escaped
like Python `json.dumps(ensure_ascii=False)` (escape `"`, `\\`, control chars as `\n`, `\r`, `\t`, `\b`, `\f`
or `\u00XX`), integers as written (BigInteger), floats formatted like Python `repr` (shortest
round-trip; exponent when exp < -4 or ≥ 16; `1e-05` style).

### T022 Upcaster and validator
Port of `contracts/upcaster-contract.md` driven by `registry.json`; `Upcaster.upcast(JsonNode,
Context, Mode)` → `UpcastResult`; `Validator.validate(JsonNode, Profile)` with networknt 2020-12
over the composed row schemas.

### T023 Runner and make target
JUnit dynamic tests over every corpus case (outcome, form, codes, golden bytes, validity,
idempotence, strict). `make test-java` (`JAVA_HOME=$(scripts/ensure_jdk25.sh) java/mvnw -B -f
java/pom.xml --strict-checksums verify`) and `test-full: test-floor lint test-java`.

## Definition of Done
`make test-java` green locally; corpus count equals Python's.

## Reviewer Guidance

Check the change against the spec rows mapped to this WP; reject any mapping that invents a
value, any schema authored twice, and any change to `validate_strict_envelope`, producer
emission or the diary reducer.
