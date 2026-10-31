---
work_package_id: WP07
title: Contract settle and documentation
dependencies:
- WP05
requirement_refs:
- FR-016
- FR-020
- C-005
- C-006
- C-007
- C-001
planning_base_branch: issue-156-command-cli
merge_target_branch: issue-156-command-cli
branch_strategy: Planning artifacts for this mission were generated on issue-156-command-cli. During /spec-kitty.implement this WP may branch from a dependency-specific base, but completed changes must merge back into issue-156-command-cli unless the human explicitly redirects the landing branch.
base_branch: kitty/mission-command-cli-write-service-01M694HJ
base_commit: d4307d781a46024e51171079ed9787f76706c590
created_at: '2026-10-31T15:40:40.419670+00:00'
subtasks:
- T035
- T036
- T037
- T038
- T039
history:
- timestamp: '2026-10-31T14:30:00Z'
  lane: planned
  agent: system
  action: Prompt generated via /spec-kitty.tasks
agent_profile: curator-carla
authoritative_surface: contracts/mission-write/
create_intent:
- service/command-cli/README.md
execution_mode: code_change
owned_files:
- contracts/mission-write/**
- docs/**
- service/command-cli/README.md
- CHANGELOG.md
- service/README.md
role: implementer
tags: []
tracker_refs: []
---
# Work Package Prompt: WP07 – Contract settle and documentation

## ⚡ Do This First: Load Agent Profile

Load your assigned profile before reading anything else: `/spk-charter-profile-load curator-carla`
(or `spec-kitty agent profile show curator-carla`). Adopt its identity, scope and boundaries.

## Branch Strategy

The planning base and the merge target are both `issue-156-command-cli`; the PR targets `main`. The workspace comes from `lanes.json`.
`spec-kitty agent action implement WP07 --agent claude --mission command-cli-write-service-01M694HJ`

## Ground rules (all WPs)

- ATDD, red first: write the acceptance tests of each subtask before the code, and see them fail for the right reason.
- Java 25 at `/usr/lib/jvm/temurin-25`. Build with `jh=$(scripts/ensure_jdk25.sh); JAVA_HOME=$jh ./gradlew --no-daemon -Dorg.gradle.java.installations.paths=$jh :command-cli:check`.
- No new dependency. Everything needed is already in `gradle/libs.versions.toml` and `gradle/verification-metadata.xml`. If you hit Central HTTP 429, wait and retry; never reroute.
- No dependency on core (`spec-kitty/spec-kitty`). Core paths are cited for parity only.
- Never log or echo the token. No body names a host path.
- Commits carry `Co-Authored-By: Stijn Dejongh <stijn.dejongh@sddevelopment.be>`. No AI model identifier anywhere.

## Objective

- Settle what `command-cli` now proves in the contract: `/shutdown` and `HealthStatus` (`draining`) are no longer provisional, and `HealthStatus` names `SERVICE_DRAINING`.
- Document the built service and how core binds it.

Spec: FR-016, FR-020. Operator rulings 4, 5 and 6.

## Subtasks

### T035 Contract settle
- `paths/shutdown.yaml`: remove `x-provisional`.
- `schemas/HealthStatus.yaml`:
  - remove `x-provisional`;
  - description: `draining` refuses new writes with `SERVICE_DRAINING` (503, no Retry-After);
  - name the drain triggers (shutdown, idle exit).
- `schemas/WriteRefusalCode.yaml`: drop the "Provisional (shutdown)." clause on `SERVICE_DRAINING`.
- `LAUNCHER.md`:
  - The "Stop" section is no longer provisional.
  - The Status paragraph says `/shutdown` was settled when command-cli was built (#156).
  - Paths are served under `/api/v1` (operator ruling 5; core client fix is spec-kitty/spec-kitty#6910).
  - Idle exit defaults to 1800 s.
  - Logs go to stdout and stderr, which the launcher redirects to `service.log`.
- `CHANGELOG.md` (`1.0.0-SNAPSHOT`, "Changed"): record all of the above, and remove these items from "Provisional".
- `info.version` stays `1.0.0-SNAPSHOT`, and no tag is cut.

### T036 Contract tools and tests
Run the contract checks that CI runs. Find them in `.github/workflows/ci.yml` and the Makefile, for example:
- `uv run python contracts/tools/provisional_check.py`;
- `breaking_check`;
- `example_check`;
- `leak_scan`;
- `bundle.py --bundle-only`;
- `uv run pytest tests -k "contract or mission_write" -q`.

Fix what they flag. If `enum_pins.json` pins `HealthStatus`, the values are unchanged and only provisional markers move.

### T037 service/command-cli/README.md
Cover:
- how to run it, and its command line;
- the files in the state directory;
- the order of checks;
- the fail-closed writes (until #160/#161);
- lifecycle and idle;
- logging;
- the native targets and release asset names;
- **How core binds it:**
  - the pin prefix `command-cli` (core `src/kernel/service_pin.py` `DEFAULT_ASSET_PREFIXES`);
  - the resolution order (core `src/specify_cli/services/resolution.py`);
  - the start args (core `services/policies.py` `WRITE_SERVICE`);
  - `bind_event_bridge(*, emitter_factory, transition_port)` at core `src/runtime/next/_internal_runtime/events.py:308`;
  - `WorkPackageTransitionPort.record_work_package_transition` (core `src/runtime/next/event_bridge.py`);
  - wire shapes in core `src/specify_cli/status/write_service_protocol.py`.

### T038 Docs
- `docs/architecture/services/{overview,write-service-protocol,read-write-split,README}.md`: the write service exists, as runnable and fail-closed.
- ADR-0002 lines about 56 and 100, and ADR-0003 lines 129-130: no longer "future". Add a dated note; do not rewrite decisions.
- `docs/guides/service-protocol-and-releases.md`: the new assets.
- `service/README.md`: a pointer to the new module.
- Keep doc-kitty frontmatter, and update `updated`.

### T039 CHANGELOG
Add an Unreleased entry on the service line for the `command-cli` deployable. Check how the CHANGELOG separates the PyPI and `service-v*` lines.

## Definition of Done
- The contract tools are green.
- The docs describe the built service.
- No stale "future command-cli" wording remains (`rg -n "future.*command-cli|command-cli.*future"`).
