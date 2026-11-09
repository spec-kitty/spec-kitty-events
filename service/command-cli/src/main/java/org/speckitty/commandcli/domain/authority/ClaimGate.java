package org.speckitty.commandcli.domain.authority;

import java.util.Optional;

/** WP07's lifecycle claim seam. */
@FunctionalInterface
public interface ClaimGate {
  ClaimGate NONE = (operation, unit, state) -> Optional.empty();

  Optional<Refusal> check(Operation operation, UnitInput unit, FoldView state);
}
