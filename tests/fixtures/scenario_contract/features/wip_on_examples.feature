Feature: @wip and its owner and date on an Examples block

  @promise:PR-EVENTS-FIXTURE-001
  Scenario Outline: Rows
    Given the value "<value>"

    @wip @wip-owner:someone @wip-since:2026-11-25
    Examples:
      | value |
      | one   |
