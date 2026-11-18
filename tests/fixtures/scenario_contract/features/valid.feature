Feature: A clean feature

  Background:
    Given a thing

  @promise:PR-EVENTS-FIXTURE-001
  Scenario: Every step is bound once
    Given the value "seven"
    When it happens
    Then it holds
    And it holds

  @promise:PR-EVENTS-FIXTURE-002
  @wip @wip-owner:someone @wip-since:2026-11-17 @defect:197
  Scenario Outline: A draft outline
    Given the value "<value>"
    Then it holds

    Examples:
      | value |
      | one   |
      | two   |
