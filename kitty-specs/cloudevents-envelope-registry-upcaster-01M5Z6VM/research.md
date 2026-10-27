# Research: CloudEvents envelope, registry and upcaster

Decisions taken by the maintainer are in ADR 0001 (2026-10-27) and in this mission's Decision
Moments; this file records the engineering decisions of the plan.

## R-1 Shape detection

- **Decision**: detect in a fixed order: not an object → pass-through; `specversion` present →
  already a CloudEvent (pass-through); `event_type`, `aggregate_id`, `payload`, `node_id`,
  `build_id` present → shape A; `event_type` + `payload` with `row_version` / `aggregate_type`
  / `at` → journal / lifecycle / decision envelope; `kind: annotation` → annotation; `type`
  equal to a registered flat constant → retrospective; `to_lane` + `wp_id` without
  `event_type` → lane row; otherwise unrecognised (shape C/D).
- **Rationale**: the member sets are disjoint on the corpus (verified: lifecycle rows carry
  `aggregate_type` and no `node_id`; shape A carries `node_id` and `build_id`), and detection
  needs no schema validator, so the upcaster has no new dependency.
- **Alternatives**: validating against each legacy-form schema (needs jsonschema at runtime;
  rejected per NFR-005).

## R-2 Shape A beyond `3.0.0`

- **Decision**: only `schema_version == "3.0.0"` maps. Other versions (the corpus has
  `2.9.0`) are pre-3.0 shape C: pass-through permissively, `UNSUPPORTED_SCHEMA_VERSION` strictly.
- **Rationale**: design section 1 (shape C recognised, not mapped).

## R-3 Mission id precedence

- **Decision**: candidates are the row (aggregate ULID segment, lifted payload or row
  `mission_id`) and the caller context. All present values must agree after id normalization;
  a disagreement is `MISSION_ID_CONFLICT`. Row values win only in the sense that context fills a
  gap; nothing overrides anything. A non-ULID aggregate segment is never a mission id.
- **Rationale**: design section 3 "Aggregate split" leaves precedence to this mission with the
  rule that nothing is silently overridden.

## R-4 Canonical serialization

- **Decision**: UTF-8 JSON, object keys sorted by code point, separators `,` and `:`, no
  whitespace, non-ASCII emitted as is, `/` not escaped, integers as written, floats in the
  shortest round-trip form with Python `repr` exponent rules; a trailing `\n` is not part of
  the canonical bytes. Upcast output never reorders arrays.
- **Rationale**: the goldens must be reproducible in Java (Jackson with
  `ORDER_MAP_ENTRIES_BY_KEYS` and a float writer matching Python `repr`). Keys outside the BMP
  would sort differently by UTF-16 unit; no registered member name is outside ASCII, and data
  keys are model-defined ASCII names.

## R-5 Byte stability and the original line

- **Decision**: `upcast_line` keeps the input bytes on the result (`original_line`); a writer
  that re-emits a legacy row writes those bytes. Goldens are the canonical bytes of the upcast
  result; idempotence: upcasting the parsed golden yields `passthrough/cloudevent` and the
  same canonical bytes.
- **Rationale**: ADR "no log rewrite"; the merge-driver proposal writes original bytes.

## R-6 Java dependencies and supply chain (DIRECTIVE_051)

- **Decision**: `com.fasterxml.jackson.core:jackson-databind:2.22.4`,
  `com.networknt:json-schema-validator:3.0.8`, `org.junit.jupiter:junit-jupiter:6.1.3`
  (test). Maven Central only; exact versions pinned; Maven verifies SHA-1 checksums with
  `--strict-checksums`; the Maven Wrapper pins the distribution version and its SHA-256.
  No dependency is shaded or published.
- **Rationale**: Jackson is the JVM JSON default (core's acceptance JVM uses it); networknt
  supports 2020-12 including `format` assertions off by default, matching the Python setup.
- **Alternatives**: everit-json-schema (no 2020-12); Gson (no ordered map writer we need).
- **Adversarial evidence**: covered by the pre-PR adversarial squad (supply-chain lens).

## R-7 JDK 25 in CI

- **Decision**: `scripts/ensure_jdk25.sh` uses `$JAVA_HOME` or `java` on `PATH` when it is
  version 25+, else downloads Temurin 25 from the Adoptium API into `.cache/jdk-25` (checksum
  from the API's `.sha256.txt`) and prints its home. `make test-java` runs `./mvnw -B
  --strict-checksums verify` with that home; `make test-full` depends on it.
- **Rationale**: the CI suite (planning `bin/ci-run.sh`) calls the Makefile; runner images
  are not under this repo's control.

## R-8 New registry entries (transport-only)

| Strict `event_type` | Type | Scope / subject |
|---|---|---|
| `MissionStarted` | `ai.spec-kitty.mission.started.v1` | mission / none |
| `MissionCompleted` | `ai.spec-kitty.mission.completed.v1` | mission / none |
| `MissionCancelled` | `ai.spec-kitty.mission.cancelled.v1` | mission / none |
| `MissionClosed` | `ai.spec-kitty.mission.closed.v1` | mission / none |
| `PhaseEntered` | `ai.spec-kitty.mission.phase.entered.v1` | mission / none |
| `ReviewRollback` | `ai.spec-kitty.wp.review.rolledback.v1` | mission / wp_id |
| `HarnessObservation` | `ai.spec-kitty.harness.observed.v1` | from the aggregate |
| `WorkObservation` | `ai.spec-kitty.work.observed.v1` | from the aggregate |

Their `legacy` is `null` (no CLI legacy form); their data schemas are authored from the
payload models' generated schemas with `$ref` inlined, then pinned to the models by tests,
as for the existing 19.
