package org.speckitty.commandcli.application.diagnostics;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** A content-free diagnostic for an unexpected failure: only its operation, refusal code and exception class chain. */
public final class FailureRecord {
  private static final int MAX_CAUSE_DEPTH = 8;

  private FailureRecord() { }

  /** Never reads a throwable's message or string form: JDK I/O messages can contain host paths and request content. */
  public static String describe(String operation, String code, Throwable failure) {
    StringBuilder record = new StringBuilder(operation).append(" failed with ").append(code).append(": ");
    Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
    Throwable current = failure;
    int depth = 0;
    while (current != null && seen.add(current)) {
      if (depth == MAX_CAUSE_DEPTH) {
        record.append(" <- …");
        break;
      }
      if (depth > 0) record.append(" <- ");
      record.append(current.getClass().getName());
      current = current.getCause();
      depth++;
    }
    return record.toString();
  }
}
