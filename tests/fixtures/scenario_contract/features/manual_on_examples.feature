Feature: @manual on an Examples block

  @promise:PR-EVENTS-FIXTURE-001
  Scenario Outline: Rows
    Given the value "<value>"

    @manual @manual-reason:needs-a-macos-host
    Examples:
      | value |
      | one   |
