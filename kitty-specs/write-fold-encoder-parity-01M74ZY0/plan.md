# Implementation Plan: Write-fold and reference-encoder parity (M2)

**Branch**: `issue-237-write-fold-encoder-parity` | **Date**: 2026-11-11 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `kitty-specs/write-fold-encoder-parity-01M74ZY0/spec.md`

Branch contract: planning branch, base branch and merge target are all `issue-237-write-fold-encoder-parity` (single-branch topology); the finished branch goes to `main` through one PR.

## Summary

Three groups of small, independent changes, each a work package, closing #237 and the rest of #231:

1. **Fold** (Java, `service/command-cli`, `service/event-log`): model an id-less transition explicitly and mirror core's "no setter" verdict (4.16, first and independently landable before core #6195); pin the non-finite id refusal (4.17); one injected clock for the write budget and the Mission lock wait (4.8).
2. **Python reference encoder and `validate_transition`** (`src/spec_kitty_events`): tuples (4.1), typed refusals (4.2), docstring (4.3), the 3.11 timestamp grammar on every interpreter (4.4), no-mutation tests (4.5), the core edge graph (4.6, D4) and the 11.5.0 bump (M2-R3).
3. **Assurance** (tests, workflows, pin): ack bytes over HTTP against core-extracted resources (4.9, M2-R2), the `UV_PYTHON` pin assertion (4.10), the process-creation ArchUnit rule (4.11), the core-pin purpose text (4.13).

## Technical Context

**Language/Version**: Python 3.10+ (package floor 3.10; CI and core use 3.11; tested on 3.10, 3.11, 3.13); Java 25 (Gradle Kotlin DSL)
**Primary Dependencies**: Pydantic (existing), hypothesis and pytest (tests); JUnit 5, AssertJ, ArchUnit, Jackson 3 (existing). No new dependencies.
**Storage**: files (`status.events.jsonl`, committed JSON test resources)
**Testing**: pytest + hypothesis; packaged conformance gate (`uv run pytest --pyargs spec_kitty_events.conformance`); `./gradlew verifyAll` (JUnit, ArchUnit, JaCoCo gates)
**Target Platform**: library on PyPI (pinned by SHA under the programme exception); the command-cli service (JVM + GraalVM native)
**Project Type**: single repository, Python library plus Gradle multi-module services
**Performance Goals**: none new; fold and encoder stay linear in log size (NFR: unchanged behaviour on normal volumes)
**Constraints**: no core dependency at build, run or test time (C-001); stay out of `service/src` (C-002); byte output of accepted rows unchanged except newly-sanitised tuples (C-006)
**Scale/Scope**: ~12 production files, ~15 test files, one new test resource, one extraction script

## Charter Check

| Charter rule | How this plan meets it |
|---|---|
| pytest, hypothesis, schema drift and conformance fixtures for contract changes | No packaged schema or fixture changes. The encoder and `validate_transition` changes get unit and property tests; the conformance gate runs unchanged. |
| `mypy --strict`, committed schema checks | All Python changes typed; no schema regeneration needed. |
| ArchUnit onion and boundary rules mandatory for services | A new boundary rule (process creation) is added to command-cli with a planted fixture. |
| Package Boundary: no import of core; test-only pinned peer | The ack-byte expectations are committed resources; the extraction tool's `--check` runs only in CI's `write-core-parity` job through the existing pin. |
| Review policy: deliberate compatibility review for envelope/payload/schema/fixture changes | `validate_transition` narrowing is consumer-visible: minor bump (D4, M2-R3), a `Changed` fragment and the docs update. Encoder refusal subclasses are additive. |
| Documentation synchronized | `docs/events/work-and-status.md`, the encoder docstring, the core-pin purpose and the corpus README are updated in the same WPs. |
| Fail-closed compatibility | The id-less change mirrors core; the non-finite id stays fail-closed and is pinned by a test. |

No violations; Complexity Tracking not needed.

## Design decisions (details in [research.md](./research.md))

