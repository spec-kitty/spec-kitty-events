# Maintainer notes (not part of the behaviour description):
# - Driven by conformance/fixtures/status_append/ (valid/, invalid/; rules in its README.md).
#   Each fixture pins its bytes (expected_line) or its refusal class (expected_error); the steps
#   check against that pin and never restate it.
# - PR-EVENTS-STATUS-APPEND-003 is bound twice: by pytest-bdd against the Python encoder and by
#   Cucumber-JVM against service/event-log's StatusRowEncoder (StatusAppendSteps.java; the
#   :event-log runner of tests/scenarios/jvm-runners.json), so both bindings write the same bytes.
# - Promises are indexed in tests/scenarios/status/PROMISES.md.

Feature: A status row is appended as the exact bytes its fixture pins, or refused
  As a binding that writes status.events.jsonl
  I want every row sanitized and encoded to the same bytes as every other binding
  So that a status log written by one binding is byte-identical to one written by another

  @promise:PR-EVENTS-STATUS-APPEND-001
  Scenario: A valid row is appended as the bytes its fixture pins
    Given every status-append fixture pinned as "valid"
    When each fixture's row is appended
    Then every line written is the exact bytes its fixture pins

  @promise:PR-EVENTS-STATUS-APPEND-002
  Scenario: An invalid row is refused with the refusal its fixture pins
    Given every status-append fixture pinned as "invalid"
    When each fixture's row is appended
    Then every append is refused with the refusal its fixture pins

  @promise:PR-EVENTS-STATUS-APPEND-003 @jvm
  Scenario: Every binding appends every row as its fixture pins
    Given every status-append fixture
    When each fixture's row is appended
    Then every append writes the bytes or raises the refusal its fixture pins
