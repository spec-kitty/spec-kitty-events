Feature: An outline row that binds nothing

  @promise:PR-EVENTS-FIXTURE-001
  Scenario Outline: The expanded text is matched
    Given the value "<value>"

    Examples:
      | value     |
      | two words |
