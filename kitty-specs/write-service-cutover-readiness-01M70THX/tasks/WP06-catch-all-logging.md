---
work_package_id: WP06
title: Catch-all logging (#231 4.14)
dependencies:
- WP05
requirement_refs:
- FR-016
- NFR-003
- NFR-005
- SC-004
planning_base_branch: kitty/mission-write-service-cutover-readiness-01M70THX
merge_target_branch: main
branch_strategy: Planning artifacts for this mission were generated on kitty/mission-write-service-cutover-readiness-01M70THX. During /spec-kitty.implement this WP may branch from a dependency-specific base, but completed changes must merge back into main unless the human explicitly redirects the landing branch.
subtasks:
- T029
- T030
- T031
history:
- timestamp: '2026-11-09T20:30:00Z'
  lane: planned
  agent: system
  action: Prompt generated via /spec-kitty.tasks
agent_profile: java-jenny
authoritative_surface: service/command-cli/src/main/java/org/speckitty/commandcli/application/diagnostics/
create_intent:
- service/command-cli/src/main/java/org/speckitty/commandcli/application/diagnostics/FailureRecord.java
- service/command-cli/src/test/java/org/speckitty/commandcli/application/diagnostics/FailureRecordTest.java
- service/command-cli/src/test/java/org/speckitty/commandcli/adapter/in/http/operations/CatchAllLoggingTest.java
execution_mode: code_change
owned_files:
- service/command-cli/src/main/java/org/speckitty/commandcli/application/diagnostics/**
- service/command-cli/src/main/java/org/speckitty/commandcli/adapter/in/http/operations/WriteController.java
- service/command-cli/src/test/java/org/speckitty/commandcli/application/diagnostics/**
- service/command-cli/src/test/java/org/speckitty/commandcli/adapter/in/http/operations/CatchAllLoggingTest.java
role: implementer
tags: []
tracker_refs: []
---
# Work Package Prompt: WP06 — Catch-all logging (#231 4.14)

## ⚡ Do This First: Load Agent Profile

Load your assigned profile before reading anything else: `/spk-charter-profile-load java-jenny`
(or `spec-kitty agent profile show java-jenny`). Adopt its identity, scope and boundaries.

## Objective

Today an unexpected exception on the write path answers a correct coded `500` but leaves **no trace**: command-cli has no logger at all. Add exactly **one** `System.Logger` record at `WARNING` per catch-all hit, in four places:

1. `WriteService.write`'s catch-all;
2. `WriteController`'s catch-all;
3. `StatusLogRepairService`'s catch-all;
4. `StatusLogRepairController`'s catch-all.

The record names the operation, the coded refusal and the **exception class chain**, and nothing else. No exception messages, because JDK I/O messages carry host paths. No path, token, row value or request content (FR-016, NFR-003). The operator can then tell a bug (`NullPointerException`) from a disk fault (`java.nio.file.AccessDeniedException`).

## Context (read first)

- Spec: [spec.md](../spec.md) User Story 3 scenario 1, FR-016, NFR-003, SC-004, and the Assumptions ("No exception messages in logs"; `TornTailRepair` is **not** a precedent, because it logs paths at `:85`).
- Plan: [plan.md](../plan.md) D-F and **"Post-plan adversarial squad → Logging"**.
- Catch-alls (the lines may have moved after WP03 and WP05; find them by `catch (RuntimeException`):
  - `application/write/WriteService.java` (base :59-65);
  - `adapter/in/http/operations/WriteController.java` (:55-61);
  - `application/repair/StatusLogRepairService.java` and `adapter/in/http/operations/StatusLogRepairController.java` (WP03).
- Logging stack: Spring Boot's web starter brings Logback plus `jul-to-slf4j`. `System.Logger` is backed by JUL (there is no `slf4j-jdk-platform-logging` in `gradle/verification-metadata.xml`), and Boot bridges it to Logback on stdout, which the launcher appends to `service.log`.
  - **Do not** add logging properties: `ConfigPin.java:53-55` pins `logging.file.*` and `logging.config` empty.
  - `System.Logger` is `java.lang`. The architecture rules allow it in application and adapter. Do **not** log in `domain`.
- Native image: `System.Logger` needs no hints.

## Subtasks

### T029 — `FailureRecord` formatter

- **The method.** `application/diagnostics/FailureRecord.java`:
  ```java
  static String describe(String operation, String code, Throwable failure)
  ```
  It returns, for example, `"repairStatusLog failed with STATUS_EVENT_PERSISTENCE_VERIFICATION_FAILED: java.lang.IllegalStateException <- java.io.UncheckedIOException <- java.nio.file.AccessDeniedException"`.
- **Rules:**
  - Walk `getCause()` only; ignore suppressed exceptions.
  - Stop on a cycle, using an identity set.
  - Cap the depth at 8, and append `" <- …"` when capped.
  - Use `getClass().getName()`. **Never** `getMessage()`, `toString()` or `getLocalizedMessage()`.
  - The operation names are the contract's operationIds (`recordTransition`, `recordTransitionBatch`, `recordInnerStateAnnotation`, `repairStatusLog`); read them from `contracts/mission-write/paths/*.yaml`. Pass the one in hand. The code is the `RefusalCode` name.
- **`FailureRecordTest`:**
  - a chain of three;
  - a self-cycle (`e.initCause` creating A→B→A, through a custom `Throwable` subclass overriding `getCause`);
  - depth 12, capped at 8;
  - a message containing `/home/x/secret` that never appears in the output.

### T030 — Log in the four catch-alls

- **The logger.** In each class, a `private static final System.Logger LOG = System.getLogger(<Class>.class.getName());`.
- **The call.** In the catch-all, before returning the coded 500:
  ```java
  LOG.log(System.Logger.Level.WARNING, FailureRecord.describe(op, code, e));
  ```
  Use the `String` overload; **never** pass the throwable, because the handler would print its message and stack. Exactly one record per failure.
- **No double logging.** If a failure is caught by the service's catch-all, the controller must not log it again: the controller's catch-all only fires for failures *outside* the service call. Check this with a test.
- **Errors.** The catch-alls stay `RuntimeException`-only. An `Error` propagates to Spring's default handling. Record that decision in a code comment on one catch-all.
- **Declared out-of-map edits:** `WriteService.java` (WP05), and `StatusLogRepairService.java` and `StatusLogRepairController.java` (WP03). Each edit is the `LOG` field plus one line.

### T031 — Tests

**`CatchAllLoggingTest`.** For each of the four catch-alls, inject a `RuntimeException`; use the narrowest seam available (a fake `StatusLog` whose session throws, a fake catalog, or a rig hook).

- **Capture:** attach a `java.util.logging.Handler` to `java.util.logging.Logger.getLogger(<Class>.class.getName())`.
  - **Keep a strong reference** to that JUL logger in a test field, because JUL loggers are held weakly.
  - Set the level to `ALL`.
  - Remove the handler in `@AfterEach`/`finally`.
- **Assert:**
  - exactly one record, at `WARNING` (JUL `Level.WARNING`);
  - its message equals `FailureRecord.describe(...)`;
  - the HTTP answer is the coded 500, unchanged.
- **Marker hygiene (NFR-003).** The injected exception's message, and the request, carry markers:
  - a path `/tmp/MARKER-PATH`;
  - a token-like `MARKERTOKEN0123456789abcdef`;
  - a row value `"MARKER-ROW"`.

  Assert that no captured record (message, parameters, thrown) contains any marker. Pair it with a positive control: the class name of the injected exception **is** present.

If wiring a JUL handler under Spring's bridge proves unreliable in the rig, test at service level for the services and with the controller instantiated directly. The assertion target stays the JUL record.

## Definition of Done

- **Tests:** `./gradlew :command-cli:check` is green, including `ArchitectureRulesTest`: no domain logging.
- **Hygiene:** no `getMessage`/`toString` of a throwable in any new log call (grep). No logging properties added.
- **Commits:** commit messages end with `Co-Authored-By:` (your model).

## Risks and reviewer guidance

- **Leaks:** passing the throwable to `LOG.log(level, msg, thrown)` would leak messages, so check every call site.
- **Flaky capture:** a test that passes only because the logger was garbage-collected and recreated. Check for the strong reference.

## Do not

- Do not change refusal codes, bodies or timings.
- Do not run `spec-kitty` lifecycle commands, push, or open a PR.
