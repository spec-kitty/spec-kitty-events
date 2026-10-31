---
work_package_id: WP02
title: 'On-disk process surface: state dir, lock, endpoint file, token, identity'
dependencies:
- WP01
requirement_refs:
- FR-002
- FR-003
- FR-013
planning_base_branch: issue-156-command-cli
merge_target_branch: issue-156-command-cli
branch_strategy: Planning artifacts for this mission were generated on issue-156-command-cli. During /spec-kitty.implement this WP may branch from a dependency-specific base, but completed changes must merge back into issue-156-command-cli unless the human explicitly redirects the landing branch.
base_branch: kitty/mission-command-cli-write-service-01M694HJ
base_commit: ec181359bb633ada4d446066ae32852374fd8656
created_at: '2026-10-31T15:02:26.329793+00:00'
subtasks:
- T007
- T008
- T009
- T010
- T011
- T012
history:
- timestamp: '2026-10-31T14:30:00Z'
  lane: planned
  agent: system
  action: Prompt generated via /spec-kitty.tasks
agent_profile: java-jenny
authoritative_surface: service/command-cli/src/main/java/org/speckitty/commandcli/adapter/out/
create_intent:
- tests/test_command_cli_endpoint_parity.py
execution_mode: code_change
owned_files:
- service/command-cli/src/main/java/org/speckitty/commandcli/domain/**
- service/command-cli/src/main/java/org/speckitty/commandcli/adapter/out/**
- service/command-cli/src/test/java/org/speckitty/commandcli/domain/**
- service/command-cli/src/test/java/org/speckitty/commandcli/adapter/out/**
- tests/test_command_cli_endpoint_parity.py
role: implementer
tags: []
tracker_refs: []
---
# Work Package Prompt: WP02 – On-disk process surface

## ⚡ Do This First: Load Agent Profile

Load your assigned profile before reading anything else: `/spk-charter-profile-load java-jenny`
(or `spec-kitty agent profile show java-jenny`). Adopt its identity, scope and boundaries.

## Branch Strategy

The planning base and the merge target are both `issue-156-command-cli`; the PR targets `main`. The workspace comes from `lanes.json`.
`spec-kitty agent action implement WP02 --agent claude --mission command-cli-write-service-01M694HJ`

## Ground rules (all WPs)

- ATDD, red first: write the acceptance tests of each subtask before the code, and see them fail for the right reason.
- Java 25 at `/usr/lib/jvm/temurin-25`. Build with `jh=$(scripts/ensure_jdk25.sh); JAVA_HOME=$jh ./gradlew --no-daemon -Dorg.gradle.java.installations.paths=$jh :command-cli:check`.
- No new dependency. Everything needed is already in `gradle/libs.versions.toml` and `gradle/verification-metadata.xml`. If you hit Central HTTP 429, wait and retry; never reroute.
- No dependency on core (`spec-kitty/spec-kitty`). Core paths are cited for parity only.
- Never log or echo the token. No body names a host path.
- Commits carry `Co-Authored-By: Stijn Dejongh <stijn.dejongh@sddevelopment.be>`. No AI model identifier anywhere.

## Objective

Build the files the launcher reads, exactly as core's reader enforces them: the state directory, the single-instance lock, the endpoint file (published atomically and removed conditionally), the token and the repository identity.

Spec: FR-002, FR-003, FR-013 (removal part). Data model: StateDirectory, ServiceLock, EndpointFile, InstanceId, RepositoryId. Contract: `kitty-specs/.../contracts/endpoint-file.md`. Research: D-2, D-3, D-4, D-10.

## Subtasks

### T007 Domain values (framework-free)
- **`InstanceId`:** a ULID minted from a clock and a `RandomGenerator`, as 26 Crockford characters. Validate it against `^[0-9A-HJKMNP-TV-Z]{26}$`.
- **`RepositoryId`:** built from an already-resolved forward-slash path string, as SHA-256 lowercase hex. The domain gets the string; the adapter resolves the path.
- **`ProtocolSupport`:** `SUPPORTED = [1]`, plus `accepts(String header)`, which takes a strict integer with no sign, spaces or leading `+`.
- **`RefusalCode`:** an enum of the PR1 codes, each with its status and title.
- **`ServiceState`:** `READY`, `DRAINING`, `STOPPED`.

### T008 StateDirectory
`prepare(Path gcd)`: resolve `gcd/spec-kitty-write-service`.
- If it is absent, create it with 0700 on POSIX, or an owner-only ACL on Windows.
- If it is a symlink, throw `StateDirectoryRefused`, and never follow it.
- If its mode has group or other bits, chmod it to 0700.

Tests use `@TempDir` and POSIX-only assumptions guarded with `assumeTrue`.

### T009 ServiceLock
`ServiceLock.tryAcquire(Path dir)` returns `Optional<ServiceLock>`.
- Open `service.lock` with `CREATE, WRITE`, NOFOLLOW (`LinkOption.NOFOLLOW_LINKS`), and owner-only permissions on create, then call `tryLock()`.
- Empty means it is held elsewhere. An `OverlappingFileLockException` also counts as held.
- Keep the channel open until `close()`.
- Test that a second acquire fails while the first holds. Test cross-process exclusion with a child JVM (`ProcessBuilder` running `java -cp <test classpath> …LockHolder`). This is the real proof: JVM-internal exclusion alone is not.

### T010 EndpointFile
`publish(Endpoint e)` writes the compact JSON object, keys as in the contract.
1. Write to `endpoint.json.<instanceId>.tmp` with `CREATE_NEW`, owner-only.
2. Call `force(true)`.
3. Move with `ATOMIC_MOVE` and `REPLACE_EXISTING`.
4. Best-effort fsync of the directory on POSIX.

`removeIfOwn(InstanceId id)` reads the file (capped at 64 KiB, NOFOLLOW) and deletes it only if `instance_id` equals `id`. An unreadable file is left alone.

Use Jackson (`JsonMapper`) in the adapter, which is allowed. The domain stays free of Jackson.

### T011 WriteToken
32 bytes from `SecureRandom`, base64url without padding, which gives 43 characters. `matches(String presented)` uses `MessageDigest.isEqual` on the UTF-8 bytes; a null presented token is false. `toString()` returns `"WriteToken[redacted]"`.

### T012 Parity with core's endpoint reader
Add a pytest, `tests/test_command_cli_endpoint_parity.py`, that re-implements nothing. It encodes core's rules as data, copied from core `status/write_service_protocol.py:530-649` and cited, and validates a sample endpoint file that the Java test suite writes to `service/command-cli/build/endpoint-sample/endpoint.json`.
- **Simpler, preferred:** a JUnit test asserts each rule in Java (mode bits, regular file, size, field types, the token alphabet and length, `host`), with the core citation in the test's javadoc.
- Choose the JUnit route unless a cross-language check is needed. If you choose it, drop the pytest path from `owned_files` with a rationale.

## Definition of Done
- Every rule of `contracts/endpoint-file.md` has a failing-then-passing test.
- The lock is proven across processes.
- Removal is conditional.
- No `java.io`/`java.nio.file` in `domain`.

## Risks
- POSIX record locks drop on any close of the same file in-process, so the lock file is opened exactly once.
- Windows: `PosixFileAttributeView` is absent. Use `AclFileAttributeView`, with only the owner granted. Guard the tests with OS conditions.
