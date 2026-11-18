# Maintainer notes (not part of the behaviour description):
# - Driven by the manifest's status_diary/replay entries: every replay_stream with its
#   like-named "-output" reducer_output golden. An unpaired stream or golden fails the listing.
# - Promises are indexed in tests/scenarios/status/PROMISES.md.

Feature: A status diary replays to its recorded output
  As a reader of a status diary
  I want every replay to fold to the same state, whatever order its rows arrive in
  So that every reader of the same diary sees the same work-package state

  Background:
    Given every status-diary replay stream with its recorded output

  @promise:PR-EVENTS-STATUS-REPLAY-001
  Scenario: A diary replayed in its recorded order gives its recorded output
    When each stream is reduced in its recorded order
    Then every stream reduces to its recorded output

  @promise:PR-EVENTS-STATUS-REPLAY-002
  Scenario: A diary replayed in another order gives the same output
    When each stream is reduced in reverse and in a shuffled order
    Then every stream reduces to its recorded output
