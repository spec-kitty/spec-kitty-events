package org.speckitty.commandcli.domain.authority.claim;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.speckitty.commandcli.domain.authority.FoldView;
import org.speckitty.commandcli.domain.authority.Operation;
import org.speckitty.commandcli.domain.authority.Refusal;
import org.speckitty.commandcli.domain.authority.UnitInput;
import org.speckitty.commandcli.domain.authority.WpWriteState;
import org.speckitty.commandcli.domain.service.RefusalReason;
import org.speckitty.missionstatus.domain.model.Lane;

class ClaimRulesTest {
  private static final ClaimRules RULES = new ClaimRules();

  @Test
  void transitionsNeverUseLifecycleClaimRules() {
    assertThat(RULES.check(Operation.TRANSITION, start(Operation.TRANSITION, "planned", "claimed", "codex", false, null),
        fold(Lane.DONE, "other", null, null))).isEmpty();
  }

  @Test
  void implementationStartJudgesTheActualLaneBeforeTheRowFromLane() {
    Optional<Refusal> genesis = RULES.check(start("genesis", "in_progress", "codex", false, null),
        fold(Lane.UNINITIALIZED, null, null, null));
    assertThat(genesis).get().extracting(Refusal::reason).isEqualTo(RefusalReason.START_REJECTED);
    assertThat(genesis.orElseThrow().message()).isEqualTo("WP WP01 is not finalized; run `spec-kitty agent mission finalize-tasks`");

    assertThat(RULES.check(start("claimed", "in_progress", "codex", false, null), fold(Lane.PLANNED, null, null, null))).isEmpty();
    Refusal wrong = RULES.check(start("claimed", "in_progress", "codex", false, null), fold(Lane.DONE, "codex", null, null)).orElseThrow();
    assertThat(wrong.reason()).isEqualTo(RefusalReason.START_REJECTED);
    assertThat(wrong.message()).isEqualTo("WP WP01 is in 'done', cannot start implementation");
  }

  @Test
  void claimedAndInProgressUseActorCompatibilityAndGenericPlaceholders() {
    assertThat(RULES.check(start("planned", "claimed", "codex", false, null), fold(Lane.CLAIMED, "codex", null, null))).isEmpty();
    for (String holder : new String[] {"implement-command", "unknown", "user", null}) {
      assertThat(RULES.check(start("planned", "claimed", "codex", false, null), fold(Lane.CLAIMED, holder, null, null))).as(holder).isEmpty();
    }
    Refusal conflict = RULES.check(start("planned", "claimed", "claude", false, null), fold(Lane.CLAIMED, "codex", null, null)).orElseThrow();
    assertThat(conflict.reason()).isEqualTo(RefusalReason.CLAIM_HELD_BY_OTHER_ACTOR);
    assertThat(conflict.message()).isEqualTo("WP WP01 is already claimed for implementation by 'codex'");
    assertThat(conflict.detail()).isEqualTo("The work package is already claimed by another actor.");
  }

  @Test
  void compactAndStructuredActorsShareToolIdentity() {
    FoldView compact = fold(Lane.IN_PROGRESS, "codex:kimi:pedro:implementer", null, null);
    assertThat(RULES.check(start("claimed", "in_progress", Map.of("tool", "codex"), false, null), compact)).isEmpty();
    Refusal conflict = RULES.check(start("claimed", "in_progress", "claude", false, null), compact).orElseThrow();
    assertThat(conflict.message()).contains("codex:kimi:pedro:implementer");
  }

  @Test
  void implementerOfRecordMayResumeAfterReviewerRework() {
    FoldView state = fold(Lane.IN_PROGRESS, "reviewer-a", null, "implementer-a");
    assertThat(RULES.check(start("claimed", "in_progress", "implementer-a", false, null), state)).isEmpty();
    Refusal thirdActor = RULES.check(start("claimed", "in_progress", "implementer-b", false, null), state).orElseThrow();
    assertThat(thirdActor.reason()).isEqualTo(RefusalReason.CLAIM_HELD_BY_OTHER_ACTOR);
    assertThat(thirdActor.message()).isEqualTo("WP WP01 is already claimed for implementation by 'reviewer-a'");
  }

  @Test
  void reviewStartUsesFoldedRoleAndHolderNotRequesterRole() {
    assertThat(RULES.check(review("for_review", "reviewer-b", false, null), fold(Lane.FOR_REVIEW, "implementer-a", "reviewer", null))).isEmpty();
    FoldView reviewer = fold(Lane.IN_REVIEW, "reviewer-a", " reviewer ", null);
    Refusal conflict = RULES.check(review("for_review", Map.of("tool", "reviewer-b", "role", "implementer"), false, null), reviewer).orElseThrow();
    assertThat(conflict.reason()).isEqualTo(RefusalReason.CLAIM_HELD_BY_OTHER_ACTOR);
    assertThat(conflict.message()).isEqualTo("WP WP01 is already claimed for review by 'reviewer-a'");
    assertThat(RULES.check(review("for_review", "reviewer-a", false, null), reviewer)).isEmpty();
    assertThat(RULES.check(review("for_review", "reviewer-b", false, null), fold(Lane.IN_REVIEW, " ", "reviewer", null))).isEmpty();
    assertThat(RULES.check(review("for_review", "reviewer-b", false, null), fold(Lane.IN_REVIEW, "reviewer-a", "implementer", null))).isEmpty();
  }

