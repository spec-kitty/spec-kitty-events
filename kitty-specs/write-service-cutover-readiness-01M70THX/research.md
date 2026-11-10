# Research: Write-service cut-over readiness

**Inputs:**
- Issue #236.
- The squad synthesis and the write-service research of 2026-11-09 (read against events `62af21d0`, core `c641725e`/`9c1bff4c18`).
- The brownfield scout of 2026-11-09, [research/brownfield-scout.md](./research/brownfield-scout.md). It re-anchors core at `main` @ `366ff233` and the pin `512567ee`.
- Prior mission `events-write-core-01M6TP6W` (research §11, R15–R31).

## Decisions

### R1. Where repair lives (D2)
- **Decision:** a `mission-write` HTTP operation, `POST /missions/{missionId}/status-log-repairs`. A core CLI verb that calls it is a core follow-up.
- **Rationale:** it reuses the token, the Host/Origin guard, the protocol header, location verification, the turn and the Mission lock. The service stays the only status-log writer.
- **Alternatives rejected:**
  - A `command-cli` subcommand. `ServeOptions` accepts only `serve`; it would collide with `service.lock` and bypass the transport guards.
  - A Python edit in core. It contradicts the single-writer rule, and it is deleted by #6195 anyway.

### R2. Healthy log (D1)
- **Decision:** `200` with `{repaired: false, action: none}`, and the log byte-identical.
- **Rationale:** idempotent and script-safe. It also avoids a new refusal code, which core's closed `WriteRefusalCode` enum could not parse.
- **Alternative rejected:** `409 STATUS_LOG_NOT_TORN`.

### R3. Three actions, not "truncate to the last LF"
- **Decision:** `none`, `completed` (exactly one readable row lacking its LF: add LF) and `quarantined` (anything else: move the fragment to `status.events.jsonl.torn`, truncate, sync).
- **Rationale:** core's implicit heal (`store.py:357-358`, at both main and the pin) appends LF regardless of content. A complete row missing its LF is a real event, which core's `splitlines()` reads fine (scout §A.2), so truncating it would delete an event. Quarantine keeps every byte (the `TornTailRepair` convention).
- **Divergence judgement:** core heals as a side effect of temp-and-rename, not as a policy. The service matches the outcome for a complete row and fails safe (quarantine) for anything else.

### R4. Split across modules
- **Decision:**
  - **Event-log statuslog** does the file IO under the existing `withMissionLock`.
  - **Command-cli** decides "complete row?" through a callback, and computes `readableAfterRepair` from the returned bytes.
- **Rationale:** `EventLogBoundaryTest.statusLogRule` forbids the codec in statuslog, and `WriteOwnershipRulesTest` forbids file IO in command-cli (scout §B.1, §C.3).

### R5. Evidence field (D3), corrected rationale
- **Decision:** an optional `implementationEvidencePresent` (boolean or null) on `TransitionUnit`. When present it is used verbatim for an unforced `in_progress → for_review`; absent or null keeps today's inference. It is request-only.
- **Correction:** core's CLI option is flag-only (`agent/status.py:419-425`, `wp_lifecycle.py:853` at core main), so no CLI door can send `false`. That makes the field authority-input parity with core's `TransitionRequest`, not a cut-over regression fix. Priority P2, still before #6195.
- **Rolling upgrade:**
  - Core's launcher reuses any running protocol-1 service without comparing `serviceVersion` (scout §A.5).
  - So a new client that sends the field to an older resident service gets `400 REQUEST_MALFORMED`.
  - Recorded on #6195 (FR-022b).

### R6. `workspace_context_required` (#223)
- **Decision:** keep the reason, and document why no wire request produces it. Core's guard is fail-closed for its own batch API (`transition_pipeline.py:322-337` at core main, `:289-304` at the pin; operator decision 2026-09-07, #946, DRIFT-3).
- **Fix:** replace the fabricated `<root>` with the resolved root.
- **Finding routed to core:** a blank `--workspace-context ""` is refused today and would be accepted after #6195 (scout §A.4). This goes into the core follow-up issue.

### R7. Drain (D10)
- **Decision:** a drain-aware wait. Requests still waiting for the turn or the Mission lock when draining starts answer `503 SERVICE_DRAINING`; requests holding the lock finish. `DRAIN_BUDGET` stays at 5 s.
- **Rationale:** the launcher contract (`LAUNCHER.md:50-57`), core's `STOP_TIMEOUT_S = 10.0` (`launcher.py:83`) and `LauncherContractProcessTest` NFR-003 all fix the timing. #236 AC 5 was rewritten (comment on #236).
- **Alternatives rejected:**
  - Drain of 15 s or more: it breaks the launcher contract and core.
  - Write budget under 5 s: `STATUS_LOCK_HELD` fires sooner under core contention.

### R8. Logging (#231 4.14)
- **Decision:** `System.Logger` `WARNING`, exactly one record per catch-all, naming the operation, the code and the exception *class* chain. No messages.
- **Rationale:** JDK I/O exception messages carry host paths. `TornTailRepair` is not a precedent: it logs paths (`:85`). `System.Logger` needs no native hints and is bridged to Logback, which writes to stdout and from there to the launcher's `service.log`.

### R9. Contract version and checks
- **Decision:** the additions go under `1.0.1-SNAPSHOT`, which is unreleased: the tags are only `service-v0.1.0-rc.1`/`rc.2`. If an rc is cut first, they move to `1.0.2-SNAPSHOT`.
- **Checks:** `breaking_check`, `citation_check`, `provisional_check`, `layout_check` and `structure_check` run by hand (they are not CI gates, #151), and their output is recorded.

### R10. Scope boundaries
- The `REQUEST_MALFORMED` read-back description (a #231 comment) rides with item 4.15.
- D4 (`validate_transition`) is M2's (#237).
- The read `StatusReducer` lift (G-4) is a separate events issue, not this mission.

## Core anchors (core `main` @ `366ff233` / pin `512567ee`)

See [research/brownfield-scout.md §A.1](./research/brownfield-scout.md). The key rows:

| Item | main | pin |
|---|---|---|
| tail heal `existing += "\n"` | `store.py:357-358` | `:357-358` |
| evidence inferred only when `None` | `transition_pipeline.py:174-176` | same |
| `default_workspace_context` docstring | `transition_pipeline.py:322-337` | `:289-304` |
| claimed→in_progress context guard | `wp_state.py:455-456` | `:341-342` |
| in_progress→for_review evidence guard | `wp_state.py:502-506` | `:388` |
| CLI `--implementation-evidence-present` (flag-only) | `agent/status.py:419-425` | `:366-372` |
| launcher `STOP_TIMEOUT_S = 10.0` | `services/launcher.py:83` | — |
