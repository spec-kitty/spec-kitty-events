# Maintainer notes (not part of the behaviour description):
# - Strict use raises the first code a case pins; a row that is already a CloudEvent
#   (ALREADY_CLOUDEVENT) is never refused.
# - Promises are indexed in tests/scenarios/upcast/PROMISES.md.

Feature: Strict use refuses a row with the first code its case pins
  As a producer that must not write a row it cannot map
  I want strict use to refuse such a row with the first code its case pins
  So that a strict caller fails on the same rows, with the same code, in every binding

  Background:
    Given the upcast corpus shipped with the package

  @promise:PR-EVENTS-UPCAST-007 @jvm
  Scenario Outline: Strict use refuses exactly the cases of the group "<group>" that pin a code
    Given every case of the group "<group>"
    When each case's line is upcast in strict use
    Then strict use refuses every case with the first code it pins and upcasts every other case

    Examples:
      | group       |
      | cli         |
      | conformance |
      | diary       |
