package org.speckitty.commandcli.domain.authority;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.speckitty.commandcli.domain.service.RefusalReason;
import org.speckitty.missionstatus.domain.model.Lane;

class GuardsTest {
  @Test
  void plannedClaimActorPrecedesDependencies() {
    GuardContext blankBoth = AuthorityFixtures.with(AuthorityFixtures.context(), "", false);
    refusal(Lane.PLANNED, Lane.CLAIMED, blankBoth, RefusalReason.ACTOR_REQUIRED, "Transition requires actor identity");
    GuardContext dependencies = AuthorityFixtures.with(AuthorityFixtures.context(), "actor", false);
    refusal(Lane.PLANNED, Lane.CLAIMED, dependencies, RefusalReason.DEPENDENCIES_UNSATISFIED,
        "Transition planned -> claimed blocked: unsatisfied dependencies (force with reason to override)");
    assertThat(Guards.check(Lane.PLANNED, Lane.CLAIMED, AuthorityFixtures.context())).isEmpty();
  }

  @Test
  void claimedStartWorkspacePrecedesDependenciesAndNullDependenciesPass() {
    GuardContext blankBoth = AuthorityFixtures.changing(
        AuthorityFixtures.with(AuthorityFixtures.context(), "actor", false), "", true, true, "reason", null, null, null);
    refusal(Lane.CLAIMED, Lane.IN_PROGRESS, blankBoth, RefusalReason.WORKSPACE_CONTEXT_REQUIRED,
        "Transition claimed -> in_progress requires workspace context");
    GuardContext dependencies = AuthorityFixtures.with(AuthorityFixtures.context(), "actor", false);
    refusal(Lane.CLAIMED, Lane.IN_PROGRESS, dependencies, RefusalReason.DEPENDENCIES_UNSATISFIED,
        "Transition claimed -> in_progress blocked: unsatisfied dependencies (force with reason to override)");
  }

  @Test
  void reviewHandoffRequiresTrueSubtasksThenTrueEvidence() {
    for (Boolean value : new Boolean[] {null, false}) {
      GuardContext incomplete = AuthorityFixtures.changing(AuthorityFixtures.context(), "workspace", value, false, "reason", null, null, null);
      refusal(Lane.IN_PROGRESS, Lane.FOR_REVIEW, incomplete, RefusalReason.IMPLEMENTATION_EVIDENCE_REQUIRED,
          "Transition in_progress -> for_review requires completed subtasks or force with reason");
    }
    GuardContext noEvidence = AuthorityFixtures.changing(AuthorityFixtures.context(), "workspace", true, null, "reason", null, null, null);
    refusal(Lane.IN_PROGRESS, Lane.FOR_REVIEW, noEvidence, RefusalReason.IMPLEMENTATION_EVIDENCE_REQUIRED,
        "Transition in_progress -> for_review requires implementation evidence or force with reason");
  }

  @Test
  void approvalNeedsCompleteApprovedEvidence() {
    refusal(Lane.IN_PROGRESS, Lane.APPROVED, AuthorityFixtures.context(), RefusalReason.REVIEWER_APPROVAL_REQUIRED,
        "Transition to approved/done requires evidence (reviewer identity and approval reference)");
    for (String verdict : new String[] {"changes_requested", "approved"}) {
      GuardContext evidence = AuthorityFixtures.changing(AuthorityFixtures.context(), "workspace", true, true, "reason", null,
          AuthorityFixtures.approvalEvidence(verdict, "ref"), null);
      Optional<Refusal> result = Guards.check(Lane.APPROVED, Lane.DONE, evidence);
      if (verdict.equals("approved")) assertThat(result).isEmpty();
      else assertThat(result).get().extracting(Refusal::reason).isEqualTo(RefusalReason.REVIEWER_APPROVAL_REQUIRED);
    }
  }

  @Test
  void reasonActorAndReviewRefGuardsUseExactMessages() {
    GuardContext noReason = AuthorityFixtures.changing(AuthorityFixtures.context(), "workspace", true, true, " ", null, null, null);
    refusal(Lane.IN_PROGRESS, Lane.PLANNED, noReason, RefusalReason.REASON_REQUIRED,
        "Transition in_progress -> planned requires reason");
    GuardContext noActor = AuthorityFixtures.with(AuthorityFixtures.context(), null, null);
    refusal(Lane.FOR_REVIEW, Lane.IN_REVIEW, noActor, RefusalReason.ACTOR_REQUIRED, "Transition requires actor identity");
    GuardContext noRef = AuthorityFixtures.changing(AuthorityFixtures.context(), "workspace", true, true, "reason", "", null, null);
    refusal(Lane.APPROVED, Lane.IN_PROGRESS, noRef, RefusalReason.REVIEW_REF_REQUIRED,
        "Transition requires review_ref (review feedback reference)");
    refusal(Lane.APPROVED, Lane.PLANNED, noRef, RefusalReason.REVIEW_REF_REQUIRED,
        "Transition requires review_ref (review feedback reference)");
  }

