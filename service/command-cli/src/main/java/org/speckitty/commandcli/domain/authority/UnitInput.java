package org.speckitty.commandcli.domain.authority;

import java.util.List;
import java.util.Map;

/**
 * One decoded write unit. {@code implementationEvidencePresent} is the request-only evidence flag of the unforced in_progress to for_review
 * guard: null means absent (the authority infers it), and it is never part of a row.
 */
public record UnitInput(String wpId, Operation operation, List<Map<String, Object>> rows, Boolean implementationEvidencePresent) {
  public UnitInput {
    rows = List.copyOf(rows);
  }

  public UnitInput(String wpId, Operation operation, List<Map<String, Object>> rows) {
    this(wpId, operation, rows, null);
  }

  public Map<String, Object> transitionRow() {
    return rows.getFirst();
  }
}
