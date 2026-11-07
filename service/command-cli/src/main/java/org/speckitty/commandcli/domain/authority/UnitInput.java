package org.speckitty.commandcli.domain.authority;

import java.util.List;
import java.util.Map;

/** One decoded write unit. */
public record UnitInput(String wpId, Operation operation, List<Map<String, Object>> rows) {
  public UnitInput {
    rows = List.copyOf(rows);
  }

  public Map<String, Object> transitionRow() {
    return rows.getFirst();
  }
}
