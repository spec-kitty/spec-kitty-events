# Maintainer notes (not part of the behaviour description):
# - A torn tail is a non-empty ledger whose last byte is not LF; the documents that pin
#   LedgerTornTailError carry such an existing_ledger.
# - Promises are indexed in tests/scenarios/ledger/PROMISES.md.

Feature: An append to a ledger with a torn tail is refused
  As a binding that writes a local event ledger
  I want an append refused when the ledger's last line is torn
  So that a new line is never glued onto a partial one

  Background:
    Given the conformance fixtures shipped with the package

  @promise:PR-EVENTS-LEDGER-003
  Scenario: An append to a ledger whose last byte is not LF is refused
    Given every fixture of the class "ledger_append" that pins the error code "LedgerTornTailError"
    When each fixture's input is appended to its ledger
    Then every fixture is refused with the error code it pins
    And every ledger is left byte-identical to its prior state
