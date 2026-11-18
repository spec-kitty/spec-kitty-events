package org.speckitty.commandcli.domain.authority.scenarios;

import static org.assertj.core.api.Assertions.assertThat;

import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.speckitty.commandcli.domain.authority.CorpusAuthorityCases;
import org.speckitty.commandcli.domain.authority.Judgement;
import org.speckitty.commandcli.domain.authority.Refusal;

/**
 * Cucumber-JVM steps of the {@code status/transition} JVM-only promise (spec-kitty-events#270, operator ruling on #289):
 * every domain-layer parity case's request, applied to its prior log by command-cli's authority (the write fold and its
 * guards), gives the acknowledgement or refusal the case pins. There is no Python write fold, so no Python binding. The
 * later-layer cases (replay, log decoding, Mission discovery) need the HTTP service and stay in {@code ProtocolCorpusTest}.
 * Test-only: no step code enters the service's production sources.
 */
public class TransitionSteps {
  private record Applied(CorpusAuthorityCases.Case testCase, Judgement judgement) {}

  private static final Set<String> REQUIRED_REASONS = Set.of(
      "unknown_lane", "illegal_transition", "force_requires_actor_and_reason", "actor_required",
      "implementation_evidence_required", "reason_required", "review_ref_required",
      "reviewer_approval_required", "review_evidence_invalid", "force_note_required", "done_evidence_incomplete");

  private List<CorpusAuthorityCases.Case> cases = List.of();
  private List<Applied> applied = List.of();

  @Given("every domain-layer status-transition parity case")
  public void everyDomainLayerParityCase() {
    cases = CorpusAuthorityCases.load();
    // The same floors as ParityCorpusTest, so the scenario never passes on a shrunken corpus.
    long legalEdges = cases.stream()
        .filter(c -> c.area().equals("lane_machine") && "ack".equals(c.expected().get("result"))).count();
    assertThat(legalEdges).as("acknowledged lane_machine cases (the legal edges)").isGreaterThanOrEqualTo(29);
    for (String reason : REQUIRED_REASONS) {
      assertThat(cases.stream().filter(c -> ("urn:spec-kitty:write-refusal:" + reason).equals(c.expected().get("reason"))).count())
          .as(reason).isPositive();
    }
    assertThat(cases.stream().filter(c -> c.area().equals("stale")).count()).as("stale cases").isPositive();
  }

  @When("each case's request is applied to its prior log")
  public void eachRequestIsApplied() {
    List<Applied> results = new ArrayList<>();
    for (CorpusAuthorityCases.Case testCase : cases) {
      results.add(new Applied(testCase, testCase.judge()));
    }
    applied = results;
  }

  @Then("every outcome is the acknowledgement or refusal its case pins")
  public void everyOutcomeAsPinned() {
    assertThat(applied).hasSameSizeAs(cases).isNotEmpty();
    for (Applied result : applied) {
      String id = result.testCase().id();
      Map<String, Object> expected = result.testCase().expected();
      if ("ack".equals(expected.get("result"))) {
        assertThat(result.judgement()).as(id).isInstanceOf(Judgement.Accepted.class);
        continue;
      }
      assertThat(result.judgement()).as(id).isInstanceOf(Judgement.Refused.class);
      Refusal refusal = ((Judgement.Refused) result.judgement()).refusal();
      assertThat(refusal.code().name()).as(id + " code").isEqualTo(expected.get("code"));
      assertThat(refusal.reason() == null ? null : refusal.reason().urn()).as(id + " reason")
          .isEqualTo(expected.get("reason"));
      if ("core".equals(result.testCase().oracle())) {
        assertThat(refusal.message()).as(id + " message").isEqualTo(expected.get("message"));
      }
      Object unit = expected.get("unit");
      assertThat(refusal.unitIndex()).as(id + " unit").isEqualTo(unit instanceof Number number ? number.intValue() : null);
    }
  }
}
