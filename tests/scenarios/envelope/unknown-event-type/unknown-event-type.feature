# Maintainer notes (not part of the behaviour description):
# - The strict journal profile is the check that knows the registered event types
#   (spec_kitty_events.strict.validate_strict_envelope); the fixtures it picks are those of
#   the envelope_strict_journal class that pin UNKNOWN_EVENT_TYPE.
# - Promises are indexed in tests/scenarios/envelope/PROMISES.md.

Feature: An unknown event type is refused
  As a consumer of the event contract
  I want an envelope of an event type the contract does not register to be refused
  So that the contract fails closed instead of passing on what it cannot judge

  Background:
    Given the conformance fixtures shipped with the package

  @promise:PR-EVENTS-ENVELOPE-007
  Scenario: An envelope of an unregistered event type is refused
    Given every fixture of the class "envelope_strict_journal" that pins the error code "UNKNOWN_EVENT_TYPE"
    When each fixture's input is judged against the strict journal profile
    Then every fixture is refused with the error code it pins
