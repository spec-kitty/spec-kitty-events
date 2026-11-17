# Maintainer notes (not part of the behaviour description):
# - Each refusal class pins its error code in its fixtures (expected_error_code); the steps
#   check the code against that pin and never restate it.
# - Promises are indexed in tests/scenarios/envelope/PROMISES.md.

Feature: An invalid envelope is refused with the code its fixture pins
  As a consumer of the event contract
  I want an invalid envelope refused with a stable error code
  So that I can tell why it was refused without reading the validator

  Background:
    Given the conformance fixtures shipped with the package

  @promise:PR-EVENTS-ENVELOPE-003
  Scenario: An envelope carrying a forbidden legacy key is refused
    Given every fixture of the class "envelope_invalid_forbidden_key"
    When each fixture's input is judged as an envelope
    Then every fixture is refused with the error code it pins

  @promise:PR-EVENTS-ENVELOPE-004
  Scenario: An envelope moving to a lane outside the vocabulary is refused
    Given every fixture of the class "envelope_invalid_unknown_lane"
    When each fixture's input is judged as an envelope
    Then every fixture is refused with the error code it pins

  @promise:PR-EVENTS-ENVELOPE-005
  Scenario: An envelope whose payload fails its schema is refused
    Given every fixture of the class "envelope_invalid_payload_schema"
    When each fixture's input is judged as an envelope
    Then every fixture is refused with the error code it pins

  @promise:PR-EVENTS-ENVELOPE-006
  Scenario: An envelope of the wrong shape is refused
    Given every fixture of the class "envelope_invalid_shape"
    When each fixture's input is judged as an envelope
    Then every fixture is refused with the error code it pins
