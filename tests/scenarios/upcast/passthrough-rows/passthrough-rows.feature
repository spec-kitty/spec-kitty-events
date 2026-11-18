# Maintainer notes (not part of the behaviour description):
# - A pass-through case pins the issue codes that explain why the row was not mapped; the
#   steps check the codes against that pin and never restate them.
# - The cli group pins no pass-through case, so it has no scenario here.
# - Promises are indexed in tests/scenarios/upcast/PROMISES.md.

Feature: A row the upcaster cannot map passes through unchanged with its pinned codes
  As a consumer reading a legacy event log
  I want a row that cannot be mapped left as it was, with the codes that say why
  So that no row is lost and no row is silently rewritten

  Background:
    Given the upcast corpus shipped with the package

  @promise:PR-EVENTS-UPCAST-004 @jvm
  Scenario: Every conformance envelope pinned as a pass-through is left as it was
    Given every case of the group "conformance" pinned as "passthrough"
    When each case's line is upcast with its caller context
    Then every result has the outcome, form and codes its case pins
    And every passed-through line is left as it was

  @promise:PR-EVENTS-UPCAST-005 @jvm
  Scenario: Every unrecognised status-diary line is left as it was
    Given every case of the group "diary" pinned as "passthrough"
    When each case's line is upcast with its caller context
    Then every result has the outcome, form and codes its case pins
    And every passed-through line is left as it was
