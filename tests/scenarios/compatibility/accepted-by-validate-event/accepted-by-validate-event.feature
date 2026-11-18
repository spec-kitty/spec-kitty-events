# Maintainer notes (not part of the behaviour description):
# - The envelopes live in conformance/fixtures/cutover_boundary/accepted_by_validate_event/;
#   the event type and the expected result come from each fixture's manifest.json row.
# - Promises are indexed in tests/scenarios/compatibility/PROMISES.md.

Feature: The plain validator accepts what the removed cutover gate refused
  As a consumer upgrading across the 8.0.0 cutover-gate removal
  I want validate_event to accept the envelopes the removed gate used to refuse
  So that fail-closed gating is the strict profile's job, as COMPATIBILITY.md states

  Background:
    Given the cutover-boundary fixtures shipped with the package

  @promise:PR-EVENTS-COMPATIBILITY-001
  Scenario: Every envelope on the accepted side of the cutover boundary is accepted
    Given every cutover-boundary fixture under "accepted_by_validate_event"
    When each fixture's envelope is validated as the event type its manifest row names
    Then every fixture is accepted, as its manifest row pins
