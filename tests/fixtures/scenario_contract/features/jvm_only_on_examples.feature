Feature: @jvm-only on an Examples block

  @promise:PR-EVENTS-FIXTURE-001
  Scenario Outline: Rows
    Given the value "<value>"

    @jvm-only
    Examples:
      | value |
      | one   |