- **RD-1 Id-less transition model** (M2-R1): `WriteFold.Transition` gets an explicit identity: a small sealed `TransitionId` (`Identified(Object raw, String spelling)` / `Absent`) built by factories from the row. The fold stores the setter's `TransitionId`, and the setter lookup returns empty for `Absent`. `"None"` spelling no longer stands for null anywhere in the arbitration. Dedupe keeps core's behaviour (two id-less rows are not "the same id").
- **RD-2 Differential cases**: committed JSON cases under `service/command-cli/src/test/resources/fold-differential/` (log rows + expected per-WP lane), with expectations produced by the package reducer (`spec_kitty_events.diary.reduce_parsed`) through a small generator script and a pytest that re-derives and compares them, so both bindings are held to the same file.
- **RD-3 One clock**: `StatusLogAppender` gains a `LongSupplier` nano clock (default `System::nanoTime`); command-cli wiring passes its `nanoClock` bean. `WriteTurn` already takes a relative budget. Repair path uses the same appender.
- **RD-4 Encoder**: tuples recurse like lists; `_encode` catches `ValueError` from the int-to-str limit as `IntegerTooLarge` and validates keys up-front (`NonStringKey`); `NonFiniteNumber` only from `allow_nan=False`. `parse_iso8601_datetime` always uses `_parse_isoformat_311`.
- **RD-5 Edge graph**: `_ALLOWED_TRANSITIONS` and the blocked/canceled rules become core's `allowed_targets()` graph at pin `512567ee`, checked by an exhaustive matrix test whose expected graph is written out literally from core's `wp_state.py` at the pin.
- **RD-6 Ack bytes** (M2-R2): `scripts/conformance/extract_ack_bytes.py` writes `service/command-cli/src/test/resources/conformance/ack-bytes.json` (case id → per-unit appended text). With a core checkout it serialises each accepted unit's rows through core's `serialize_event_line`; `--check` reports drift and runs in `write-core-parity`. The committed file is first produced with the package's reference encoder (core-exact for these rows by the committed `status_append` oracle), because core cannot run in this session; CI's core `--check` confirms it. `ProtocolCorpusTest` runs every ack case over HTTP (domain cases included) and compares the appended bytes.
- **RD-7 ArchUnit**: no class outside `..adapter.out.mission..` may depend on `java.lang.ProcessBuilder` or `java.lang.Process`, or call `Runtime.exec`. `ProcessHandle` (used by `EndpointPublisher` for its own pid) is not process creation and stays allowed. The planted fixture lives in a test-only package, is imported explicitly, and a test asserts the rule fails on it.

## Project Structure

### Documentation (this feature)

```
kitty-specs/write-fold-encoder-parity-01M74ZY0/
├── spec.md
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── checklists/requirements.md
└── tasks/
```

### Source Code (repository root)

```
service/command-cli/src/main/java/org/speckitty/commandcli/domain/authority/   # WriteFold, TransitionFold, EventOrdering (WP01)
service/command-cli/src/test/java/.../domain/authority/                        # fold differential + non-finite tests (WP01)
service/command-cli/src/test/resources/fold-differential/                      # WP01 cases
service/event-log/src/main/java/.../statuslog/StatusLogAppender.java           # clock (WP02)
service/command-cli/src/main/java/.../config/ServiceWiring.java                # clock wiring (WP02)
src/spec_kitty_events/status_log.py, _iso8601.py, __init__.py                  # WP03
src/spec_kitty_events/status.py, docs/events/work-and-status.md, pyproject.toml # WP04
scripts/conformance/extract_ack_bytes.py, .github/workflows/service.yml        # WP05
service/command-cli/src/test/java/.../ProtocolCorpusTest.java                  # WP05
tests/test_service_workflows.py, service/core-pin.json                          # WP06
service/command-cli/src/test/java/.../architecture/                            # WP06
docs/changelog/unreleased/237-*.md                                              # each WP its own
```

## Implementation Concern Map

| Concern | Spec | Proposed WP | Model |
|---|---|---|---|
| Id-less transition + differential cases + non-finite id test | FR-001–FR-004 | WP01 (first, landable alone) | Sonnet (subtle parity) |
| One injected clock | FR-005 | WP02 | Sonnet (concurrency) |
| Reference encoder items | FR-006–FR-010 | WP03 | Sonnet (encoding) |
| `validate_transition` edge graph, docs, 11.5.0 | FR-011–FR-013 | WP04 | Sonnet |
| Ack bytes over HTTP + extraction tool | FR-014, FR-018 | WP05 | Sonnet |
| UV_PYTHON assertion, ArchUnit rule, pin purpose, issue matrix | FR-015–FR-017, FR-019 | WP06 | Haiku (mechanical) |

## Parallel Work Analysis

### Dependency Graph

```
WP01 ─┐
WP02 ─┤
WP03 ─┼─ (all independent) ─→ review → accept → PR
WP04 ─┤
WP05 ─┤   (WP05 reads WP03's encoder only through its public API, unchanged for the corpus rows)
WP06 ─┘
```

### Work Distribution

- **Sequential work**: none required; WP01 goes first so it is reviewable on its own.
- **Parallel streams**: Java fold (WP01, WP02), Python (WP03, WP04), assurance (WP05, WP06). Single-branch topology: implementers run one at a time on the branch to avoid commit races.
- **Agent assignments**: file ownership as in the source tree above; `ServiceWiring.java` only WP02; `service.yml` only WP05; `tests/test_service_workflows.py` only WP06.

### Coordination Points

- Version bump (`pyproject.toml`, `__version__`) and `service/core-pin.json` purpose are announced to the orchestrator (siblings B and C).
- After all WPs: `make test-fast`-scoped blast radius, conformance gate, `./gradlew verifyAll`, docs validate.
