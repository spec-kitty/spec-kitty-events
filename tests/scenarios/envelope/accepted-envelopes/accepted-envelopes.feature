# Maintainer notes (not part of the behaviour description):
# - The steps name a fixture class; the envelopes live in the packaged fixtures, never here.
# - Promises are indexed in tests/scenarios/envelope/PROMISES.md.

Feature: A canonical envelope is accepted
  As a consumer of the event contract
  I want every envelope the contract calls canonical to be accepted
  So that a conforming producer is never refused

  Background:
    Given the conformance fixtures shipped with the package

  @promise:PR-EVENTS-ENVELOPE-001
  Scenario: Every canonical envelope is accepted
    Given every fixture of the class "envelope_valid_canonical"
    When each fixture's input is judged as an envelope
    Then every fixture is accepted

  @promise:PR-EVENTS-ENVELOPE-002
  Scenario: Every envelope synthesized from a historical row is accepted
    Given every fixture of the class "envelope_valid_historical_synthesized"
    When each fixture's input is judged as an envelope
    Then every fixture is accepted
