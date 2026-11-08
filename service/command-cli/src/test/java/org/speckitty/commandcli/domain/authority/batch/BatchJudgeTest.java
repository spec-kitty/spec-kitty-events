package org.speckitty.commandcli.domain.authority.batch;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.speckitty.commandcli.domain.authority.DependencyDeclaration;
import org.speckitty.commandcli.domain.authority.Judgement;
import org.speckitty.commandcli.domain.authority.LogLine;
import org.speckitty.commandcli.domain.authority.MissionFacts;
import org.speckitty.commandcli.domain.authority.Refusal;
import org.speckitty.commandcli.domain.authority.UnitInput;
import org.speckitty.commandcli.domain.authority.Operation;
import org.speckitty.commandcli.domain.service.RefusalReason;

class BatchJudgeTest {
  private static final MissionFacts NO_FACTS = facts(Map.of());

  @Test
  void secondUnitIsJudgedAfterTheFirstAndSingleRefusalsHaveNoIndex() {
    List<LogLine> log = log(seed("WP01"));
    UnitInput claim = unit("WP01", Operation.TRANSITION, row("WP01", "planned", "claimed", 2));
    UnitInput implement = unit("WP01", Operation.START_IMPLEMENTATION, row("WP01", "claimed", "in_progress", 3));
    assertThat(BatchJudge.standard().judge(log, List.of(claim, implement), true, NO_FACTS)).isInstanceOf(Judgement.Accepted.class);
    Judgement single = BatchJudge.standard().judge(log, List.of(unit("WP01", Operation.TRANSITION, row("WP01", "planned", "done", 4))), false, NO_FACTS);
    assertThat(((Judgement.Refused) single).refusal().unitIndex()).isNull();
  }

  @Test
  void firstRefusalStopsAtItsOriginalBatchIndex() {
    List<LogLine> log = log(seed("WP01"));
    List<UnitInput> units = List.of(
        unit("WP01", Operation.TRANSITION, row("WP01", "planned", "claimed", 2)),
        unit("WP01", Operation.TRANSITION, row("WP01", "claimed", "in_progress", 3)),
        unit("WP01", Operation.TRANSITION, row("WP01", "in_progress", "done", 4)));
    Refusal refusal = ((Judgement.Refused) BatchJudge.standard().judge(log, units, true, NO_FACTS)).refusal();
    assertThat(refusal.unitIndex()).isEqualTo(2);
    assertThat(refusal.reason()).isEqualTo(RefusalReason.ILLEGAL_TRANSITION);
  }

  @Test
  void earlierUnitCanSatisfyAnotherWorkPackagesDependency() {
    List<LogLine> log = log(seed("WP01"), row("WP02", "genesis", "planned", 100));
    Map<String, Object> approve = row("WP01", "planned", "approved", 2);
    approve.put("force", true);
    approve.put("reason", "operator approve");
    MissionFacts facts = facts(Map.of("WP02", new DependencyDeclaration.Declared(List.of("WP01"))));
    Judgement result = BatchJudge.standard().judge(log, List.of(
        unit("WP01", Operation.TRANSITION, approve),
        unit("WP02", Operation.TRANSITION, row("WP02", "planned", "claimed", 3))), true, facts);
    assertThat(result).isInstanceOf(Judgement.Accepted.class);
  }

  @Test
  void annotationRowsOfAnEarlierUnitJoinTheFold() {
    List<LogLine> log = log(seed("WP01"));
    UnitInput implement = unit("WP01", Operation.TRANSITION, row("WP01", "claimed", "in_progress", 3));
    UnitInput review = unit("WP01", Operation.TRANSITION, row("WP01", "in_progress", "for_review", 5));
    UnitInput blocked = unit("WP01", Operation.TRANSITION, row("WP01", "for_review", "blocked", 6));
    List<LogLine> claimed = log(seed("WP01"), row("WP01", "planned", "claimed", 2));
    MissionFacts roster = new MissionFacts() {
      public DependencyDeclaration dependencies(String wpId) { return new DependencyDeclaration.None(); }
      public List<String> subtaskRoster(String wpId) { return List.of("T001"); }
    };
    Map<String, Object> annotation = annotation(4, Map.of("subtasks", Map.of("T001", "done")));
    UnitInput withAnnotation = new UnitInput("WP01", Operation.TRANSITION, List.of(implement.transitionRow(), annotation));
    assertThat(BatchJudge.standard().judge(claimed, List.of(implement, review, blocked), true, roster)).isInstanceOf(Judgement.Refused.class);
    assertThat(BatchJudge.standard().judge(claimed, List.of(withAnnotation, review, blocked), true, roster)).isInstanceOf(Judgement.Accepted.class);
  }

  @Test
  void acceptsSixtyFourUnitsWithinTheSequentialFoldBudget() {
    List<LogLine> log = new ArrayList<>();
    List<UnitInput> units = new ArrayList<>();
    for (int index = 1; index <= 64; index++) {
      String wp = String.format("WP%03d", index);
      log.add(new LogLine.Value(row(wp, "genesis", "planned", index * 2 - 1)));
      units.add(unit(wp, Operation.TRANSITION, row(wp, "planned", "claimed", index * 2)));
    }
    org.assertj.core.api.Assertions.assertThatCode(() ->
        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(Duration.ofSeconds(5), () ->
            assertThat(BatchJudge.standard().judge(log, units, true, NO_FACTS)).isInstanceOf(Judgement.Accepted.class)))
        .doesNotThrowAnyException();
  }

  private static MissionFacts facts(Map<String, DependencyDeclaration> declarations) {
    return new MissionFacts() {
      public DependencyDeclaration dependencies(String wpId) { return declarations.getOrDefault(wpId, new DependencyDeclaration.None()); }
      public List<String> subtaskRoster(String wpId) { return List.of(); }
    };
  }

  private static Map<String, Object> seed(String wp) {
    return row(wp, "genesis", "planned", 1);
  }

  private static Map<String, Object> row(String wp, String from, String to, int id) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("event_id", String.format("%026d", id));
    row.put("wp_id", wp);
    row.put("from_lane", from);
    row.put("to_lane", to);
    row.put("at", String.format("2026-01-01T00:00:%02d+00:00", id % 60));
    row.put("actor", "implementer-a");
    row.put("force", false);
    row.put("execution_mode", "worktree");
    row.put("reason", null);
    return row;
  }

  private static Map<String, Object> annotation(int id, Map<String, Object> delta) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("event_id", String.format("%026d", id));
    row.put("wp_id", "WP01");
    row.put("kind", "annotation");
    row.put("at", String.format("2026-01-01T00:00:%02d+00:00", id % 60));
    row.put("actor", "implementer-a");
    row.put("delta", delta);
    return row;
  }

  private static UnitInput unit(String wp, Operation operation, Map<String, Object> row) {
    return new UnitInput(wp, operation, List.of(row));
  }

  private static List<LogLine> log(Map<String, Object>... rows) {
    List<LogLine> result = new ArrayList<>();
    for (Map<String, Object> row : rows) result.add(new LogLine.Value(row));
    return result;
  }
}
