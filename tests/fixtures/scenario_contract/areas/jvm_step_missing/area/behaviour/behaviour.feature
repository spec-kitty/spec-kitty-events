Feature: An area behaviour

  @promise:PR-EVENTS-AREA-001 @jvm
  Scenario: Claims the JVM, but one step has no Java definition
    Given a thing
    And the value "x"
    When it happens
    Then it holds
