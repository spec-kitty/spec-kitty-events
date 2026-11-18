# Maintainer notes (not part of the behaviour description):
# - The steps select ledger_append documents by what they pin; each pins its own existing_ledger
#   bytes and expected_line, so the lines live in the packaged fixtures, never here.
# - The byte rule is normative in conformance/fixtures/ledger_append/README.md.
# - Promises are indexed in tests/scenarios/ledger/PROMISES.md.

Feature: An accepted append adds exactly the line its fixture pins
  As a binding that writes a local event ledger
  I want every accepted append to add one line with the exact bytes the contract pins
  So that every binding writes ledgers that are byte-for-byte interchangeable

  Background:
    Given the conformance fixtures shipped with the package

  @promise:PR-EVENTS-LEDGER-001
  Scenario: Every accepted append adds exactly the pinned line after the prior bytes
    Given every fixture of the class "ledger_append" that pins acceptance
    When each fixture's input is appended to its ledger
    Then every fixture is accepted
    And every ledger holds its prior bytes followed by exactly the line its fixture pins
