Feature: A manual promise

  @promise:PR-EVENTS-FIXTURE-001
  @manual @manual-reason:needs-an-operator-run-peer
  Scenario: Specified, but CI cannot run it
    Given a thing
    When a step only an operator can take
    Then it holds
