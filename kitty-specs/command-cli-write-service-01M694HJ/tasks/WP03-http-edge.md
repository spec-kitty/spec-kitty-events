---
work_package_id: WP03
title: 'HTTP edge: bind, guard, token, protocol, size, problems'
dependencies: [WP01]
requirement_refs:
- FR-004
- FR-005
- FR-006
- FR-007
- FR-015
planning_base_branch: issue-156-command-cli
merge_target_branch: issue-156-command-cli
branch_strategy: Planning artifacts were generated on issue-156-command-cli. Completed changes merge back into issue-156-command-cli; the PR then targets main.
subtasks:
- T013
- T014
- T015
- T016
- T017
- T018
history:
- timestamp: '2026-10-31T14:30:00Z'
  lane: planned
  agent: system
  action: Prompt generated via /spec-kitty.tasks
agent_profile: java-jenny
authoritative_surface: service/command-cli/src/main/java/org/speckitty/commandcli/adapter/in/http/edge/
create_intent: []
execution_mode: code_change
owned_files:
- service/command-cli/src/main/java/org/speckitty/commandcli/adapter/in/http/edge/**
- service/command-cli/src/test/java/org/speckitty/commandcli/adapter/in/http/edge/**
role: implementer
tags: []
tracker_refs: []
---
# Work Package Prompt: WP03 – HTTP edge

## ⚡ Do This First: Load Agent Profile

Load your assigned profile before reading anything else: `/spk-charter-profile-load java-jenny`
(or `spec-kitty agent profile show java-jenny`). Adopt its identity, scope and boundaries.

## Branch Strategy

The planning base and the merge target are both `issue-156-command-cli`; the PR targets `main`. The workspace comes from `lanes.json`.
`spec-kitty agent action implement WP03 --agent claude --mission command-cli-write-service-01M694HJ`

## Ground rules (all WPs)

- ATDD, red first: write the acceptance tests of each subtask before the code, and see them fail for the right reason.
- Java 25 at `/usr/lib/jvm/temurin-25`. Build with `jh=$(scripts/ensure_jdk25.sh); JAVA_HOME=$jh ./gradlew --no-daemon -Dorg.gradle.java.installations.paths=$jh :command-cli:check`.
- No new dependency. Everything needed is already in `gradle/libs.versions.toml` and `gradle/verification-metadata.xml`. If you hit Central HTTP 429, wait and retry; never reroute.
- No dependency on core (`spec-kitty/spec-kitty`). Core paths are cited for parity only.
- Never log or echo the token. No body names a host path.
- Commits carry `Co-Authored-By: Stijn Dejongh <stijn.dejongh@sddevelopment.be>`. No AI model identifier anywhere.

## Objective

Every request passes the contract's order of checks before any operation runs:
1. the Host/Origin guard (403);
2. the bearer token (401);
3. the protocol header (400);
4. the size cap (413).

BindGuard keeps the service on `127.0.0.1`. ConfigPin keeps working-directory configuration files out.

Spec: FR-004 to FR-007, FR-015; User Story 2. Research: D-5. Operator ruling 2: copy the guard **with its tests**.

## Subtasks

### T013 BindGuard and ConfigPin
Copy `service/src/main/java/org/speckitty/missionstatus/config/BindGuard.java` and `ConfigPin.java`, adapted to the package `org.speckitty.commandcli.adapter.in.http.edge`, together with `BindGuardTest`, `BindGuardProcessTest` and `ConfigPinProcessTest`. Adapt the process tests to the `command-cli` jar later, in WP05.
- Register both through `META-INF/spring.factories` in the module's resources. That file is owned by WP01: make the edit and note it as out-of-map.
- Replace the `mission-status.*` keys. Port 0 is the only port and is always ephemeral.

### T014 HostOriginFilter
Copy the filter and its test set (`HostTableTest`, `OriginTableTest`, `HostOriginFilterTest`, `DirectErrorPathTest`, `RequestEchoTest`, `MalformedQueryTest`, `RegistrationTest` and the rig).
- The refusal body is the `WriteRefusal`: `{type: about:blank, title, status: 403, code: REQUEST_REFUSED}`.
- Keep the error-dispatch coverage and the raw-Host comparison.
- Drop the tests that are about stream-specific paths.

### T015 BearerTokenFilter
It runs after the guard and on every path, including unknown paths, so that a 404 does not reveal routes to a caller without the token.
- Accept only `Authorization: Bearer <token>`, with exactly one space and a case-sensitive scheme.
- Missing, wrong, or a malformed header: 401, `WWW-Authenticate: Bearer`, code `WRITE_TOKEN_INVALID`.
- The token comes from a `TokenSource` bean (WP02's `WriteToken`).

### T016 ProtocolHeaderFilter
- It applies to every path except `/api/v1/handshake` and `/api/v1/health` (exact match).
- The header `Spec-Kitty-Write-Protocol` must be present exactly once and be a supported major. Otherwise: 400 `PROTOCOL_VERSION_UNSUPPORTED`, detail `"Supported protocol versions: 1."`.
- **Decide** whether unknown paths get the protocol check or a 404. The contract only binds operations. Recommendation: apply it only to known POST operation paths, so unknown paths fall through to 404.

### T017 BodySizeFilter
- It applies to the POST write paths.
- A declared `Content-Length` over 1,048,576 gives 413 at once.
- Otherwise wrap the input stream and count. When the count passes the cap, throw `RequestTooLarge`, which the advice maps to 413 `REQUEST_TOO_LARGE`.

### T018 RefusalBody and the order matrix
- One `RefusalBody` writer for filters and advice, producing compact JSON plus a newline, as the read service's canonical form does.
- A parameterized test sends combinations: bad Host plus bad token gives 403; good Host plus bad token plus no protocol gives 401; and so on through size. This proves the first failing check wins.
- An unknown path with good Host and token: 404 problem without `code`.
- Container error dispatch (`TRACE`): problem body, no echo.

## Definition of Done
- The order matrix is green.
- The copied guard tests are green against the write service.
- No response body contains a request header value.

## Risks
- Filter ordering: register with explicit `FilterRegistrationBean` orders (guard `HIGHEST_PRECEDENCE`, then +1, +2, +3).
- Error dispatches must pass the guard and the token as well.
