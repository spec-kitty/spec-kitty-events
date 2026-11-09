package org.speckitty.commandcli.domain.authority.dependency;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.speckitty.commandcli.domain.authority.DependencyDeclaration;
import org.speckitty.commandcli.domain.authority.FoldView;
import org.speckitty.commandcli.domain.authority.MissionFacts;
import org.speckitty.commandcli.domain.authority.SubtaskRoster;
import org.speckitty.commandcli.domain.authority.WpWriteState;
import org.speckitty.missionstatus.domain.model.Lane;

class DependencyVerdictTest {
  private static final DependencyVerdict VERDICT = new DependencyVerdict();

  @Test
  void onlyTheTwoImplementationEntryEdgesHaveAVerdict() {
    MissionFacts facts = facts(new DependencyDeclaration.Unresolvable("bad"));
    assertThat(VERDICT.readiness("WP02", Lane.IN_PROGRESS, Lane.FOR_REVIEW, fold(), facts)).isNull();
    assertThat(VERDICT.readiness("WP02", Lane.PLANNED, Lane.BLOCKED, fold(), facts)).isNull();
    assertThat(VERDICT.readiness("WP02", Lane.IN_REVIEW, Lane.APPROVED, fold(), facts)).isNull();
  }

  @Test
  void noDeclarationIsSatisfiedAndUnresolvableIsNot() {
    assertThat(VERDICT.readiness("WP02", Lane.PLANNED, Lane.CLAIMED, fold(), facts(new DependencyDeclaration.None()))).isTrue();
    assertThat(VERDICT.readiness("WP02", Lane.CLAIMED, Lane.IN_PROGRESS, fold(), facts(new DependencyDeclaration.None()))).isTrue();
    assertThat(VERDICT.readiness("WP02", Lane.PLANNED, Lane.CLAIMED, fold(),
        facts(new DependencyDeclaration.Unresolvable("cannot read")))).isFalse();
  }

  @Test
  void acceptableEndingsAndMissingDependenciesFollowCore() {
    DependencyDeclaration declaration = new DependencyDeclaration.Declared(List.of("approved", "done", "operator", "synthetic", "review", "missing"));
    FoldView state = new FoldView(Map.of(
        "approved", state(Lane.APPROVED, null),
        "done", state(Lane.DONE, null),
        "operator", state(Lane.CANCELED, "operator"),
        "synthetic", state(Lane.CANCELED, "synthetic"),
        "review", state(Lane.FOR_REVIEW, null)));
    assertThat(VERDICT.readiness("WP02", Lane.PLANNED, Lane.CLAIMED, state, facts(declaration))).isFalse();
    assertThat(VERDICT.readiness("WP02", Lane.PLANNED, Lane.CLAIMED, state,
        facts(new DependencyDeclaration.Declared(List.of("approved", "done", "operator"))))).isTrue();
    assertThat(VERDICT.readiness("WP02", Lane.PLANNED, Lane.CLAIMED, fold(),
        facts(new DependencyDeclaration.Declared(List.of("missing"))))).isFalse();
  }

  @Test
  void everyOtherLaneOrInvalidStateIsUnsatisfied() {
    for (Lane lane : List.of(Lane.PLANNED, Lane.CLAIMED, Lane.IN_PROGRESS, Lane.IN_REVIEW, Lane.BLOCKED, Lane.CANCELED, Lane.UNINITIALIZED)) {
      FoldView state = new FoldView(Map.of("WP01", state(lane, null)));
      assertThat(VERDICT.readiness("WP02", Lane.PLANNED, Lane.CLAIMED, state,
          facts(new DependencyDeclaration.Declared(List.of("WP01"))))).as(lane.wire()).isFalse();
    }
  }

  private static MissionFacts facts(DependencyDeclaration declaration) {
    return new MissionFacts() {
      public DependencyDeclaration dependencies(String wpId) { return declaration; }
      public SubtaskRoster subtaskRoster(String wpId) { return new SubtaskRoster.Resolved(List.of()); }
    };
  }

  private static FoldView fold() {
    return new FoldView(Map.of());
  }

  private static WpWriteState state(Lane lane, String reasonSource) {
    return new WpWriteState(lane, null, null, null, null, reasonSource, true, Map.of(), null, null, Map.of());
  }
}
