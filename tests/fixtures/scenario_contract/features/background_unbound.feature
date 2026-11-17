Feature: An unbound Background step

  Background:
    Given a background step nothing binds

  @promise:PR-EVENTS-FIXTURE-001
  Scenario: The Background is checked with the scenario
    Given a thing
