---
work_package_id: WP05
title: 'Lifecycle: start, shutdown, idle and signal exit, process tests'
dependencies: [WP04]
requirement_refs:
- FR-012
- FR-014
- NFR-002
- NFR-003
- SC-001
- SC-004
planning_base_branch: issue-156-command-cli
merge_target_branch: issue-156-command-cli
branch_strategy: Planning artifacts were generated on issue-156-command-cli. Completed changes merge back into issue-156-command-cli; the PR then targets main.
subtasks:
- T024
- T025
- T026
- T027
- T028
- T029
history:
- timestamp: '2026-10-31T14:30:00Z'
  lane: planned
  agent: system
  action: Prompt generated via /spec-kitty.tasks
agent_profile: java-jenny
authoritative_surface: service/command-cli/src/main/java/org/speckitty/commandcli/adapter/in/process/
create_intent: []
execution_mode: code_change
owned_files:
- service/command-cli/src/main/java/org/speckitty/commandcli/adapter/in/process/**
- service/command-cli/src/test/java/org/speckitty/commandcli/process/**
role: implementer
tags: []
tracker_refs: []
---
# Work Package Prompt: WP05 – Lifecycle and process

## ⚡ Do This First: Load Agent Profile

Load your assigned profile before reading anything else: `/spk-charter-profile-load java-jenny`
(or `spec-kitty agent profile show java-jenny`). Adopt its identity, scope and boundaries.

## Branch Strategy

The planning base and the merge target are both `issue-156-command-cli`; the PR targets `main`. The workspace comes from `lanes.json`.
`spec-kitty agent action implement WP05 --agent claude --mission command-cli-write-service-01M694HJ`

## Ground rules (all WPs)

- ATDD, red first: write the acceptance tests of each subtask before the code, and see them fail for the right reason.
- Java 25 at `/usr/lib/jvm/temurin-25`. Build with `jh=$(scripts/ensure_jdk25.sh); JAVA_HOME=$jh ./gradlew --no-daemon -Dorg.gradle.java.installations.paths=$jh :command-cli:check`.
- No new dependency. Everything needed is already in `gradle/libs.versions.toml` and `gradle/verification-metadata.xml`. If you hit Central HTTP 429, wait and retry; never reroute.
- No dependency on core (`spec-kitty/spec-kitty`). Core paths are cited for parity only.
- Never log or echo the token. No body names a host path.
- Commits carry `Co-Authored-By: Stijn Dejongh <stijn.dejongh@sddevelopment.be>`. No AI model identifier anywhere.

## Objective

Make the binary behave as `LAUNCHER.md` requires, end to end:
- lock before boot;
- publish after bind;
- ready;
- drain and exit on `/shutdown` or idle;
- remove the endpoint on SIGTERM.

Prove it by driving the built jar as a child process in exactly the launcher's sequence.

Spec: FR-002, FR-011 to FR-014, NFR-002, NFR-003, SC-001, SC-004; User Stories 1 and 4. Research: D-2, D-8, D-9.

## Subtasks

### T024 Startup sequence
`CommandCliMain`, in `config` (out-of-map edit, noted):
1. parse the options;
2. `StateDirectory.prepare`;
3. `ServiceLock.tryAcquire`. If the lock is held, log one line and `System.exit(0)`.
4. Mint the instance and the token, and compute the identity.
5. Run `SpringApplication` with those beans.

`EndpointPublisher` listens to `WebServerInitializedEvent`. It publishes the endpoint file with the bound port and `started_at = now`. A publish failure exits 1.

### T025 Shutdown
`ShutdownPort` implementation:
1. after the 202 is committed, on a separate thread, `awaitIdle(5 s)`;
2. `EndpointFile.removeIfOwn`;
3. close the context with `SpringApplication.exit`;
4. release the lock;
5. `System.exit(0)`.

Guard it so that it runs once.

### T026 IdleWatch
- A scheduled single thread with a 1 s tick.
- `lastActivity` is updated by the in-flight filter on request exit.
- When `now - lastActivity >= idleTimeout` and nothing is in flight, run `beginDrain()` and then the shutdown sequence.

### T027 Shutdown hook
`Runtime.addShutdownHook`, registered after publish, calls `removeIfOwn`. The orderly path already ran it, so it must be idempotent.

### T028 Process tests (`@Tag("process")`; they need the bootJar, so depend on it in the Gradle test task)
Use the `AppProcess` precedent from the read service. Each test copies the launcher: stdout and stderr append to `<gcd>/spec-kitty-write-service/service.log`, and `JAVA_TOOL_OPTIONS`, `JDK_JAVA_OPTIONS` and `_JAVA_OPTIONS` are stripped.

- **(a) Ready:** start; poll for `endpoint.json` every 50 ms with a 15 s budget; check the file rules; handshake (instance, repositoryId, `[1]`); health `ready`. Measure time-to-ready against NFR-002 (≤ 10 s on the jar).
- **(b) Second instance:** a second `serve` on the same gcd exits 0 within 10 s, and the endpoint file is unchanged (same instance and mtime).
- **(c) Shutdown:** `/shutdown` returns 202; health returns `draining` or the connection is refused; the process exits 0 within 10 s; the endpoint file is gone.
- **(d) Idle exit:** `--idle-timeout 2` exits 0 within 10 s, and the endpoint file is gone.
- **(e) SIGTERM (POSIX only):** `Process.destroy()` ends the process, and the endpoint file is gone.
- **(f) Bad arguments:** exit 2, and nothing is published.
- **(g) Loose state directory:** a symlinked state dir exits non-zero, and nothing is published.

### T029 Log hygiene
- Across (a) to (e), read `service.log` and assert it does not contain the token.
- Assert it contains no request header values sent by the tests (sentinel strings in Host, User-Agent and Origin).

## Definition of Done
- Process tests are green on Linux.
- The jar reaches ready in ≤ 10 s and exits after `/shutdown` in ≤ 10 s.

## Risks
- `System.exit` inside Spring: use `SpringApplication.exit(ctx)` and then `System.exit` from a non-request thread, or the 202 never flushes.
- Test flakiness: always use budgets and polling, never sleeps that assume timing.
