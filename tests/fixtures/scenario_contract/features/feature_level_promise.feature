@promise:PR-EVENTS-FIXTURE-001
Feature: A promise on the Feature tags every scenario under it

  Background:
    Given a thing

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
