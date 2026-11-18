# Maintainer notes (not part of the behaviour description):
# - The folder holds full lifecycles, an event after a terminal state, V1 interview outcomes,
#   a deferred decision reopened by a resolve, and unfoldable terminal combinations; each
#   stream pins its own output, anomalies included.
# - Promises are indexed in tests/scenarios/replay/PROMISES.md.

Feature: A decision point stream folds to the state its fixture pins
  As a consumer projecting decisions from an event log
  I want every decision point replay stream to fold to the state the contract pins
  So that every binding reaches the same decision, and reports the same anomalies

  Background:
    Given the conformance fixtures shipped with the package

  @promise:PR-EVENTS-REPLAY-002
  Scenario: Every decision point replay stream folds to its pinned state
    Given every replay stream in the fixture folder "decisionpoint/replay"
    When each stream is folded by the "decision point" reducer
    Then every fold is the state its fixture pins
