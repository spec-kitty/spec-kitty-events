# `compatibility/` promises

Each row maps a `@promise:` id to the guarantee its scenario asserts, the requirement that states
it, and the binding that runs it. The fixtures and the manifest stay the single source of truth.

JVM binding: none

| Promise | Guarantee | Requirement | Binding |
|---------|-----------|-------------|---------|
| `PR-EVENTS-COMPATIBILITY-001` | `validate_event` accepts every envelope on the accepted side of the cutover boundary (missing or pre-cutover `schema_version`, legacy `feature/` aggregate prefix, envelope-level legacy key): the cutover gate is gone from it. | `COMPATIBILITY.md` `8.0.0`; `cutover_boundary/accepted_by_validate_event/`; #199 | python |
| `PR-EVENTS-COMPATIBILITY-002` | The strict journal profile refuses every envelope on the rejected side of the cutover boundary with exactly the error codes its fixture pins: fail-closed gating lives there. | `COMPATIBILITY.md` `8.0.0`; `spec_kitty_events.strict.validate_strict_envelope`; `cutover_boundary/rejected_by_strict_profile/`; #199 | python |
