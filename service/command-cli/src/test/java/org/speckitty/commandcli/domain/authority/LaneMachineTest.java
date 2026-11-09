package org.speckitty.commandcli.domain.authority;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.speckitty.commandcli.domain.service.RefusalReason;
import org.speckitty.missionstatus.domain.model.Lane;

class LaneMachineTest {
  private static final Set<LaneMachine.Edge> LEGAL = Set.of(
      edge(Lane.GENESIS, Lane.PLANNED), edge(Lane.GENESIS, Lane.CANCELED),
      edge(Lane.PLANNED, Lane.CLAIMED), edge(Lane.PLANNED, Lane.BLOCKED), edge(Lane.PLANNED, Lane.CANCELED),
      edge(Lane.CLAIMED, Lane.IN_PROGRESS), edge(Lane.CLAIMED, Lane.BLOCKED), edge(Lane.CLAIMED, Lane.CANCELED),
      edge(Lane.IN_PROGRESS, Lane.FOR_REVIEW), edge(Lane.IN_PROGRESS, Lane.APPROVED), edge(Lane.IN_PROGRESS, Lane.PLANNED),
      edge(Lane.IN_PROGRESS, Lane.BLOCKED), edge(Lane.IN_PROGRESS, Lane.CANCELED),
      edge(Lane.FOR_REVIEW, Lane.IN_REVIEW), edge(Lane.FOR_REVIEW, Lane.BLOCKED), edge(Lane.FOR_REVIEW, Lane.CANCELED),
      edge(Lane.IN_REVIEW, Lane.APPROVED), edge(Lane.IN_REVIEW, Lane.DONE), edge(Lane.IN_REVIEW, Lane.IN_PROGRESS),
      edge(Lane.IN_REVIEW, Lane.PLANNED), edge(Lane.IN_REVIEW, Lane.BLOCKED), edge(Lane.IN_REVIEW, Lane.CANCELED),
      edge(Lane.APPROVED, Lane.DONE), edge(Lane.APPROVED, Lane.IN_PROGRESS), edge(Lane.APPROVED, Lane.PLANNED),
      edge(Lane.APPROVED, Lane.BLOCKED), edge(Lane.APPROVED, Lane.CANCELED),
      edge(Lane.BLOCKED, Lane.IN_PROGRESS), edge(Lane.BLOCKED, Lane.CANCELED));

  private static LaneMachine.Edge edge(Lane from, Lane to) {
    return new LaneMachine.Edge(from, to);
  }

  @Test
  void exactly29EdgesAndEveryOtherPairIsUnforcedIllegal() {
    assertThat(LaneMachine.legalEdges()).containsExactlyInAnyOrderElementsOf(LEGAL).hasSize(29);
    for (Lane from : Lane.values()) {
      for (Lane to : Lane.values()) {
        Optional<Refusal> decision = LaneMachine.check(from, to, AuthorityFixtures.context());
        if (decision.isPresent() && decision.orElseThrow().reason() == RefusalReason.ILLEGAL_TRANSITION) {
          assertThat(LEGAL).doesNotContain(edge(from, to));
        } else {
          assertThat(LEGAL).contains(edge(from, to));
        }
      }
    }
    assertThat(LaneMachine.targets(Lane.DONE)).isEmpty();
    assertThat(LaneMachine.targets(Lane.CANCELED)).isEmpty();
    assertThat(LaneMachine.targets(Lane.UNINITIALIZED)).isEmpty();
  }

  @Test
  void genesisTargetIsIllegalEvenUnderForce() {
    Refusal refusal = LaneMachine.check(Lane.PLANNED, Lane.GENESIS,
        AuthorityFixtures.force(AuthorityFixtures.context(), true)).orElseThrow();
    assertThat(refusal.reason()).isEqualTo(RefusalReason.ILLEGAL_TRANSITION);
    assertThat(refusal.message()).isEqualTo("Illegal transition: planned -> genesis");
  }

  @Test
  void forceCanReachAnyOtherTargetFromAnySourceIncludingUninitializedAndSameLane() {
    for (Lane from : Lane.values()) {
      for (Lane to : Lane.values()) {
        Optional<Refusal> result = LaneMachine.check(from, to, AuthorityFixtures.force(AuthorityFixtures.context(), true));
        if (to == Lane.GENESIS) assertThat(result).isPresent();
        else assertThat(result).as(from + " -> " + to).isEmpty();
      }
    }
  }

  @Test
  void forceRequiresBothActorAndReasonBeforeOtherGuards() {
    GuardContext force = AuthorityFixtures.force(AuthorityFixtures.context(), true);
    GuardContext blankActor = AuthorityFixtures.with(force, "  ", null);
    assertThat(LaneMachine.check(Lane.DONE, Lane.PLANNED, blankActor)).get()
        .extracting(Refusal::reason, Refusal::message)
        .containsExactly(RefusalReason.FORCE_REQUIRES_ACTOR_AND_REASON, "Force transitions require actor and reason");
    GuardContext blankReason = AuthorityFixtures.changing(force, force.workspaceContext(), false, false, "\t", null, null, null);
    assertThat(LaneMachine.check(Lane.PLANNED, Lane.CLAIMED, blankReason)).get()
        .extracting(Refusal::reason, Refusal::message)
        .containsExactly(RefusalReason.FORCE_REQUIRES_ACTOR_AND_REASON, "Force transitions require actor and reason");
  }
}
