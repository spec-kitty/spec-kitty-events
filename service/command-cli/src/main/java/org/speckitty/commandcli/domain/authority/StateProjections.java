package org.speckitty.commandcli.domain.authority;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.speckitty.commandcli.domain.authority.WriteFold.Transition;
import org.speckitty.missionstatus.domain.model.CodePoints;
import org.speckitty.missionstatus.domain.model.Lane;
import org.speckitty.missionstatus.domain.model.PyText;

/** CLI reducer's cancellation provenance and durable implementer-of-record projections. */
final class StateProjections {
  private StateProjections() {}

  static void apply(Map<String, Map<String, Object>> states, List<Transition> rawEvents) {
    List<Transition> events = rawEvents.stream().sorted(EventOrdering::compare).toList();
    Set<String> seen = new HashSet<>();
    List<Transition> unique = events.stream().filter(event -> seen.add(event.id())).toList();
    projectCancellation(states, unique);
    projectImplementer(states, unique);
  }

  private static void projectCancellation(Map<String, Map<String, Object>> states, List<Transition> events) {
    for (Transition event : events) {
      if (event.to() != Lane.CANCELED) continue;
      Map<String, Object> state = states.get(event.wpId());
      if (state == null || !Lane.CANCELED.wire().equals(state.get("lane"))) continue;
      state.put("cancellation_reason", event.reason());
      state.put("reason_source", cancellationSource(event));
    }
  }

  private static String cancellationSource(Transition event) {
    if (event.reasonSource() != null) return event.reasonSource();
    String reason = event.reason();
    if (PyText.isBlank(reason) || reason.startsWith("Force move to ") || reason.startsWith("move-task: ")) return "synthetic";
    return "operator";
  }

  private static void projectImplementer(Map<String, Map<String, Object>> states, List<Transition> events) {
    List<Transition> claims = events.stream().filter(StateProjections::claimantBearer).toList();
    Map<String, String> latestReal = latestRealClaimAt(claims);
    for (Transition claim : claims) {
      if (claim.seed() && CodePoints.compare(latestReal.getOrDefault(claim.wpId(), ""), claim.at()) >= 0) continue;
      Map<String, Object> state = states.get(claim.wpId());
      if (state != null) state.put("implementer_of_record", String.valueOf(agent(claim)));
    }
  }

  private static boolean claimantBearer(Transition event) {
    return event.from() == Lane.PLANNED && event.to() == Lane.CLAIMED && WriteFold.truthy(agent(event));
  }

  private static Object agent(Transition event) {
    Object metadata = event.row().get("policy_metadata");
    return metadata instanceof Map<?, ?> map ? map.get("agent") : null;
  }

  private static Map<String, String> latestRealClaimAt(List<Transition> claims) {
    Map<String, String> latest = new HashMap<>();
    for (Transition claim : claims) {
      if (!claim.seed() && CodePoints.compare(claim.at(), latest.getOrDefault(claim.wpId(), "")) > 0) {
        latest.put(claim.wpId(), claim.at());
      }
    }
    return latest;
  }
}
