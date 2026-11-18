# Maintainer notes (not part of the behaviour description):
# - The reducers order events themselves (ordering.canonical_order_key), so the order rows
#   arrive in must not change a fold. Each stream is folded reversed and under fixed shuffle
#   seeds, and compared with the same pinned output as in file order.
# - Promises are indexed in tests/scenarios/replay/PROMISES.md.

Feature: The order rows arrive in does not change a fold
  As a consumer that receives events out of order
  I want a stream to fold to the same state whatever order its rows arrive in
  So that a projection never depends on delivery order

  Background:
    Given the conformance fixtures shipped with the package

  @promise:PR-EVENTS-REPLAY-003
  Scenario Outline: Every replay stream folds to its pinned state with its rows reordered
    Given every replay stream in the fixture folder "<folder>"
    When each stream is folded by the "<reducer>" reducer with its rows shuffled
    Then every fold is the state its fixture pins

    Examples:
      | folder               | reducer        |
      | lifecycle/replay     | lifecycle      |
      | decisionpoint/replay | decision point |
