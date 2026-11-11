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
