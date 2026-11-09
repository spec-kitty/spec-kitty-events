package org.speckitty.commandcli.domain.authority;

import java.util.Map;

/** Immutable read view supplied to every authority gate and judge. */
public record FoldView(Map<String, WpWriteState> all) {
  public FoldView {
    all = Map.copyOf(all);
  }

  public WpWriteState of(String wpId) {
    return all.getOrDefault(wpId, WpWriteState.uninitialized());
  }

  public boolean known(String wpId) {
    return all.containsKey(wpId);
  }
}
