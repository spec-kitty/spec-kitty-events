Feature: A scenario whose first step is a bullet

  @promise:PR-EVENTS-FIXTURE-001
  Scenario: pytest-bdd refuses a leading * step
    * a thing
    Given a thing
