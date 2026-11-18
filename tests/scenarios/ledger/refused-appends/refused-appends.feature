# Maintainer notes (not part of the behaviour description):
# - Each refusal document pins its refusal class in expected_error (LedgerValidationError or
#   LedgerTornTailError); the steps check the class against that pin and never restate it.
# - Promises are indexed in tests/scenarios/ledger/PROMISES.md.

Feature: A refused append leaves the ledger byte-identical
  As a binding that writes a local event ledger
  I want an append the contract refuses to raise before anything is written
  So that a refusal never corrupts or creates a ledger

  Background:
    Given the conformance fixtures shipped with the package

  @promise:PR-EVENTS-LEDGER-002
  Scenario: Every refused append raises its pinned refusal and leaves the ledger as it was
    Given every fixture of the class "ledger_append" that pins a refusal
    When each fixture's input is appended to its ledger
    Then every fixture is refused with the error code it pins
    And every ledger is left byte-identical to its prior state
