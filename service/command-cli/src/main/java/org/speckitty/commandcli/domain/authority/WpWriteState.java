package org.speckitty.commandcli.domain.authority;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.speckitty.missionstatus.domain.model.Lane;

/** The authority state of one work package after folding its status log. */
public record WpWriteState(
    Lane lane,
    Object actor,
    String tool,
    String role,
    String agent,
    String reasonSource,
    boolean hasTransitions,
    Map<String, String> subtaskState,
    String implementerOfRecord,
    Map<String, Object> review,
    Map<String, Object> slots) {
  public WpWriteState {
    subtaskState = Map.copyOf(subtaskState);
    review = review == null ? null : Map.copyOf(review);
    slots = Collections.unmodifiableMap(new LinkedHashMap<>(slots));
  }

  public Object slot(String name) {
    return slots.get(name);
  }

  public static WpWriteState uninitialized() {
    return new WpWriteState(Lane.UNINITIALIZED, null, null, null, null, null, false, Map.of(), null, null, Map.of());
  }
}