  @Test
  void reviewWrongLaneIsStartRejected() {
    Refusal refusal = RULES.check(review("for_review", "reviewer-b", false, null), fold(Lane.DONE, "implementer-a", null, null)).orElseThrow();
    assertThat(refusal.reason()).isEqualTo(RefusalReason.START_REJECTED);
    assertThat(refusal.message()).isEqualTo("WP WP01 is in 'done', cannot start review");
  }

  @Test
  void lifecycleForceNoteMustBeNonBlankAndOnlyAppliesToReviewLanes() {
    Refusal blank = RULES.check(start("for_review", "in_progress", "codex", true, " "), fold(Lane.FOR_REVIEW, "codex", null, null)).orElseThrow();
    assertThat(blank.reason()).isEqualTo(RefusalReason.START_REJECTED);
    assertThat(blank.message()).isEqualTo("--force requires a non-blank --note explaining why the review lane is being left");
    Refusal outside = RULES.check(start("planned", "claimed", "codex", true, "operator"), fold(Lane.PLANNED, "codex", null, null)).orElseThrow();
    assertThat(outside.reason()).isEqualTo(RefusalReason.START_REJECTED);
    assertThat(outside.message()).isEqualTo("--force only applies to a WP in for_review, in_review or approved; WP01 is in 'planned'");
    assertThat(RULES.check(start("in_review", "in_progress", "codex", true, "operator"), fold(Lane.IN_REVIEW, "reviewer", "reviewer", null))).isEmpty();
  }

  @Test
  void unnotedForceExitFromInReviewOrApprovedHasItsOwnRefusal() {
    Refusal review = RULES.check(start("in_review", "in_progress", "codex", true, null), fold(Lane.IN_REVIEW, "reviewer-a", "reviewer", null)).orElseThrow();
    assertThat(review.reason()).isEqualTo(RefusalReason.CLAIM_HELD_BY_OTHER_ACTOR);
    assertThat(review.message()).isEqualTo("WP WP01 is already claimed for review by 'reviewer-a'");
    Refusal approved = RULES.check(start("approved", "in_progress", "codex", true, null), fold(Lane.APPROVED, "reviewer-a", "reviewer", null)).orElseThrow();
    assertThat(approved.reason()).isEqualTo(RefusalReason.START_REJECTED);
    assertThat(approved.message()).isEqualTo("WP WP01 is approved; implementation cannot resume it. To rework it run "
        + "`spec-kitty agent tasks move-task WP01 --to planned --review-feedback-file <file>`, "
        + "or, as an operator, `--force --note <why>`");
  }

  @Test
  void forReviewWithdrawalRequiresTheImplementerOfRecord() {
    FoldView state = fold(Lane.FOR_REVIEW, "implementer-a", null, "implementer-a");
    assertThat(RULES.check(start("for_review", "in_progress", "implementer-a", true, null), state)).isEmpty();
    Refusal conflict = RULES.check(start("for_review", "in_progress", "implementer-b", true, null), state).orElseThrow();
    assertThat(conflict.reason()).isEqualTo(RefusalReason.CLAIM_HELD_BY_OTHER_ACTOR);
    assertThat(conflict.message()).isEqualTo("WP WP01 is in for_review; only its implementer of record (implementer-a) may withdraw it, "
        + "or an operator with `--force --note <why>`");
  }

  private static UnitInput start(String from, String to, Object actor, boolean force, String reason) {
    return start(Operation.START_IMPLEMENTATION, from, to, actor, force, reason);
  }

  private static UnitInput review(String from, Object actor, boolean force, String reason) {
    return start(Operation.START_REVIEW, from, "in_review", actor, force, reason);
  }

  private static UnitInput start(Operation operation, String from, String to, Object actor, boolean force, String reason) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("wp_id", "WP01");
    row.put("from_lane", from);
    row.put("to_lane", to);
    row.put("actor", actor);
    row.put("force", force);
    row.put("reason", reason);
    return new UnitInput("WP01", operation, List.of(row));
  }

  private static FoldView fold(Lane lane, String actor, String role, String implementer) {
    Map<String, Object> slots = implementer == null ? Map.of() : Map.of("latest_implementer_actor", implementer);
    return new FoldView(Map.of("WP01", new WpWriteState(lane, actor, actor, role, null, null, true, Map.of(), null, null, slots)));
  }
}
