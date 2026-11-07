package org.speckitty.commandcli.domain.authority;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.speckitty.commandcli.domain.service.RefusalReason;
import org.speckitty.missionstatus.domain.model.Lane;

class UnitJudgeTest {
  private static final MissionFacts NO_FACTS = new MissionFacts() {
    public DependencyDeclaration dependencies(String wpId) { return new DependencyDeclaration.None(); }
    public List<String> subtaskRoster(String wpId) { return List.of(); }
  };

  @Test
  void claimRefusalPrecedesStaleForLifecycleOperationsOnly() {
    Refusal claim = refusal(RefusalReason.CLAIM_HELD_BY_OTHER_ACTOR, "WP01 is already claimed");
    AtomicBoolean consulted = new AtomicBoolean();
    UnitJudge judge = new UnitJudge(DependencyGate.NO_VERDICT, (operation, unit, state) -> {
      consulted.set(true);
      return Optional.of(claim);
    });
    UnitInput start = unit(Operation.START_IMPLEMENTATION, AuthorityFixtures.row("planned", "in_progress"));
    FoldView inProgress = AuthorityFixtures.foldTo(Lane.IN_PROGRESS);
    assertThat(judge.judge(start, inProgress, NO_FACTS)).containsSame(claim);
    consulted.set(false);
    UnitInput transition = unit(Operation.TRANSITION, AuthorityFixtures.row("planned", "in_progress"));
    Refusal stale = judge.judge(transition, inProgress, NO_FACTS).orElseThrow();
    assertThat(stale.reason()).isNull();
    assertThat(consulted).isFalse();
  }

  @Test
  void compatibleLifecycleNoOpStillBecomesStale() {
    UnitJudge judge = new UnitJudge(DependencyGate.NO_VERDICT, ClaimGate.NONE);
    UnitInput start = unit(Operation.START_IMPLEMENTATION, AuthorityFixtures.row("claimed", "in_progress"));
    Refusal refusal = judge.judge(start, AuthorityFixtures.foldTo(Lane.IN_PROGRESS), NO_FACTS).orElseThrow();
    assertThat(refusal.reason()).isNull();
    assertThat(refusal.code().name()).isEqualTo("TRANSITION_REJECTED");
  }

  @Test
  void unknownPrecedesClaimAndForceNotePrecedesDoneEvidenceAndDependency() {
    AtomicBoolean claimed = new AtomicBoolean();
    UnitJudge judge = new UnitJudge((wpId, from, to, state, facts) -> {
      throw new AssertionError("dependency gate must not run");
    }, (operation, unit, state) -> {
      claimed.set(true);
      return Optional.of(refusal(RefusalReason.CLAIM_HELD_BY_OTHER_ACTOR, "claim"));
    });
    Map<String, Object> unknown = AuthorityFixtures.row("planned", "bad");
    assertThat(judge.judge(unit(Operation.START_IMPLEMENTATION, unknown), AuthorityFixtures.foldTo(Lane.PLANNED), NO_FACTS))
        .get().extracting(Refusal::reason).isEqualTo(RefusalReason.UNKNOWN_LANE);
    assertThat(claimed).isFalse();

    Map<String, Object> forced = AuthorityFixtures.row("approved", "done");
    forced.put("force", true);
    forced.put("evidence", Map.of());
    UnitJudge noDependencies = new UnitJudge(DependencyGate.NO_VERDICT, ClaimGate.NONE);
    assertThat(noDependencies.judge(unit(Operation.TRANSITION, forced), AuthorityFixtures.foldTo(Lane.APPROVED), NO_FACTS))
        .get().extracting(Refusal::reason).isEqualTo(RefusalReason.FORCE_NOTE_REQUIRED);
  }

  @Test
  void stalePrecedesForceNoteAndDoneEvidence() {
    Map<String, Object> row = AuthorityFixtures.row("approved", "done");
    row.put("force", true);
    row.put("evidence", Map.of());
    UnitJudge judge = new UnitJudge(DependencyGate.NO_VERDICT, ClaimGate.NONE);
    Refusal refusal = judge.judge(unit(Operation.TRANSITION, row), AuthorityFixtures.foldTo(Lane.IN_REVIEW), NO_FACTS).orElseThrow();
    assertThat(refusal.reason()).isNull();
    assertThat(refusal.message()).contains("from_lane");
  }

  @Test
  void doneEvidencePrecedesDependencyAndMachine() {
    Map<String, Object> row = AuthorityFixtures.row("approved", "done");
    row.put("evidence", Map.of());
    UnitJudge judge = new UnitJudge((wpId, from, to, state, facts) -> {
      throw new AssertionError("dependency gate must not run");
    }, ClaimGate.NONE);
    assertThat(judge.judge(unit(Operation.TRANSITION, row), AuthorityFixtures.foldTo(Lane.APPROVED), NO_FACTS))
        .get().extracting(Refusal::reason).isEqualTo(RefusalReason.DONE_EVIDENCE_INCOMPLETE);
  }