  @Test
  void inReviewCompletenessApprovalThenConsistency() {
    refusal(Lane.IN_REVIEW, Lane.PLANNED, AuthorityFixtures.context(), RefusalReason.REVIEW_EVIDENCE_INVALID,
        "Transition from in_review requires review_result (structured review outcome)");
    Map<String, Object> result = new java.util.LinkedHashMap<>();
    result.put("verdict", "changes_requested");
    result.put("reference", "ref");
    GuardContext noReviewer = AuthorityFixtures.changing(AuthorityFixtures.context(), "workspace", true, true, "reason", null, null, result);
    refusal(Lane.IN_REVIEW, Lane.PLANNED, noReviewer, RefusalReason.REVIEW_EVIDENCE_INVALID,
        "Transition from in_review requires review_result with reviewer");
    result.put("reviewer", "reviewer-a");
    result.remove("verdict");
    GuardContext noVerdict = AuthorityFixtures.changing(AuthorityFixtures.context(), "workspace", true, true, "reason", null, null, result);
    refusal(Lane.IN_REVIEW, Lane.PLANNED, noVerdict, RefusalReason.REVIEW_EVIDENCE_INVALID,
        "Transition from in_review requires review_result with verdict");
    result.put("verdict", "changes_requested");
    result.remove("reference");
    GuardContext noReference = AuthorityFixtures.changing(AuthorityFixtures.context(), "workspace", true, true, "reason", null, null, result);
    refusal(Lane.IN_REVIEW, Lane.PLANNED, noReference, RefusalReason.REVIEW_EVIDENCE_INVALID,
        "Transition from in_review requires review_result with reference");

    GuardContext rejected = AuthorityFixtures.changing(AuthorityFixtures.context(), "workspace", true, true, "reason", "different", null,
        AuthorityFixtures.reviewResult("changes_requested", "ref"));
    refusal(Lane.IN_REVIEW, Lane.PLANNED, rejected, RefusalReason.REVIEW_EVIDENCE_INVALID,
        "review_ref must match review_result.reference");
    GuardContext evidenceMismatch = AuthorityFixtures.changing(AuthorityFixtures.context(), "workspace", true, true, "reason", "ref",
        AuthorityFixtures.approvalEvidence("approved", "ref"), AuthorityFixtures.reviewResult("changes_requested", "ref"));
    refusal(Lane.IN_REVIEW, Lane.APPROVED, evidenceMismatch, RefusalReason.REVIEW_EVIDENCE_INVALID,
        "Review evidence must match review_result");
    GuardContext onlyMismatch = AuthorityFixtures.changing(AuthorityFixtures.context(), "workspace", true, true, "reason", "ref",
        AuthorityFixtures.approvalEvidence("changes_requested", "ref"), AuthorityFixtures.reviewResult("changes_requested", "different"));
    refusal(Lane.IN_REVIEW, Lane.IN_PROGRESS, onlyMismatch, RefusalReason.REVIEW_EVIDENCE_INVALID,
        "review_ref must match review_result.reference");
  }

  @Test
  void inReviewCanUseReviewResultAsApprovalAndComparesEvidenceTriple() {
    GuardContext approved = AuthorityFixtures.changing(AuthorityFixtures.context(), "workspace", true, true, "reason", "ref",
        null, AuthorityFixtures.reviewResult("approved", "ref"));
    assertThat(Guards.check(Lane.IN_REVIEW, Lane.DONE, approved)).isEmpty();
    GuardContext contradictory = AuthorityFixtures.changing(AuthorityFixtures.context(), "workspace", true, true, "reason", null,
        AuthorityFixtures.approvalEvidence("changes_requested", "other"), AuthorityFixtures.reviewResult("approved", "ref"));
    refusal(Lane.IN_REVIEW, Lane.APPROVED, contradictory, RefusalReason.REVIEWER_APPROVAL_REQUIRED,
        "Transition to approved/done requires evidence (reviewer identity and approval reference)");
  }

  @Test
  void otherEdgesAreUnguarded() {
    assertThat(Guards.check(Lane.BLOCKED, Lane.CANCELED, AuthorityFixtures.context())).isEmpty();
    assertThat(Guards.check(Lane.GENESIS, Lane.PLANNED, AuthorityFixtures.context())).isEmpty();
  }

  private static void refusal(Lane from, Lane to, GuardContext context, RefusalReason reason, String message) {
    Refusal refusal = Guards.check(from, to, context).orElseThrow();
    assertThat(refusal.reason()).isEqualTo(reason);
    assertThat(refusal.code()).isEqualTo(reason.code());
    assertThat(refusal.message()).isEqualTo(message);
  }
}
