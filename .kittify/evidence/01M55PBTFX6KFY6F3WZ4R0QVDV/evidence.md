# Issue #113 verification — SubtaskStatus repoint identity compatibility

**Date:** 2026-10-17
**Scope:** Confirm no consumer repo relies on the broken `isinstance(<Lane value>, Status)` /
`Status is Lane` identity semantics after spec-kitty-events 10.6.0 repointed the public
`Status` alias in `spec_kitty_events.diary` from `Lane` to the new `SubtaskStatus` enum.

The change preserves value-equality (`SubtaskStatus.DONE == Lane.DONE` is still `True`) and
`str()`. The only behavioural tooth: `isinstance(Lane.DONE, Status)` was `True`, now `False`.
Risk is limited to a consumer doing an identity/type check of a *lane* value against the
diary `Status` alias.

## Method
Parallel read-only research squad (one agent per real consumer), ripgrep over `.py`, plus
version-pin inspection. Real consumers = repos declaring a `spec-kitty-events` dependency,
excluding SHADOW_CLONES/ and the sk-nightly-reds snapshot (throwaway copies of spec-kitty).

## Findings — all CLEAN

### spec-kitty (the notable consumer; spec-kitty#5953)
- Pin: `pyproject.toml` `spec-kitty-events>=10.5.1,<11`; uv.lock resolves 10.5.1 (ceiling
  admits 10.6.0 on a future resolve — analysis covers that case too).
- Defines its OWN local `Lane`, `Status` (`Status = Lane`), and `SubtaskStatus` in
  `src/specify_cli/status/models.py:29,114,120` — an independent mirror, NOT imported from
  events.
- No file imports `Status` from `spec_kitty_events`. Every `Status` token resolves to the
  local `Status = Lane`.
- Exactly one events-`Lane` import: `.../agent/tasks_transition_core.py:264`
  (`from spec_kitty_events.status import ... Lane`), used construction-only
  (`Lane(old_lane)`), no `isinstance`/`is`/`issubclass`/`type(...) is` against it.
- All `is Lane` / `isinstance(..., Lane)` hits target the LOCAL Lane — unaffected.
- The re-exported `SubtaskStatus` is itself locally authored here (not yet sourced from
  events), consistent with spec-kitty#5953 not yet landed; still decoupled from the new
  events enum. When #5953 bumps the pin and re-exports the events-sourced enum, value
  equality covers it and no `isinstance(lane, events.Status)` check exists.

### spec-kitty-runtime
- Pin: `spec-kitty-events==3.3.0` (uv.lock 3.0.0) — the diary/SubtaskStatus API does not
  exist at this pin.
- Imports only from `spec_kitty_events.mission_next` (event-type constants, payload models,
  RuntimeActorIdentity). Tokens `diary`, `SubtaskStatus`, `Lane`, `Status` appear nowhere in
  `.py`. Zero identity/type checks against them.

### spec-kitty-saas
- Pin: `spec-kitty-events==6.1.0` — predates the change.
- Never imports `Status`/`SubtaskStatus`/the diary module. `Lane` imported only from
  `spec_kitty_events.status`. The only identity checks are `isinstance(x, Lane)` against
  `Lane` itself (projections.py:282, test_projections.py:57-58) — stay `True` regardless of
  the diary alias change. All other `Status` tokens are unrelated Django model enums.

## Verdict
No consumer relies on the broken identity. #113 closed as verified; no code change needed in
spec-kitty-events. Follow-up #114 (collapse the two independent `Lane` enums) remains open.
