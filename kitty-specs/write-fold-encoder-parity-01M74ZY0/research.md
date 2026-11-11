# Research: Write-fold and reference-encoder parity (M2)

Core read statically at `main` `22a6a730` and at the pin `512567ee` (service/core-pin.json). This
session cannot execute core's code (the permission classifier refuses it), so every core-derived
expectation is either read from core's source or verified by CI's `write-core-parity` job, which
runs core at the pin.

## Re-verified references

| Claim (#231 / #237) | Where it is now |
|---|---|
| Fold treats a null id as the setter | `service/command-cli/.../domain/authority/WriteFold.java:132` (`id()` = `pyText(event_id)` → `"None"`), `TransitionFold.java:45` stores it as `last_event_id`, `:73` looks it up, `:84` guards only the spelling `"null"`, which never occurs. |
| Core finds no setter for `None` | `src/spec_kitty_events/diary.py:1454-1458` `_find_event_by_id` (core's `status/reducer.py` imports this package's reducer: `from spec_kitty_events.diary import State, reduce_parsed`). `_same_timestamp_verdict` (`diary.py:1493`) and arm (ii) (`diary.py:1631`) both skip when the setter is `None`. |
| Two clocks | `WriteService.java:122` deadline from the injected `nanoClock`; `service/event-log/.../StatusLogAppender.java:55` remaining from `System.nanoTime()`. |
| Ack cases assert no bytes | `ProtocolCorpusTest.java:145-155`; domain cases are filtered out of `protocolCases` (`:51`), and only one representative per guard reason runs over HTTP. |
| UV_PYTHON assertion missing | `tests/test_service_workflows.py:160-180`; the workflow has the prefix at `.github/workflows/service.yml:631,635`. |
| No process rule in command-cli | `service/command-cli/src/test/java/.../architecture/ArchitectureRulesTest.java`; the read service's rule 10 is `service/src/test/java/.../architecture/ArchitectureRules.java:195`. |
| Pin purpose stale | `service/core-pin.json`: "CI-only peers: lock interop and the nightly corpus oracle (ADR 0002 D4)". |
| Encoder: tuples, `except ValueError`, docstring | `src/spec_kitty_events/status_log.py:61-66, 121-131`; `src/spec_kitty_events/__init__.py` inventory. |
| 3.11+ native `fromisoformat` | `src/spec_kitty_events/_iso8601.py:421-423`. |
| Edge matrix | `src/spec_kitty_events/status.py:592-622, 698-713`. |

## Counts re-derived (FR-018)

The corpus holds **258** cases today (not 253): 86 domain ack, 5 service ack, 158 domain refusal,
8 service refusal, 1 edge refusal. #231's "83 domain-level ack cases" and #237's "88 of 253" are
both stale; this mission asserts appended bytes for all **91** ack cases (86 + 5).

## Decisions

### D-1 Id-less transitions mirror core (M2-R1)
- **Decision**: model identity explicitly (`TransitionId.Identified` / `TransitionId.Absent`); an
  absent-id setter is no setter.
- **Rationale**: core's `if event_id is None: return None` is an explicit guard with its own
  docstring ("or None if unset/absent"), not an accident. The divergence principle says match it.
  The operator asked for the empty case to be its own constructor, not a null check.
- **Alternatives**: refuse null ids on read (diverges from core, would need a core issue); keep a
  sentinel string (the bug: `"None"` collides with a real `"None"` id).

### D-2 Differential expectations from the package reducer
- **Decision**: committed JSON cases, expectations generated with `spec_kitty_events.diary` and
  re-checked by a pytest; the Java fold test reads the same files.
- **Rationale**: the package reducer *is* core's reducer, so this is a core-parity oracle with no
  core dependency.

### D-3 One clock
- **Decision**: inject the nano clock into `StatusLogAppender`; production passes `System::nanoTime`.
- **Alternatives**: pass a relative remaining budget (would change a shared public signature that
  the read side may also use).

### D-4 Encoder refusals
- **Decision**: `IntegerTooLarge(StatusRowRefused)` and `NonStringKey(StatusRowRefused)`, exported.
  Keys are validated recursively before `json.dumps`, so `sort_keys` never raises `TypeError`.
- **Why core differs**: core would raise a bare `ValueError`/`TypeError`; these inputs are
  unreachable from `json.loads`, so typing the refusal changes no reachable byte.

### D-5 Timestamps: the 3.11 port everywhere
- **Decision**: `parse_iso8601_datetime` always calls `_parse_isoformat_311`.
- **Rationale**: core runs 3.11; 3.13's native parser differs on `+00:00:00.000001` and on 31 of
  60,000 fuzzed forms (15 on 3.11 itself, because of `normalize_iso8601_shape`). The port is the
  core-exact behaviour on every interpreter.

### D-6 Edge graph from core at the pin (D4)
Core's `wp_state.py` `allowed_targets()` (unforced edges):

| from | to |
|---|---|
| genesis | planned, canceled |
| planned | claimed, blocked, canceled |
| claimed | in_progress, blocked, canceled |
| in_progress | for_review, approved, planned, blocked, canceled |
| for_review | in_review, blocked, canceled |
| in_review | approved, done, in_progress, planned, blocked, canceled |
| approved | done, in_progress, planned, blocked, canceled |
| blocked | in_progress, canceled |
| done, canceled | — |

The implementer re-reads `wp_state.py` at `512567ee` (the pin; `main` changed the file by 128
lines) and records any difference. Removed from the package: `for_review → approved|done|in_progress|planned`,
`in_review → for_review`, `genesis → blocked`, `blocked → blocked`. The package's `(None, planned)`
initial edge has no core counterpart (core has no `None` lane) and stays. Force keeps bypassing the
edge check (core: `check_transition`), guards are unchanged.

### D-7 Ack bytes (M2-R2)
- **Decision**: committed `ack-bytes.json` test resource plus `extract_ack_bytes.py` (`--check` in
  `write-core-parity`).
- **Rationale**: the operator wants the expectations stored here with no hard coupling; the
  extraction tool keeps them honest against core through the existing CI-only pin.
- **Caveat**: initial values come from the reference encoder, core-exact for these rows by the
  committed `status_append` oracle; the CI `--check` is the proof from core itself.

### D-8 Version
11.4.0 → 11.5.0 (M2-R3), in `pyproject.toml` and `__version__`; sibling sessions informed.

## Supply chain
No dependency is added, upgraded or removed; DIRECTIVE_051 controls are not triggered.

## Findings during implementation

Recorded by WP07 from each work package's report and review (review-cycle files under `tasks/`).

### Rulings taken during implementation

- **DM-01M758AR0XV801HVGA8ZPQWATR (operator, 2026-11-11): Full D4.** Aligning
  `validate_transition` with core's graph conflicted with two packaged valid fixtures and the
  "force-exempt by contract" text for review-ref-gated rewinds (#48), and `reduce_status_events`
  (which calls `validate_transition`) would start recording such events as `TransitionAnomaly`.
  The operator chose core's graph everywhere. Result (WP04): the two fixtures became forced moves
  under the same ids, with unforced invalid twins (`min_version` 11.5.0); a third fixture,
  `zeitgeist_attrs/valid/wp_status_changed_done_drops_evidence.json`, was rerouted
  `for_review → in_review` to keep its unforced, evidence-dropping intent; COMPATIBILITY 11.5.0
  records the reducer behaviour change.

### Per work package

- **WP01 (4.16, 4.17).** `TransitionId` (sealed `Identified` / `Absent`, factory-only). Core's
  `_find_event_by_id` guard is deliberate, so the fold mirrors it. Nine differential cases, with
  expectations from `spec_kitty_events.diary.reduce` (core's reducer). Both directions are shown:
  the old fold *dropped* events core applies (`idless-setter-stale-forward-applies`,
  `idless-setter-claimed-to-in-progress`), *refused* a log core reduces (`idless-and-string-none`),
  and, across a log, *acknowledged* a `claimed → in_progress` core drops
  (`idless-setter-cascade-acknowledged-core-drops`); two identified controls. A same-timestamp
  id-less case cannot reach the rollback arm: the tie falls to the id comparison, which is
  unorderable in core (`TypeError`), so `idless-same-timestamp-unorderable` pins "both refuse".
  The case shape gained `expected: {"unreducible": "TypeError"}` for such logs. The non-finite
  log id (`NaN`, `Infinity`, `1e400`) answers `500 STATUS_EVENT_PERSISTENCE_VERIFICATION_FAILED`
  with the log unchanged.
- **WP02 (4.8).** `StatusLogAppender(LongSupplier)`; command-cli passes its `nanoClock` bean; the
  repair path uses the same adapter. Reverting to `System.nanoTime()` fails 3 of the 5 new tests.
- **WP03 (4.1, 4.2, 4.4, 4.5).** Tuples recurse and stay tuples (core's `_strip_pii_recursive`
  does the same). New refusals `IntegerTooLarge`, `NonStringKey`, `UnsupportedValue`, checked on
  the *sanitised* value, so a dropped session field holding a huge int is accepted as in core.
  Non-string keys are refused at every depth (core would coerce them; unreachable from
  `json.loads`; documented). The CPython 3.11 port is used on every interpreter: on
  `+00:00:00.000001` it gives offset 0 on 3.10, 3.11 and 3.13, while 3.13's native parser gives
  1 µs. A 200,000-form fuzz showed 0 differences between the port and native 3.11. Byte dumps of
  the status_append fixtures are identical on 3.10.20, 3.11.15 and 3.13.18 (NFR-001). Review
  cycle 1 caught a sign-counting bug in the digit limit (a negative 4300-digit int was refused);
  fixed and pinned.
- **WP04 (4.3, 4.6).** Core's graph at the pin equals D-6 above (no drift between the pin and core
  `main` for `allowed_targets()`). Removed unforced edges: `for_review → approved|done|in_progress|planned`,
  `in_review → for_review`, `genesis → blocked`, `blocked → blocked`; none added. The Java binding
  has no transition validator, so it needed only the version line. 11.4.0 → 11.5.0.
- **WP05 (4.9).** 91 ack cases (86 domain + 5 service); **89 run over HTTP with appended-bytes
  assertions**. Two are explicitly excluded, each with its observed refusal asserted:
  `dependency-no-tasks-file-accepted` (the service answers `404 WP_NOT_FOUND` before the
  authority, a pre-existing service rule from mission `events-write-core-01M6TP6W` FR-018, whereas
  core's `emit_status_transition` accepts it: core's dependency reader returns no declared dependencies when zero
  `tasks/` files match the WP id, so there is nothing to refuse; read statically at a core checkout (`22a6a730`, later
  than the `512567ee` pin, so unverified at the pin and tracked in #256) and
  `annotation-subtasks-complete-unlocks-review`
  (a batch whose first unit is an annotation; the wire's `TransitionBatch` takes transition units
  only, so `400 REQUEST_MALFORMED`). NFR-002 / SC-004 ("100% of ack cases") are therefore met for
  89 of 91; the two gaps are pre-existing service behaviours, not introduced here, and are filed
  as follow-ups rather than changed in this mission. `ack-bytes.json` was produced in encoder mode;
  CI's `write-core-parity` step (`extract_ack_bytes.py --check`, core mode) is its proof from core.
- **WP06 (4.10, 4.11, 4.13).** Positive controls: removing either `UV_PYTHON` prefix fails the
  workflow test; the planted `ProcessBuilder` class fails the new rule and a planted
  `ProcessHandle` reader passes it.

### Environment notes

- Core's code cannot run in this session (permission classifier); core was read statically at
  the pin and at `main`.
- The local Gradle build needs a session-local Gradle home: the environment's init script reads
  plugin markers from a Central mirror whose copies differ from the Gradle Plugin Portal's pinned
  in `gradle/verification-metadata.xml`.
- `cd site && npm run validate` cannot run here: the doc toolkit comes from npm.pkg.github.com,
  which answers 401. CI's `docs-build` is the docs gate for this PR.
- `tests/test_version_not_amended.py` fails in this shallow clone (no release tags), with and
  without the mission's changes.
