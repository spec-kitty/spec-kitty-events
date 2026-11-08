package org.speckitty.commandcli.domain.authority;

import java.util.List;

/** The new, non-replayed units of one write request and the facts needed to judge them. */
public record AuthorityInput(List<LogLine> log, List<UnitInput> newUnits, boolean batch, MissionFacts facts) {
  public AuthorityInput {
    log = List.copyOf(log);
    newUnits = List.copyOf(newUnits);
  }
}
