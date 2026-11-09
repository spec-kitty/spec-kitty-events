package org.speckitty.commandcli.domain.authority;

import java.util.List;

/** A resolved authored subtask roster, or an unresolvable declaration which must fail closed. */
public sealed interface SubtaskRoster {
  record Resolved(List<String> ids) implements SubtaskRoster {
    public Resolved {
      ids = List.copyOf(ids);
    }
  }

  record Unresolvable() implements SubtaskRoster {}
}
