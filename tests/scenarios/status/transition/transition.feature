# Maintainer notes (not part of the behaviour description):
# - Driven by conformance/status_transition_parity/ (cases/, case.schema.json, unreachable.json)
#   and by spec_kitty_events.status.validate_transition (the 11.5.0 core edge graph, #237).
# - The legal edges are the ones the corpus accepts from an unforced request; the steps never
#   list them. Their illegal twins are every other lane pair.
# - PR-EVENTS-STATUS-TRANSITION-007 applies a request to a prior log, which only the services
#   (service/command-cli, service/event-log) do. It is @wip until the jvm step source (#283)
#   lands; its two last steps have no Python binding (see PROMISES.md).
# - Promises are indexed in tests/scenarios/status/PROMISES.md.

Feature: A status transition is acknowledged or refused as the parity corpus pins
  As a binding that writes status transitions
  I want the same edge graph and the same refusals as every other binding
  So that a transition one binding acknowledges is never refused by another

  @promise:PR-EVENTS-STATUS-TRANSITION-001
  Scenario: Every parity case is well formed
    Given every status-transition parity case
    Then every case conforms to the corpus case schema

  @promise:PR-EVENTS-STATUS-TRANSITION-002
  Scenario: Every refusal is named from the closed vocabularies
    Given every status-transition parity case the corpus pins as a refusal
    Then every refusal pins a write-refusal code and a reason from the closed vocabularies

  @promise:PR-EVENTS-STATUS-TRANSITION-003
  Scenario: Every refusal reason is reached or explained
    Given every status-transition parity case the corpus pins as a refusal
    Then every refusal reason is reached by a case or has its cause recorded as unreachable

  @promise:PR-EVENTS-STATUS-TRANSITION-004
  Scenario: The legal edges are exactly the ones the corpus accepts
    Given the lane edges the parity corpus accepts from an unforced request
    When every pair of lanes is validated as a transition that meets every guard
    Then exactly the edges the corpus accepts pass the unforced edge check

  @promise:PR-EVENTS-STATUS-TRANSITION-005
  Scenario: Every illegal twin is refused unless forced
    Given the lane edges the parity corpus accepts from an unforced request
    When every pair of lanes is validated as a transition that meets every guard
    Then every other pair is refused unforced with an edge violation
    And forcing any pair lifts the edge check

  @promise:PR-EVENTS-STATUS-TRANSITION-006
  Scenario: A request the corpus refuses as illegal is refused by the edge graph
    Given every parity case refused as an illegal transition from one unforced request
    When each case's requested lane pair is validated unforced
    Then validate_transition refuses every requested pair

  @promise:PR-EVENTS-STATUS-TRANSITION-007
  @wip @wip-owner:stijn-dejongh @wip-since:2026-11-18
  Scenario: The services acknowledge or refuse every request as its case pins
    Given every status-transition parity case
    When each case's request is applied to its prior log
    Then every outcome is the acknowledgement or refusal its case pins
