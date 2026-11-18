# Maintainer notes (not part of the behaviour description):
# - Each fixture pins its codes (expected_error_codes); the steps check the codes against
#   that pin and never restate them.
# - Promises are indexed in tests/scenarios/compatibility/PROMISES.md.

Feature: The strict profile refuses what the removed cutover gate refused
  As a consumer that must fail closed across the 8.0.0 cutover boundary
  I want the strict journal profile to refuse those envelopes with the codes their fixtures pin
  So that the checks the removed gate made still have one fail-closed home

  Background:
    Given the cutover-boundary fixtures shipped with the package

  @promise:PR-EVENTS-COMPATIBILITY-002
  Scenario: Every envelope on the rejected side of the cutover boundary is refused by the strict profile
    Given every cutover-boundary fixture under "rejected_by_strict_profile"
    When each fixture's input is judged against the strict journal profile
    Then every fixture is refused with exactly the error codes it pins
