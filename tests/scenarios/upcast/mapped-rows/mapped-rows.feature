# Maintainer notes (not part of the behaviour description):
# - The steps name a corpus group; the lines, contexts and goldens live in the packaged corpus
#   (producer_contract/fixtures/upcast/cases/<group>/), never here.
# - @jvm: Cucumber-JVM binds the same steps against the Java Upcaster (java/, run by verifyAll).
# - Promises are indexed in tests/scenarios/upcast/PROMISES.md.

Feature: A legacy row maps to the CloudEvent its case pins
  As a consumer reading a legacy event log
  I want every legacy row the corpus pins as mapped to become that exact CloudEvent
  So that every binding reads the same history into the same bytes

  Background:
    Given the upcast corpus shipped with the package

  @promise:PR-EVENTS-UPCAST-001 @jvm
  Scenario: Every CLI producer fixture's legacy row maps to its golden CloudEvent
    Given every case of the group "cli" pinned as "mapped"
    When each case's line is upcast with its caller context
    Then every result has the outcome, form and codes its case pins
    And every mapped row's canonical bytes equal its golden
    And every golden's read-profile validity is the one its case pins

  @promise:PR-EVENTS-UPCAST-002 @jvm
  Scenario: Every mapped conformance envelope maps to its golden CloudEvent
    Given every case of the group "conformance" pinned as "mapped"
    When each case's line is upcast with its caller context
    Then every result has the outcome, form and codes its case pins
    And every mapped row's canonical bytes equal its golden
    And every golden's read-profile validity is the one its case pins

  @promise:PR-EVENTS-UPCAST-003 @jvm
  Scenario: Every mapped status-diary line maps to its golden CloudEvent
    Given every case of the group "diary" pinned as "mapped"
    When each case's line is upcast with its caller context
    Then every result has the outcome, form and codes its case pins
    And every mapped row's canonical bytes equal its golden
    And every golden's read-profile validity is the one its case pins
