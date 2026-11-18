# Maintainer notes (not part of the behaviour description):
# - Driven by the producer-row fixtures of the status event types (lane.transitioned.v1,
#   wp.innerstate.changed.v1) under producer_contract/fixtures/rows/: every structured actor of
#   a valid fixture, and every structured actor an invalid fixture refuses (its pinned error
#   path is the actor). The violation names are the fixtures' own "violation" values.
# - Promises are indexed in tests/scenarios/status/PROMISES.md.

Feature: The structured status actor has four keys, or five with profile_source
  As a reader of status rows
  I want a structured actor kept whole, and an inconsistent one refused
  So that a resolved agent binding survives the status log without silent corruption

  @promise:PR-EVENTS-STATUS-ACTOR-001
  Scenario: A four-key actor is kept whole
    Given every structured status actor with 4 keys that its fixture accepts
    When each actor is decoded as a status actor
    Then every actor decodes to itself, every key kept
    And the producer contract's actor schema gives the same verdict

  @promise:PR-EVENTS-STATUS-ACTOR-002
  Scenario: A five-key actor keeps its profile_source
    Given every structured status actor with 5 keys that its fixture accepts
    When each actor is decoded as a status actor
    Then every actor decodes to itself, every key kept
    And the producer contract's actor schema gives the same verdict

  @promise:PR-EVENTS-STATUS-ACTOR-003
  Scenario: A profile_source whose null-ness differs from the profile's is refused
    Given every structured status actor its fixture refuses for "profile_source_nullness"
    When each actor is decoded as a status actor
    Then every actor is refused
    And the producer contract's actor schema gives the same verdict

  @promise:PR-EVENTS-STATUS-ACTOR-004
  Scenario: A profile_source outside the closed vocabulary is refused
    Given every structured status actor its fixture refuses for "enum_violation"
    When each actor is decoded as a status actor
    Then every actor is refused
    And the producer contract's actor schema gives the same verdict
