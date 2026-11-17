Feature: A promise on an Examples block is checked for duplicates too

  Scenario Outline: Rows carry their own promises
    Given the value "<value>"

    @promise:PR-EVENTS-FIXTURE-001
    Examples:
      | value |
      | one   |

    @promise:PR-EVENTS-FIXTURE-002
    Examples:
      | value |
      | two   |

  @promise:PR-EVENTS-FIXTURE-001
  Scenario: Reuses the first block's promise
    Given a thing
