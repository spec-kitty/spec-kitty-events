# Maintainer notes (not part of the behaviour description):
# - The goldens are read from the packaged corpus; reading one again must not change a byte.
# - Promises are indexed in tests/scenarios/upcast/PROMISES.md.

Feature: Upcasting a golden CloudEvent again changes nothing
  As a consumer that may read a log twice
  I want a row that is already a CloudEvent passed through byte for byte
  So that upcasting is idempotent

  Background:
    Given the upcast corpus shipped with the package

  @promise:PR-EVENTS-UPCAST-006 @jvm
  Scenario Outline: Every golden of the group "<group>" is a pass-through of itself
    Given every case of the group "<group>" pinned as "mapped"
    When each case's golden is upcast again with its caller context
    Then every result is a pass-through of its golden with the same bytes

    Examples:
      | group       |
      | cli         |
      | conformance |
      | diary       |
