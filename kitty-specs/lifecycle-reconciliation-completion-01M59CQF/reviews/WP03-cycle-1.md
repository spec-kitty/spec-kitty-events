# WP03 review feedback (cycle 1) — changes requested

## MAJOR

1. **The reconciliation record makes a false claim about the timeout event.** `docs/architecture/lifecycle-reconciliation.md` (`## mission_next`) says "Core also persists two decision events … the timeout event (`events.py:96` …, named `DecisionTimeoutExpired` on the wire)". Against spec-kitty d881f7b4:
   - Core persists only `SignificanceEvaluated` (`engine.py:494`, via `_append_event`).
   - For the timeout, core has only the payload model (`significance.py:506`) and an emitter-protocol method (`events.py:96`). The default `NullEmitter.emit_decision_timeout_expired` (`events.py:178`) is a no-op, and nothing in core `src/` writes a timeout event to the log.
   - The string `DecisionTimeoutExpired` appears nowhere in core, so there is no wire name; the package chose it.

   This page exists to be an accurate record, so this has to be fixed.
   **Fix:** state that core persists `SignificanceEvaluated`, and that the timeout has a payload model and an emitter seam but no persisted event or wire name today, so `DecisionTimeoutExpired` is the package's name. Make the same correction in `CHANGELOG.md` [11.1.0] ("mirroring what core persists" → "mirroring core's payload models"), in `COMPATIBILITY.md` if it echoes the claim, and in `docs/events/review-audit-decisions.md` if it does. Consider adding a follow-up: "core: persist the timeout event under the package name `DecisionTimeoutExpired`."

## Verified OK

- Every other citation was spot-checked against spec-kitty d881f7b4 and resolves exactly: `pyproject.toml:80`, `mission_payload.py:30`, `lifecycle_events.py:661/830/966/77-93`, `engine.py:26/494`, `events.py:17`, `schema.py:15/79-84`, `significance.py:485/506`, `status_lanes.py:8`, `mission_state.py:1320-1340`, plus the local `strict.py:144` and `lifecycle.py:24`. No core consumer of `mission_audit` exists, as the page says.
- There are no absolute local paths. The frontmatter description is 166 characters.
- The R2 narrowing is stated in the doc, CHANGELOG and COMPATIBILITY. The minor classification is justified by additivity and the 6.1.0 precedent.
- The version is `11.1.0` in pyproject, `__init__`, uv.lock and README. The uv.lock diff is the package version line only, and `uv lock --check` passes. The `>=10.5.1,<11` pin appears only in the doc's correction note.
- Gates: mypy --strict clean, ruff check and format clean, conformance 795 passed, schemas show no drift, `make test-fast` 3908 passed / 0 failed.
