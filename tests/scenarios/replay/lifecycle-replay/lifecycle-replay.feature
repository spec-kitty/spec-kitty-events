# Maintainer notes (not part of the behaviour description):
# - Each <stem>.jsonl stream in the folder is paired with the <stem>_output.json it pins; the
#   rows and the state live in the packaged fixtures, never here.
# - The lifecycle stream carries a duplicated event id and an event type the reducer ignores;
#   the pinned output already reflects both.
# - Promises are indexed in tests/scenarios/replay/PROMISES.md.

Feature: A mission lifecycle stream folds to the state its fixture pins
  As a consumer projecting mission state from an event log
  I want every lifecycle replay stream to fold to the state the contract pins
  So that every binding projects the same mission from the same events

  Background:
    Given the conformance fixtures shipped with the package

  @promise:PR-EVENTS-REPLAY-001
  Scenario: Every lifecycle replay stream folds to its pinned state
    Given every replay stream in the fixture folder "lifecycle/replay"
    When each stream is folded by the "lifecycle" reducer
    Then every fold is the state its fixture pins