  @Test
  void dependenciesFeedTriStateContextAndSubtasksComeFromRoster() {
    MissionFacts roster = new MissionFacts() {
      public DependencyDeclaration dependencies(String wpId) { return new DependencyDeclaration.None(); }
      public List<String> subtaskRoster(String wpId) { return List.of("T001"); }
    };
    Map<String, Object> row = AuthorityFixtures.row("in_progress", "for_review");
    UnitJudge judge = new UnitJudge((wpId, from, to, state, facts) -> false, ClaimGate.NONE);
    Refusal refusal = judge.judge(unit(Operation.TRANSITION, row), AuthorityFixtures.foldTo(Lane.IN_PROGRESS), roster).orElseThrow();
    assertThat(refusal.reason()).isEqualTo(RefusalReason.IMPLEMENTATION_EVIDENCE_REQUIRED);
    assertThat(refusal.message()).contains("completed subtasks");

    FoldView doneSubtask = WriteFold.fold(append(AuthorityFixtures.foldTo(Lane.IN_PROGRESS), annotation(Map.of("T001", "skipped"))));
    assertThat(judge.judge(unit(Operation.TRANSITION, row), doneSubtask, roster)).isEmpty();
  }

  @Test
  void dependencyVerdictFeedsMachineWhichRetainsActorBeforeDependencyPrecedence() {
    Map<String, Object> row = AuthorityFixtures.row("planned", "claimed");
    row.put("actor", "  ");
    UnitJudge judge = new UnitJudge((wpId, from, to, state, facts) -> false, ClaimGate.NONE);
    Refusal refusal = judge.judge(unit(Operation.TRANSITION, row), AuthorityFixtures.foldTo(Lane.PLANNED), NO_FACTS).orElseThrow();
    assertThat(refusal.reason()).isEqualTo(RefusalReason.ACTOR_REQUIRED);
  }

  @Test
  void standaloneAndTrailingAnnotationsDoNotTraverseMachine() {
    Map<String, Object> annotation = new LinkedHashMap<>();
    annotation.put("kind", "annotation");
    annotation.put("event_id", "01J00000000000000000000001");
    annotation.put("wp_id", "WP01");
    annotation.put("at", "2026-01-01T00:00:00+00:00");
    annotation.put("actor", "implementer");
    annotation.put("delta", Map.of("role", "reviewer"));
    UnitInput input = new UnitInput("WP01", null, List.of(annotation));
    assertThat(new UnitJudge(DependencyGate.NO_VERDICT, ClaimGate.NONE).judge(input, new FoldView(Map.of()), NO_FACTS)).isEmpty();
  }

  @Test
  void mapSignatureAndJudgementAreAvailableToBatchCaller() {
    UnitJudge judge = new UnitJudge(DependencyGate.NO_VERDICT, ClaimGate.NONE);
    UnitInput input = unit(Operation.TRANSITION, AuthorityFixtures.row("planned", "claimed"));
    assertThat(judge.judge(input, AuthorityFixtures.foldTo(Lane.PLANNED).all(), NO_FACTS)).isEmpty();
    Refusal refusal = refusal(RefusalReason.ILLEGAL_TRANSITION, "Illegal transition: done -> planned");
    assertThat(new Judgement.Refused(refusal).refusal()).isSameAs(refusal);
    assertThat(new Judgement.Accepted()).isInstanceOf(Judgement.class);
  }

  private static UnitInput unit(Operation operation, Map<String, Object> row) {
    return new UnitInput("WP01", operation, List.of(row));
  }

  private static Refusal refusal(RefusalReason reason, String message) {
    return new Refusal(reason.code(), reason, message, DetailPolicy.detail(reason, message), null);
  }

  private static Map<String, Object> annotation(Map<String, String> subtasks) {
    Map<String, Object> annotation = new LinkedHashMap<>();
    annotation.put("kind", "annotation");
    annotation.put("event_id", "01J00000000000000000000002");
    annotation.put("wp_id", "WP01");
    annotation.put("at", "2026-01-01T00:00:00+00:00");
    annotation.put("actor", "implementer");
    annotation.put("delta", Map.of("subtasks", subtasks));
    return annotation;
  }

  private static List<Map<String, Object>> append(FoldView view, Map<String, Object> annotation) {
    List<Map<String, Object>> rows = new java.util.ArrayList<>();
    rows.addAll(AuthorityFixtures.rows(new String[][] {{"genesis", "planned"}, {"planned", "claimed"}, {"claimed", "in_progress"}}));
    rows.add(annotation);
    return rows;
  }
}
