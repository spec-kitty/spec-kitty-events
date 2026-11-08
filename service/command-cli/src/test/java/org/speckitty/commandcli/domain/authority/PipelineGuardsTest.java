package org.speckitty.commandcli.domain.authority;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.speckitty.commandcli.domain.service.RefusalCode;
import org.speckitty.commandcli.domain.service.RefusalReason;
import org.speckitty.missionstatus.domain.model.Lane;

class PipelineGuardsTest {
  @Test
  void unknownLaneNamesOriginalValue() {
    Refusal refusal = PipelineGuards.unknownLane(Map.of("from_lane", "planned", "to_lane", "in-review")).orElseThrow();
    assertThat(refusal.code()).isEqualTo(RefusalCode.TRANSITION_REJECTED);
    assertThat(refusal.reason()).isEqualTo(RefusalReason.UNKNOWN_LANE);
    assertThat(refusal.message()).isEqualTo("Unknown lane: in-review");
    assertThat(PipelineGuards.unknownLane(Map.of("from_lane", "not-a-lane", "to_lane", "in-review")).orElseThrow().message())
        .isEqualTo("Unknown lane: not-a-lane");
    assertThat(PipelineGuards.unknownLane(Map.of("from_lane", "planned", "to_lane", "done"))).isEmpty();
  }

  @Test
  void staleFromLaneIsProtocolRefusalWithoutUrn() {
    Refusal refusal = PipelineGuards.staleFromLane(Lane.PLANNED, Lane.CLAIMED).orElseThrow();
    assertThat(refusal.code()).isEqualTo(RefusalCode.TRANSITION_REJECTED);
    assertThat(refusal.reason()).isNull();
    assertThat(refusal.message()).isEqualTo("The unit's from_lane is not the work package's current lane.");
    assertThat(PipelineGuards.staleFromLane(Lane.PLANNED, Lane.PLANNED)).isEmpty();
  }

  @Test
  void forcedReviewExitNeedsNoteUnlessVerdictOrMigration() {
    Map<String, Object> row = AuthorityFixtures.row("in_review", "planned");
    row.put("force", true);
    Refusal refusal = PipelineGuards.forceNoteRequired("WP01", Lane.IN_REVIEW, row).orElseThrow();
    assertThat(refusal.reason()).isEqualTo(RefusalReason.FORCE_NOTE_REQUIRED);
    assertThat(refusal.message()).isEqualTo("WP01 cannot leave in_review by force without a note. "
        + "--force requires a non-blank --note explaining why the review lane is being left; as an operator pass "
        + "`--force --note <why>` (`--reason <why>` on `agent status emit`).");
    row.put("actor", "migration:rebuild");
    assertThat(PipelineGuards.forceNoteRequired("WP01", Lane.IN_REVIEW, row)).isEmpty();
    row.put("actor", "operator");
    row.put("review_result", AuthorityFixtures.reviewResult("changes_requested", "ref"));
    assertThat(PipelineGuards.forceNoteRequired("WP01", Lane.IN_REVIEW, row)).isEmpty();
    row.remove("review_result");
    row.put("reason", "rework");
    assertThat(PipelineGuards.forceNoteRequired("WP01", Lane.IN_REVIEW, row)).isEmpty();
    row.put("reason", " ");
    assertThat(PipelineGuards.forceNoteRequired("WP01", Lane.PLANNED, row)).isEmpty();
  }

  @Test
  void doneEvidenceRunsForEveryTargetAndUsesPythonTruthiness() {
    for (Object evidence : new Object[] {Map.of(), Map.of("review", "bad")}) {
      assertDoneEvidenceRefused(evidence);
    }
    for (String field : List.of("reviewer", "verdict", "reference")) {
      for (Object falsy : new Object[] {null, "", false, BigInteger.ZERO, List.of(), Map.of()}) {
        Map<String, Object> review = AuthorityFixtures.reviewResult("approved", "ref");
        review.put(field, falsy);
        assertDoneEvidenceRefused(Map.of("review", review));
      }
      Map<String, Object> blankReference = AuthorityFixtures.reviewResult("approved", "ref");
      blankReference.put(field, field.equals("reference") ? " \t\n" : "  ");
      if (field.equals("reference")) {
        assertDoneEvidenceRefused(Map.of("review", blankReference));
      } else {
        assertThat(PipelineGuards.doneEvidence(Map.of("review", blankReference))).isEmpty();
      }
    }
    assertThat(PipelineGuards.doneEvidence(null)).isEmpty();
    assertThat(PipelineGuards.doneEvidence(AuthorityFixtures.approvalEvidence("approved", "ref"))).isEmpty();
  }

  private static void assertDoneEvidenceRefused(Object evidence) {
    Refusal refusal = PipelineGuards.doneEvidence(evidence).orElseThrow();
    assertThat(refusal.reason()).isEqualTo(RefusalReason.DONE_EVIDENCE_INCOMPLETE);
    assertThat(refusal.message()).isEqualTo(
        "Moving to done requires evidence with review.reviewer review.verdict, and review.reference");
  }
}
