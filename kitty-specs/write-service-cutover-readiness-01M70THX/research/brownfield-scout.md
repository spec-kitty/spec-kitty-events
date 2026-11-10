# Scout: M1 write-service cut-over readiness (brownfield, read-only)

Date 2026-11-09. Nothing edited, committed, pushed or commented.

- **Events**: `origin/main` = `62af21d0`. Read from `git archive 62af21d0` (scratch copy).
- **Core main**: `366ff233c08a8675bc9e12875a0b9dfbc7a30002` (2026-11-09). 3164 commits ahead of the pin.
- **Core pin**: `512567eee71e31bb39f580c4f7acf8b865e9d1b2` (`service/core-pin.json`).
- Line refs are `file:line` at events `62af21d0` unless prefixed `core@main` / `core@pin`.

## A. Core re-anchoring

### A.1 Line map

| Item | core@main 366ff233 | core@pin 512567ee |
|---|---|---|
| `store.append_raw_rows_atomic` def | store.py:333 | :333 |
| tail heal `existing += "\n"` (after `if existing and not existing.endswith("\n")`) | store.py:357-358 | :357-358 |
| temp+fsync+`os.replace`+dir fsync | store.py:361-377 | same |
| `StoreError` class | store.py:58 | :58 |
| `read_events_raw` raises `StoreError` (invalid JSON / non-object) | store.py:544, raises 565/567 | :520, 541/543 |
| `_partition_event_stream_from_text` (`splitlines`, raises) | store.py:648, 670, 677/679/687/690/704 | :624, 646, 653/655/663/666/680 |
| `read_events` / `read_event_stream` | store.py:742 / 804 | :712 / 774 |
| `read_events_lenient` (never raises) | store.py:785 | :755 |
| `materialize_snapshot` calls both strict readers | reducer.py:397-400 | same |
| new on main: `seed_scratch_event_log` (#7276) | store.py:383-404 | — |
| `_infer_review_gates`: evidence inferred only when `None` and review handoff | transition_pipeline.py:133, 152-153, 174-176 | same |
| subtask hint overridden unconditionally when unforced handoff | transition_pipeline.py:155-173 | same |
| `prepare_transition` def | transition_pipeline.py:278 | :245 |
| `default_workspace_context` param / docstring (#946, WP02 D-2, DRIFT-3, operator decision 2026-09-07) | :290 / :322-337 | :257 / :289-304 |
| default synthesis `f"{execution_mode}:{repo_root or feature_dir}"` | :364-367 | :331-334 |
| new on main: `_wire_review_ref` (#7080, review_ref derived from `review_result.reference`) | :226-256 | — |
| `wp_state` ClaimedState guard "requires workspace context" | wp_state.py:455-456 | :341-342 |
| `wp_state` InProgress→FOR_REVIEW: subtasks then evidence `is not True` | wp_state.py:496-507 (evidence 502-506) | :388 |
| `TransitionInputs.workspace_context` / `.implementation_evidence_present` | wp_state.py:124/126 | :44/46 |
| `emit._infer_implementation_evidence[_from_event_stream]` = `any(e.wp_id == wp_id …)` | emit.py:541-548 | same |
| `emit._prepare_batch` passes `default_workspace_context=False` (docstring #946/DRIFT-3) | emit.py:1082, 1104-1111, 1127 | same |
| `emit_status_transition_batch` | emit.py:1138 | same |
| only callers of batch door: `_fallback_emit_batch` | coordination/status_transition.py:619, 646, 652 | — |
| CLI `agent status emit --workspace-context` | cli/commands/agent/status.py:405-411 (passes :514) | :352-358 (:458) |
| CLI `--implementation-evidence-present` | status.py:419-425 (passes :516) | :366-372 (:460) |
| orchestrator-api `transition` def | orchestrator_api/wp_lifecycle.py:837 | — |
| orchestrator-api `--implementation-evidence-present` | wp_lifecycle.py:853-854, passed :946 | :776-777, :865 |
| `TransitionRequest` / `GuardContext` (the only holders of these fields; never persisted) | models.py:1097 / 1147 (fields 1128-1130, 1157-1159) | — |
| core torn-tail precedent with manual remedy text | kernel/event_log_append.py:37-38, 122-125, 287-292 ("cut the bytes after its last newline") | — |

### A.2 Strict-read nuance

A torn tail breaks core's strict readers only when the fragment is invalid JSON. That is the normal crash shape: `JSONDecodeError` becomes `StoreError`.

A complete row that is merely missing its LF reads fine in core, because `splitlines()` handles it. That is exactly the case the service's `completed` action targets. `read_events_lenient` never raises.

### A.3 Major correction: the evidence flag cannot say "false" from any core door

The prior research (§3, §0) and the drafted M1 spec (US2, row at spec:40) both claim the evidence refusal is reachable "today via `--implementation-evidence-present=false`". **That is wrong.**

Both options are declared with a single name and a `None` default:

- `typer.Option("--implementation-evidence-present")` at status.py:419-425;
- `typer.Option(None, "--implementation-evidence-present")` at wp_lifecycle.py:853.

Typer then generates a flag only. There is no `--no-…` form, and `=false` is refused. I verified this:

- The real CLI (core venv, worktree `227cef16ec`) answers "No such option: --no-implementation-evidence-present".
- A typer 0.27.3 repro answers "Option does not take a value".

So the CLI can send only `True` or `None`. On every reachable `in_progress` WP the inference is `True` (a WP reaches `in_progress` only through a transition row), so `True` is the same as the inferred value.

`git grep 'implementation_evidence_present\s*=\s*(False|True)' core@main src` finds nothing. The refusal is therefore reachable only through the Python API, `emit_status_transition(..., implementation_evidence_present=False)` (emit.py:888 at the pin), and #6195 deletes those Python writers.

Consequences:

- **Dropping the flag at cut-over changes no CLI-visible behaviour.** #224 is not a C-003 regression.
- D3 (wire field) is still possible as additive parity with core's `TransitionRequest`, but US2's rationale and P1 priority must be rewritten.
- The `--implementation-evidence-present` help text promises nothing about `false`.

### A.4 The mirror case: #223 is reachable from the CLI, and cut-over would change it

`agent status emit … --to in_progress --workspace-context ""` (or `"   "`) is refused today:

- status.py does not normalise the value: there is no `strip` or `or None` between :428 and :520.
- The pipeline fills a default only when the context is `None` (transition_pipeline.py:365).
- The guard (wp_state.py:455) then refuses.

After #6195, `WorkPackageTransition` (core@main `runtime/next/event_bridge.py:61-75`: `write_location`, `rows` and `write_contract` only) carries no context. The service synthesises `<mode>:<root>` (`Guards.java:44-45`) and **accepts**.

This is a minor, malformed-input change, but it is the exact C-003 argument the spec makes for #224. It needs a ruling in M1, or a note in the core follow-up: core's client should refuse a blank `--workspace-context` locally, or core should normalise it.

### A.5 Service-version pin and handshake on core@main

These exist on main but not at the pin.

**Pins**

- `src/kernel/service_pin.py`:
  - `KNOWN_SERVICES` = write-service, read-service and charter-service (:60);
  - the write-service asset prefix is `command-cli` (:69);
  - placeholder tags are refused (:93).
- `pins/read-service.source.yaml`:
  - pins `service-v0.1.0-rc.1` at commit `2f5e0394…`, with `protocol_versions: [1]` and `requires_core '>=4.0.0rc8,<5'`;
  - pins `contract-bundles-0.1.0-rc.1`.
- `pins/README.md`: "The write service and the charter service are not pinned yet."
- `services/vendor.py`, `resolution.py` and `policies.py:56` (`WRITE_SERVICE`, launchable).

**Launcher and handshake**

- `services/launcher.py` (#6590). Its ready check is `_check_identity` (:300-307): `repositoryId` plus a common protocol major, and `instanceId`.
- **`serviceVersion` is parsed but never compared**: `write_service_protocol.py:438` is the only use.
- A running service from an older pin is therefore reused if it speaks protocol 1. Version gating is "core runs only the pinned binary", which breaks for a long-lived service across a core upgrade until its idle exit. A new optional field such as `implementationEvidencePresent` sent to an older running service answers `400 REQUEST_MALFORMED`.
- `STOP_TIMEOUT_S = 10.0` (launcher.py:83), then SIGTERM.

**Client shapes**

- `status/write_service_protocol.py` (#6675) holds core's typed client shapes.
- Its `TransitionUnit.to_wire` (:262-274) sends only `wpId`, `rows` and `context`, with **no `operation`**. That works because events made `operation` optional.
- `WriteRefusalCode` and `WriteRefusalReason` are closed enums (:108-212); `WriteRefusal.from_wire` (:477-484) checks the status code by code.
- **Any new refusal code would break core's parser.** Any new field or path needs a mirrored edit there (cross-repo whack-a-field).
- `API_PREFIX="/api/v1"` (:64): #6910 is fixed on main (`3dc4ca94f7`). The command-cli README at 333-335 ("core still calls bare paths") is therefore stale.

### A.6 Core issue #6195

- **State**: open; P2, `triage:needs-revision`, `domain:cli`, `domain:status`.
- **Scope**: `move-task`, `mark-status`, `status emit`, claim and implement become pass-throughs; Python writers deleted; retire `ALLOWED_CALLERS`; remove the step-3 bridge.
- **Done when**: CLI tests pass against the core; orchestrator-api contract tests unchanged (#5231); a mixed-version check (a log written by the previous release is read and extended).
- **Comments**: Ivan-Melck triage on 2026-10-14. stijn-dejongh on 2026-10-31 (ADR 2026-10-29-1 split): the Java half moved to events#155, and core keeps the client half (binding EventBridge to the pinned write service through the launcher, #6790, now closed, then deleting the Python appenders).
- **No client mapping for `--implementation-evidence-present` or `--workspace-context`.** No checklist item for torn-tail repair or rolling upgrade. The synthesis's "unverified" item stays unverified: the issue says nothing.
- Related: #6790 closed, #6675 closed, epic #6117 open.

## B. Events seam map (`62af21d0`)

### B.1 Torn-tail repair

**event-log `adapter/out/statuslog`** (`service/event-log/src/main/java/org/speckitty/missionstatus/adapter/out/statuslog/`)

- `LockedStatusLogSession.java`:
  - `currentBytes` is at 20-26;
  - `appendAndSync` is at 28-50 (the size re-check is at 40-43);
  - **`readAndCheckTail` is at 56-62** (throws `TornTailException` when the last byte is not LF);
  - **`writeAndSync` is at 64-73** (one `write`, `force`, then `syncDirectory` only for a new file);
  - `rollback` (truncate plus force) is at 75-84.
- `StatusLogAppender.java`:
  - `withMissionLock` is at 42-53;
  - the lock is `MissionLockPath.of(gcd, missionDirectory.getFileName())` at 43;
  - **`System.nanoTime()` is at :44** (the synthesis says :45; that is wrong);
  - the package-private ctor `(LedgerLock, FileOps)` is at 27-30.
- `StatusLogSession.java`: the port has `currentBytes()` and `appendAndSync()` only (8-12).
- `FileOps.java` (package-private) has `exists`, `read`, `openForAppend` and `syncDirectory`, plus `AppendChannel{size, write, force, truncate, close}` (8-31).
  - There is **no top-level truncate or force**, and no "open sibling file" method.
  - `JdkFileOps.openForAppend` is CREATE+WRITE+APPEND+NOFOLLOW (39-79), so it can serve the quarantine file too.
  - `truncate` on an APPEND channel is already used by `rollback`.
- `MissionLockPath.java:16-30` builds `<gcd>/spec-kitty-locks/<dirname>.status.lock`. Core uses the same formula at core@main `locking.py:183-199` (unchanged in substance pin→main).
- `TornTailException.java:6-13`: the message contains the host path.

**Precedent: `adapter/out/ledger/TornTailRepair.java`**

- quarantine `.torn` (:32), lock `<ledger>.lock` (:35), 10 s (:38);
- `repair` at 67-88; `completeLength` backward scan at 91-110;
- `System.Logger` at :44, and **it logs the ledger and quarantine host paths at WARNING (:85)**. The prior research claims it follows a "no paths" discipline; that is wrong.

**Boundaries**

- `EventLogBoundaryTest` (`service/event-log/src/test/.../architecture/EventLogBoundaryTest.java`):
  - `STATUSLOG_TYPES` at 65-66 lists three types that must exist;
  - `statusLogRule` at 101-103: statuslog may depend **only** on itself, ledger and `java..`. So no codec: **the "complete row vs fragment" decode must happen in command-cli**, through a callback or by returning the fragment;
  - `WRITE_OWNERSHIP` at 114-116;
  - only four packages are allowed (107-108).
- `FileWriteApis.java` (testFixtures, 16-40): `FileChannel.open` counts as a write API even for READ.
- `WriteOwnershipRulesTest.java:19-22` (command-cli): only `adapter.out.filesystem` may call those APIs. **No file IO for the repair is allowed in command-cli.** It must live in event-log statuslog.
- command-cli `ArchitectureRulesTest`:
  - onion at 41-55;
  - application may not use `java.nio.file`/`channels` (70-79) nor `org.speckitty.missionstatus` (82-90);
  - domain may use no IO (57-67).

**command-cli application** (`service/command-cli/src/main/java/org/speckitty/commandcli/`)

- `application/write/WriteService.java`:
  - catch-all at 59-65;
  - surface check at 70-72 (`coordination_ref_commit` → 422);
  - `find`/`RootUnverifiable`/`locationMatches` at 73-84;
  - `lockedWrite` at 126-141: deadline at :127, turn at :128-130, `withLock` at :131, LockResult switch at :133-139;
  - `insideLock` at 143-171;
  - `AppendVerifier` call at :167-168;
  - `input()` building `UnitInput` at 220-223.
- `WriteTurn.java:8-34` (fair `ReentrantLock`).
- `port/StatusLog.java:4-22`: `LockResult{Completed, LockHeld, PersistenceFailed}`, and a Session with no raw-bytes access.
- `port/MissionCatalog.java:6-20`.
- `adapter/out/statuslog/EventLogStatusLog.java:23-55`: `StatusLockHeldException` → LockHeld; `IOException` (incl. `TornTailException`) → PersistenceFailed.
- `AppendVerifier.java`: `decodeRows` 33-42 can classify a fragment; `firstUnreadable` 19-30.

**HTTP edge and wiring**

- `adapter/in/http/edge/Operations.java`: `WRITE` regex at :17, `isWrite` 22-24, `needsProtocol` 27-29.
  - Its **only** consumer is `ProtocolHeaderFilter.java:32`.
  - The body cap (`BodySizeFilter:20-42`, 1 MiB) applies to **every** path.
  - Draining is checked in the controller (`WriteController:64`), not in `Operations`. Prior §1.3's "`Operations` must learn the path for the body cap and draining" is partly wrong: only `needsProtocol` must learn it.
- `adapter/in/http/operations/WriteController.java`:
  - `@RestController`, routes at 37-53;
  - catch-all at 55-61;
  - draining at :64;
  - `refuse` adds `Retry-After` for `SERVICE_BUSY` (86-90).
- Routes are registered by component scan of `config` and `adapter.in.http` (`config/CommandCliApplication.java:7`). `ActivityFilter` is `/*`, ordered HIGHEST+4 (`OperationsWiring.java:20-34`), so a repair request holds the drain automatically.
- `config/ServiceWiring.java`:
  - **10 s budget at :74** (`Duration.ofSeconds(10)`, `System::nanoTime`);
  - root fallback at :72;
  - `ServiceWiringTest:22-33` asserts exactly 10 s.
- `adapter/out/mission/FilesystemMissionCatalog.java`: `root()` 31-33, `find` 36-, `locationMatches` 56-64 (coordination-routed / `checkout != "."` / `destinationRef` → false), `facts` 76-77.

**Test rigs**

- `WriteRig.java` (test `adapter/in/http/operations`):
  - start at 41-69;
  - `postMission` switch over unit, batch and annotation at **88-93** (whack-a-field);
  - `replaceMission` with a raw `initialLog` at 122-138 (can seed a torn tail);
  - `tree()` at 104-116 (asserts that nothing was created).
- `EdgeRig` and `RawClient` (`edge/`).
- `ContractSchemas.java:35-45`: `violations(schemaName, json)` validates against the split contract.
- `DrainTest.java:39-96`. It drives `finishDrain` indirectly through `POST /shutdown` with a fake `lifecycle.enter()` (:80-96). There is no real write, and no budget expiry over HTTP.
- `ServiceLifecycleTest`: budget expiry at 100-106.
- event-log `StatusLogAppenderTest`: torn tail refused at 114-122. `StatusLogFaultInjectionTest`'s `FaultyFileOps implements FileOps` (:209) is **broken by any new `FileOps` method**. `StatusLogInteropTest` holds the core lock interop at the pin.

### B.2 Contract (`contracts/mission-write/`)

**Files and indexes**

- `openapi.yaml`: version `1.0.1-SNAPSHOT` (:4). The `paths:` map at 34-46 holds 6 paths.
- `paths/*.yaml`: the transitions template shows the response map 400/401/403/404/409/413/422/500/503.
- `schemas/_index.yaml` and `responses/_index.yaml` list every file. `layout_check.py:15,196-211` enforces this, so a new schema must be indexed.

**CHANGELOG.md**

- `## 1.0.1-SNAPSHOT` at :6: Added :20, Changed :52, Removed :93, **Provisional :104**, Compatibility :116.
- Not released: the tags are only `service-v0.1.0-rc.1` and `rc.2`, both before 1.0.1. So M1 stays in 1.0.1-SNAPSHOT **unless an rc is cut first** (README:529-534 rule).

**Rules (`contracts/README.md`)**

- Response schemas are closed: 536-558. A new response status code on an existing path is breaking (`response-*-status-added`, `response-key-added`). **A new path with new schemas is additive.**
- Request-side optional additions are additive (:557-558).
- `breaking_check.py`:
  - Elements carrying `x-provisional` are dropped before comparing (`breaking_check.py:44, 216-222`).
  - **No workflow runs it** (README:582-586, #151). The same holds for `structure_check` (README:499), citation, provisional, enum and example checks.
  - CI runs only `bundle.py` (service.yml:166, docs-site.yml:168, sonar.yml:105) and the Gradle `validate_*`, `validateBundle_*` and `clientSmoke_*` tasks (`contracts/build.gradle:42-72`).
  - So "`breaking_check.py` clean" (FR-020) is a manual run (`--root contracts`), not a gate.
- `citation_check.py:3-33`: **every property needs `x-source{path,symbol}` or `x-derived{rule,inputs}`**. `x-source` paths are core paths (e.g. `TransitionUnit.yaml:29-37`). New fields with no core symbol (`implementationEvidencePresent` has none on core's client; the repair ack fields) need `x-derived`, or an `x-source` on core's `TransitionRequest.implementation_evidence_present` in `models.py`.
- `provisional_check.py:3-18`: an `x-provisional` element must appear by name in the CHANGELOG's `### Provisional` section.
- `enum_pins.json`: the mission-write entry pins `WriteOperation`, `WriteSurface` and `HealthStatus`. A new `action` enum would want a pin, by convention.
- `fixtures/example_check/required_examples.json` has no mission-write key.

**Tests reading the contract**

- `RefusalVocabularyTest:32` (`hasSize(16)` reasons).
- `OperationsTest:33` (javadoc "six operations"); `WRITE_SURFACES` at :220.
- `EdgeOrderTest:115,134` (target lists).
- Docs:
  - `docs-site.yml:280` loops over both modules and checks published == bundle;
  - `tests/test_docs_service_reference.py:25`;
  - `scripts/schema_pages_map.json` has **no** mission-write entry, so no new entry is needed;
  - starlight renders the bundle, so a new path appears automatically; the `docs-build` required check runs on `contracts/**`.

### B.3 #224: the evidence field

- `schemas/TransitionUnit.yaml`: `additionalProperties: false` (:11), required wpId/rows/context (12-15), properties 16-37.
- `WriteBodies.java`:
  - `UNIT_REQUIRED` and `UNIT_FIELDS` at 44-45;
  - strictness through `onlyFields` (:140); the detail text at :141 enumerates the members;
  - operation parse at 175-188;
  - typed unit at :171.
- `WriteRequest.UnitRequest(wpId, operation, rows)` at :27-32.
- `UnitInput(wpId, operation, rows)` at :7. Its constructor callers:
  - WriteService:221;
  - tests: CorpusCases:51, CorpusAuthorityCases:47, DiskMissionFactsParityTest:60, AuthorityTest:14, UnitJudgeTest:137/152, BatchJudgeTest:71/128, ClaimRulesTest:162.
  - Add a compact constructor overload to avoid red intermediates.
- `UnitJudge.judge` builds the guard context at :40.
- `Guards.context`:
  - signature at 30-31;
  - **evidence `wp.hasTransitions()` at :43** (derived even under force; the guard is skipped under force by `LaneMachine`);
  - **`executionMode + ":<root>"` at :44-45**.
- Guard order (subtasks → evidence) at 73-83 matches wp_state:496-506.
- `GuardContext` record at 4-14. Its constructor callers are Guards:45 and AuthorityFixtures:13/17/22/28.
- `GuardContextTest:38` asserts the evidence is True.
- `ReplayClassifier`: compares encoded rows only (40-51), so a request-only field does not affect replay. `Unit` carries the `UnitRequest` (:16).

**Corpus**

- `src/spec_kitty_events/conformance/status_transition_parity/`: 253 cases (239 domain, 14 protocol).
- `case.schema.json`: request.units.items allows only `wpId`, `operation` and `rows` (`additionalProperties: false`). A new key is needed. The `core_call.kwargs` shape is free.
- `unreachable.json`:
  - :2 is `workspace_context_required` and cites core `transition_pipeline.py:289-304` (pin lines, without a SHA);
  - :3-5 is `partial` / `implementation_evidence_required` (cites emit.py:541-548).
- `parity_corpus.py`: `load_unreachable` 147-153 and `load_partial_unreachable` 156-163 (an absent `partial` is fine).
- Packaged test `test_status_transition_parity.py`: reasons `== 16` at :61; the reachability algebra at 79-89 (`set(partial) <= refused`).

**Java corpus consumers**

- `ProtocolCorpusTest.java`:
  - `requestBody` at 181-199 copies only `wpId`, `operation` and `rows`, so it **must learn the new key**;
  - `everyGuardReasonOverHttp` at 54-71 runs **one** case per reason (`putIfAbsent`, first alphabetically);
  - a new `guard-implementation-evidence-…` case would sort first and replace the subtask-half representative;
  - report at 248-255: `GUARD_REASONS hasSize(15)` at :250 (a new case adds no reason), unreachable assert at :252, **`PASSED hasSize(34)` at :253**, and the printf at :254.
- `ParityCorpusTest.java:25-63` (domain, all cases); `CorpusCases.java:49-53` builds `UnitInput`.

**Generator** (`scripts/conformance/generate_transition_parity.py`)

- `ROW_CALL_KWARGS` at 41-56: transition kwargs lack `implementation_evidence_present`.
- Core call kwargs built at 1630-1674. `emit_status_transition` accepts the kwarg at the pin (emit.py:888, :968).
- Wire units are emitted at :1888 and :1906 with only `wpId`, `operation` and `rows`. The request at :1936-1940.
- reason-source extras at 1561-1568.
- Its tests: `tests/test_generate_transition_parity.py`. CI runs `--check` against the pin (service.yml write-core-parity).

**Description and docs**

- The "unreachable" wording appears in `WriteRefusalReason.yaml:21-22`, write-service-protocol.md:220-223 and command-cli/README.md:285-289.

### B.4 #223: workspace context

- `WriteRefusalReason.yaml:19-20` (the description); `x-provisional` at 46-49.
- `FilesystemMissionCatalog.root()` (31-33) is an `Optional<Path>`, resolved once in `ServiceWiring:71-72`.
- `Guards` is in pure `domain.authority` (no `java.nio`). The root would have to travel as a String through:
  - `MissionFacts` (port at 4-8; implemented by `DiskMissionFacts:12` and the test `CorpusMissionFacts:12`); or
  - a new `UnitInput` or context argument.
- The value is never persisted. Refusal details come from `DetailPolicy`, not the context. Core's real values are door-prefixed (`move-task:<root>`, `orchestrator-api:<root>`, `merge:<root>`, …, core@main `tasks_move_task_executor.py:285` and others), not `<mode>:<root>`, so "the real root" is still not core's value.
- Docs: write-service-protocol.md "Unreachable reasons" at :220-223; README :285-289.

### B.5 Drain budget (4.7)

- `ServiceLifecycle.java`:
  - `DRAIN_BUDGET = 5 s` at :19;
  - `finishDrain` at 80-99 uses `System.nanoTime` (:84, :87), not the injected `Clock`.
- Callers: `ServiceController.java:43-56` (:49) and `IdleTicker.java:29-43` (:34).
- `ProcessShutdown.stop` at 33-44 and `onSignal` at 47-52. `application.properties:19-21` caps the Boot shutdown phase at 2 s.

**Cross-repo constraint (not in the prior research)**

The stop timings are written in several places:

- **LAUNCHER.md:50-57**: "finishes the writes it holds (at most 5 s) … If the process is still running after 10 s … SIGTERM";
- core@main `launcher.py:83`: `STOP_TIMEOUT_S = 10.0`;
- README:293-301 and write-service-protocol.md:287-291;
- `LauncherContractProcessTest:66` ("NFR-003: exit within 10 s").

So the spec's FR-015 and NFR-004 (drain ≥ write budget + 5 s, so ≥ 15 s) **contradict the launcher contract**: core SIGTERMs at 10 s. Either change LAUNCHER.md and core's `STOP_TIMEOUT_S` together (contract plus core issue), or keep 5 s and refuse queued writes on drain. For the second option, waiters on the turn get `SERVICE_DRAINING` and the turn deadline is shortened once draining starts.

### B.6 Catch-alls and logging (4.14)

- The catch-alls are at `WriteService.java:59-65` and `WriteController.java:55-61`. command-cli/src/main has **no logger**.
- Precedent: `TornTailRepair.java:44,85` uses `System.Logger`, **and logs paths**.
- Logging stack: `spring-boot-starter-web` brings Logback and jul-to-slf4j. `System.Logger` goes to JUL, which Boot bridges to Logback, which writes to stdout; the launcher appends that to `service.log`.
- `ConfigPin.java:53-55` pins `logging.file.*` and `logging.config` empty (no file logging; do not add logging properties).
- `System.Logger` is `java.lang`, so the architecture rules allow it in application and adapter. Do not log in `domain.authority`; purity is not enforced for `java.lang`, but it is wrong there.
- Native image needs no hints for `System.Logger`.
- No existing test captures logs. Two options:
  - unit level: attach a `java.util.logging.Handler` to `Logger.getLogger(<class name>)`;
  - in a rig: spring-boot-test's `OutputCaptureExtension`.

### B.7 Wording items (4.12 and 4.15)

- `schemas/WriteOperation.yaml:1-18` has `x-derived` and no `x-provisional`. Adding one needs a CHANGELOG `### Provisional` bullet naming `WriteOperation` (provisional_check). `enum_pins.json` pins it.
- `WriteRefusalCode.yaml:49-50`: REQUEST_MALFORMED's description lacks the read-back (unreadable row) case.
- Step lists:
  - command-cli/README.md "The checks, in order" is at 108-141 (step 11 at :141). The read-back is described only under Durability at 165-168.
  - write-service-protocol.md: steps at 89-112 (11 at :112); Durability at 114-122.
- "after core #6195" text: **only** `docs/architecture/services/event-log-lock-and-append.md:142-143` (paragraph 140-146). README:151-154 and protocol.md:120-122 and :210-211 only link #222. The synthesis correction is confirmed.
- Operation tables that a new path must join:
  - README "HTTP surface" at 59-71;
  - write-service-protocol.md "Operations" at 225-238;
  - README "Binding notes" at 236-254.
- README:333-335 (#6910 "still calls bare paths") is stale, since #6910 is fixed on core main.

### B.8 Record

`kitty-specs/events-write-core-01M6TP6W/issue-matrix.json`:

```json
{"schema_version": 1, "rows": {"#NNN": {"evidence_ref", "fr", "nfr", "repo", "sc", "scope", "source_file", "title", "verdict", "wp"}}}
```

- Verdicts in use: `in-mission`, `deferred-with-followup`, `not-applicable`.
- The #223 and #224 rows are `deferred-with-followup`, with `evidence_ref` citing the maintainer ruling of 2026-11-08 (5).
- There is **no `#222` row**. A row in the same shape would be `"#222": {verdict: "deferred-with-followup", evidence_ref: "Follow-up: #222 … operator ruling 2026-10-31 …", rest null}`.

## C. Risks

### C.2 Places that enumerate fields, paths and counts (whack-a-field)

**New unit field**

- `TransitionUnit.yaml` plus a citation;
- `WriteBodies` 44-45 and :141;
- `WriteRequest.UnitRequest`, `UnitInput` (10 constructor sites), `Guards.context`, `GuardContext` (5 sites);
- `case.schema.json`, the generator (`ROW_CALL_KWARGS`, :1888/:1906), `ProtocolCorpusTest.requestBody` and `PASSED 34`, `CorpusCases`, `CorpusAuthorityCases`, `DiskMissionFactsParityTest`;
- `unreachable.json`;
- the CHANGELOG;
- **core `write_service_protocol.TransitionUnit`** (another repo).

**New path**

- `openapi.yaml` paths and `paths/`;
- schemas `_index`;
- `Operations.WRITE`/`needsProtocol`;
- the controller;
- `WriteRig.postMission`;
- `EdgeOrderTest` lists, `OperationsTest` ("six");
- README:59-71, protocol.md:231-238;
- the CHANGELOG;
- core's client (paths and shapes).

**New refusal code**

- `RefusalCode.java`, `WriteRefusalCode.yaml`, `RefusalVocabularyTest` (16 reasons);
- `ProtocolCorpusTest.fixedDetail`;
- **core's closed `WriteRefusalCode` enum would fail to parse it**.
- Avoid new codes: use idempotent `action: none` and the existing `STATUS_LOCK_HELD`, `SERVICE_DRAINING`, `WRITE_LOCATION_UNVERIFIED` and `STATUS_EVENT_PERSISTENCE_VERIFICATION_FAILED`.

**`FileOps` change**

- breaks `StatusLogFaultInjectionTest.FaultyFileOps:209`;
- a new public statuslog type should join `STATUSLOG_TYPES` (EventLogBoundaryTest:65-66).

**Drain constant**

- LAUNCHER.md, README, protocol.md, core `STOP_TIMEOUT_S`, NFR-003 test.

### C.3 Parallel authority

- A new statuslog repair must take `MissionLockPath`, which is the same lock core takes. Do **not** reuse `TornTailRepair` (it takes `<ledger>.lock`).
- The torn-tail classification (decode) cannot live in event-log statuslog (boundary rule). The quarantine and truncate cannot live in command-cli (`WriteOwnershipRulesTest`). Split the responsibility across the port.
- Do not loosen `currentBytes()` (C-006).

### C.4 Native image

- Follow `AckBody`'s ordered-map serialisation for the repair ack, and do not serialise records (`AckBody.java:10`). That way no reflection hints are needed.
- Hints live only in `CommandCliHints` (resources), and `reachability-metadata.json` holds only FFM downcalls. A new `@RestController` method in `adapter.in.http` is scanned. Keep `@RequestBody byte[]`, as `WriteController` does.
- `NativeSmokeTest:38-65` exercises only the unit path.

### C.5 Contract checks are not gates

`breaking_check`, citation, provisional, layout and structure are not in CI (#151). FR-020 "break check reports no break" needs an explicit manual run with recorded output.

### C.6 Contract version

1.0.1-SNAPSHOT is unreleased now. If a `service-v*` rc is cut before M1 merges, M1 must open 1.0.2-SNAPSHOT.

### C.7 Rolling upgrade

The launcher ignores `serviceVersion` and reuses any running protocol-1 service, so a newer CLI sending `implementationEvidencePresent` to an older resident service gets `400`. #6195 has no checklist item for this.

### C.8 Core is ahead of the pin

#7080 (`_wire_review_ref`) changes review_ref derivation on core@main, outside M1. Re-check parity when the pin moves.

## D. Corrections to the prior research and the synthesis

1. **#224 is "reachable today via `--implementation-evidence-present=false`": FALSE.** The flag-only option cannot express false (§A.3). This weakens D3's C-003 rationale and drafted spec US2 (P1).
2. **#223 is CLI-reachable** via an empty `--workspace-context` (§A.4). Cut-over would silently accept it, which is the overlooked C-003 delta.
3. **4.7 fix "drain = write + 5 s"** conflicts with LAUNCHER.md:52-56 and core `STOP_TIMEOUT_S=10.0` (§B.5).
4. **4.14 "same discipline as TornTailRepair, no paths": wrong.** `TornTailRepair.java:85` logs paths.
5. **§1.3 "Operations must learn the path for body cap and draining": partly wrong.** Only `needsProtocol` must; the cap is global and draining is per controller.
6. **"DrainTest never calls finishDrain"**: it does, indirectly through `/shutdown` (:80-96), but never with a real write or a budget expiry.
7. **Line drift**:
   - StatusLogAppender nanoTime is at :44 (the synthesis says :45);
   - pipeline evidence inference is at 174-176 (the prior research said 176-183/180-182);
   - `default_workspace_context` docstring at core@main is 322-337;
   - ProtocolCorpusTest asserts at 250-254 (adds `PASSED 34`).
8. **Prior §3.3 "could not verify a pin"**: core@main now has `service_pin.py`, `pins/`, the launcher and vendor, but no write-service pin yet and no `serviceVersion` check (§A.5).
9. **Synthesis correction 1 (only event-log-lock-and-append.md:142-143 says "after #6195"): confirmed.**
