# `envelope/` promises

Each row maps a `@promise:` id to the guarantee its scenario asserts, the requirement that states
it, and the binding that runs it. The fixtures and schemas stay the single source of truth.

JVM binding: none

| Promise | Guarantee | Requirement | Binding |
|---------|-----------|-------------|---------|
| `PR-EVENTS-ENVELOPE-001` | Every canonical envelope is accepted. | Class `envelope_valid_canonical` (`conformance/README.md`, eight-class taxonomy); #197 | python |
| `PR-EVENTS-ENVELOPE-002` | Every envelope synthesized from a historical row is accepted. | Class `envelope_valid_historical_synthesized`; #197 | python |
| `PR-EVENTS-ENVELOPE-003` | An envelope carrying a forbidden legacy key is refused with `FORBIDDEN_KEY`. | `spec_kitty_events.forbidden_keys`; class `envelope_invalid_forbidden_key`; #197 | python |
| `PR-EVENTS-ENVELOPE-004` | An envelope moving to a lane outside the vocabulary is refused with `UNKNOWN_LANE`. | `Lane`, `LANE_ALIASES`; class `envelope_invalid_unknown_lane`; #197 | python |
| `PR-EVENTS-ENVELOPE-005` | An envelope whose payload fails its schema is refused with `PAYLOAD_SCHEMA_FAIL`. | Typed payload models; class `envelope_invalid_payload_schema`; #197 | python |
| `PR-EVENTS-ENVELOPE-006` | An envelope of the wrong shape is refused with `ENVELOPE_SHAPE_INVALID`. | `spec_kitty_events.models.Event`; class `envelope_invalid_shape`; #197 | python |
| `PR-EVENTS-ENVELOPE-007` | The strict journal profile refuses an envelope of an unregistered event type (`UNKNOWN_EVENT_TYPE`): a strict journal fails closed. The plain envelope verdict does not judge event types. | `spec_kitty_events.strict.validate_strict_envelope` (`STRICT_EVENT_TYPES`); class `envelope_strict_journal`; #197 | python |
| `PR-EVENTS-ENVELOPE-008` | A legacy lane resolves to the canonical lane its fixture pins, or is refused with `UNKNOWN_LANE` when it has no mapping. | `LANE_ALIASES`; class `lane_mapping_legacy`; #197 | python |
