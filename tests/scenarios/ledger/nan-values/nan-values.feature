# Maintainer notes (not part of the behaviour description):
# - JSON cannot hold NaN, so a document names the input field (nan_field) the runner sets to
#   an IEEE-754 NaN before appending; it pins the refusal in expected_error.
# - Promises are indexed in tests/scenarios/ledger/PROMISES.md.

Feature: An append carrying NaN is refused
  As a binding that writes a local event ledger
  I want a record holding NaN refused instead of written
  So that every ledger line stays strict JSON every reader can parse

  Background:
    Given the conformance fixtures shipped with the package

  @promise:PR-EVENTS-LEDGER-004
  Scenario: An append whose input carries NaN is refused
    Given every fixture of the class "ledger_append" that sets a field to NaN
    When each fixture's input is appended to its ledger
    Then every fixture is refused with the error code it pins
    And every ledger is left byte-identical to its prior state
