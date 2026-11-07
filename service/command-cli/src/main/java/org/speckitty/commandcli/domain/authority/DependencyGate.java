package org.speckitty.commandcli.domain.authority;

import org.speckitty.missionstatus.domain.model.Lane;

/** WP07's dependency-readiness seam; null means no verdict. */
@FunctionalInterface
public interface DependencyGate {
  DependencyGate NO_VERDICT = (wpId, from, to, state, facts) -> null;

  Boolean readiness(String wpId, Lane from, Lane to, FoldView state, MissionFacts facts);
}
