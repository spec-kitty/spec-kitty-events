package org.speckitty.commandcli.domain.authority.dependency;

import org.speckitty.commandcli.domain.authority.DependencyDeclaration;
import org.speckitty.commandcli.domain.authority.DependencyGate;
import org.speckitty.commandcli.domain.authority.FoldView;
import org.speckitty.commandcli.domain.authority.MissionFacts;
import org.speckitty.commandcli.domain.authority.WpWriteState;
import org.speckitty.missionstatus.domain.model.Lane;

/** Core's dependency-readiness verdict for the two implementation-entry edges. */
public final class DependencyVerdict implements DependencyGate {
  @Override
  public Boolean readiness(String wpId, Lane from, Lane to, FoldView state, MissionFacts facts) {
    if (!gated(from, to)) return null;
    return switch (facts.dependencies(wpId)) {
      case DependencyDeclaration.None ignored -> true;
      case DependencyDeclaration.Unresolvable ignored -> false;
      case DependencyDeclaration.Declared declared -> declared.wpIds().stream().allMatch(id -> satisfied(state.of(id)));
    };
  }

  private static boolean gated(Lane from, Lane to) {
    return (from == Lane.PLANNED && to == Lane.CLAIMED) || (from == Lane.CLAIMED && to == Lane.IN_PROGRESS);
  }

  private static boolean satisfied(WpWriteState dependency) {
    Lane lane = dependency.lane();
    return lane == Lane.APPROVED || lane == Lane.DONE
        || (lane == Lane.CANCELED && "operator".equals(dependency.reasonSource()));
  }
}
