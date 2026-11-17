# Maintainer notes (not part of the behaviour description):
# - lane_mapping_legacy holds both outcomes: a legacy alias or canonical lane that resolves,
#   and a legacy lane with no mapping, which is refused. Each fixture pins its own outcome.
# - Promises are indexed in tests/scenarios/envelope/PROMISES.md.

Feature: A legacy lane resolves as its fixture pins
  As a consumer replaying events written before the lane vocabulary settled
  I want each legacy lane name to resolve to its canonical lane, or be refused when it has none
  So that old status rows land in the lane they always meant

  Background:
    Given the conformance fixtures shipped with the package

  @promise:PR-EVENTS-ENVELOPE-008
  Scenario: Every legacy lane mapping resolves as its fixture pins
    Given every fixture of the class "lane_mapping_legacy"
    When each fixture's input is resolved as a legacy lane mapping
    Then every verdict is the one its fixture pins
