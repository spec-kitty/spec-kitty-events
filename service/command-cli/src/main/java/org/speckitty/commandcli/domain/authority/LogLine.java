package org.speckitty.commandcli.domain.authority;

import java.util.Map;

/** A decoded physical status-log line. */
public sealed interface LogLine permits LogLine.Blank, LogLine.Value, LogLine.Unparseable {
  record Blank() implements LogLine {}

  record Value(Map<String, Object> row) implements LogLine {}

  record Unparseable() implements LogLine {}
}
