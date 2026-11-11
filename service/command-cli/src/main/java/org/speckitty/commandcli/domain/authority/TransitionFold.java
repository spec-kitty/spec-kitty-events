package org.speckitty.commandcli.domain.authority;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.speckitty.commandcli.domain.authority.WriteFold.Transition;
import org.speckitty.missionstatus.domain.model.CodePoints;
import org.speckitty.missionstatus.domain.model.Lane;

/** Core's rollback-aware transition fold, including reconstruction-seed payload rules. */
final class TransitionFold {
  private static final Set<Lane> ROLLBACK_TARGETS = Set.of(Lane.PLANNED, Lane.CLAIMED, Lane.IN_PROGRESS);
  private static final List<String> RUNTIME_SLOTS = List.of("shell_pid", "shell_pid_created_at", "subtasks", "notes",
      "tracker_refs", "agent", "assignee", "review", "role", "agent_profile", "agent_profile_version", "model", "provider");

  /** Internal slot: the {@link TransitionId} of the event that set the lane in force (absent slot = no setter). */
  static final String SETTER = "__setter";

  private TransitionFold() {}

  static Map<String, Map<String, Object>> fold(List<Transition> events) {
    Map<String, Map<String, Object>> states = new LinkedHashMap<>();
    Map<String, Provenance> provenance = new HashMap<>();
    for (Transition event : events) {
      Map<String, Object> current = states.get(event.wpId());
      Provenance slots = provenance.computeIfAbsent(event.wpId(), ignored -> new Provenance());
      Verdict verdict = arbitrate(current, event, events);
      if (verdict == Verdict.APPLY) {
        Map<String, Object> next = stateFrom(event, current);
        recordApplied(event, next, slots);
        states.put(event.wpId(), next);
      } else if (verdict == Verdict.LOST_ON_CONTINUITY && current != null && event.seed()) {
        foldLosingSeed(event, current, slots);
      }
    }
    return states;
  }

  private static Map<String, Object> stateFrom(Transition event, Map<String, Object> previous) {
    Map<String, Object> state = new LinkedHashMap<>();
    state.put("lane", event.to().wire());
    state.put("actor", Actors.project(event.actor()));
    state.put("__raw_actor", event.actor());
    state.put("last_transition_at", event.at());
    state.put(SETTER, event.id());
    state.put("last_event_id", event.id().spelling());
    int priorForce = previous == null ? 0 : ((Number) previous.getOrDefault("force_count", 0)).intValue();
    state.put("force_count", priorForce + (event.force() ? 1 : 0));
    if (previous != null) {
      for (String slot : RUNTIME_SLOTS) if (previous.containsKey(slot)) state.put(slot, previous.get(slot));
      if (previous.containsKey("review_result")) state.put("review_result", previous.get("review_result"));
    }
    state.putAll(claimWrites(event));
    if (event.reviewResult() != null || event.from() == Lane.IN_REVIEW) {
      state.put("review_result", event.reviewResult());
    }
    return state;
  }

  private static Map<String, Object> claimWrites(Transition event) {
    if (event.from() != Lane.PLANNED || event.to() != Lane.CLAIMED) return Map.of();
    Object metadata = event.row().get("policy_metadata");
    if (!(metadata instanceof Map<?, ?> map)) return Map.of();
    Map<String, Object> writes = new LinkedHashMap<>();
    for (String slot : List.of("shell_pid", "shell_pid_created_at")) {
      if (map.get(slot) != null) writes.put(slot, map.get(slot));
    }
    if (WriteFold.truthy(map.get("agent"))) writes.put("agent", map.get("agent"));
    return writes;
  }

  private static Verdict arbitrate(Map<String, Object> current, Transition event, List<Transition> all) {
    if (current == null) return Verdict.APPLY;
    Optional<Transition> found = setterOf(current, all);
    if (found.isEmpty()) return Verdict.APPLY;
    Transition setter = found.get();
    if (current.get("last_transition_at").equals(event.at())) {
      Verdict timestamp = sameTimestamp(event, setter, all);
      if (timestamp != null) return timestamp;
    }
    boolean stale = !event.force() && !event.from().wire().equals(current.get("lane"))
        && !continuityExempt(event, setter, all);
    return stale ? Verdict.LOST_ON_CONTINUITY : Verdict.APPLY;
  }

  /** Core's {@code _find_event_by_id}: an id-less setter is no setter, so neither the same-timestamp nor the continuity arm applies. */
  private static Optional<Transition> setterOf(Map<String, Object> current, List<Transition> all) {
    if (!(current.get(SETTER) instanceof TransitionId id) || !id.identified()) return Optional.empty();
    return all.stream().filter(event -> event.id().equals(id)).findFirst();
  }

  private static Verdict sameTimestamp(Transition event, Transition setter, List<Transition> all) {
    if (rollback(event)) {
      if (rollback(setter)) return null;
      return !event.seed() || bareSibling(setter, event, all) ? Verdict.APPLY : null;
    }
    if (rollback(setter) && !yieldsToReal(setter, event, all)) return Verdict.LOST_TO_ROLLBACK;
    return null;
  }

  private static boolean continuityExempt(Transition event, Transition setter, List<Transition> all) {
    if (rollback(event)) return !event.seed() || rollbackSeed(setter) || bareSibling(setter, event, all);
    if (event.seed()) return false;
    if (setter.seed() && !rollback(setter)) return true;
    return yieldsToReal(setter, event, all);
  }

  private static boolean rollback(Transition event) {
    return (event.from() == Lane.IN_REVIEW || (event.from() == Lane.FOR_REVIEW && event.reviewRef() != null))
        && ROLLBACK_TARGETS.contains(event.to());
  }

  private static boolean rollbackSeed(Transition event) {
    return event.seed() && rollback(event);
  }

  private static boolean yieldsToReal(Transition setter, Transition event, List<Transition> all) {
    return rollbackSeed(setter) && !event.seed() && !bareSibling(event, setter, all);
  }

  private static boolean bareSibling(Transition approval, Transition seed, List<Transition> all) {
    if (rollback(approval) || approval.to() != Lane.APPROVED || approval.from() != seed.from()) return false;
    return all.stream().noneMatch(event -> event.wpId().equals(approval.wpId()) && event.from() == Lane.APPROVED
        && !event.seed() && EventOrdering.compare(event, approval) > 0);
  }

  private static boolean writesVerdict(Transition event) {
    return event.reviewResult() != null || event.from() == Lane.IN_REVIEW;
  }

  private static void recordApplied(Transition event, Map<String, Object> state, Provenance provenance) {
    Set<String> writes = new HashSet<>(claimWrites(event).keySet());
    if (writesVerdict(event)) writes.add("review_result");
    if (event.seed() && !rollback(event)) {
      provenance.seed.addAll(writes);
      return;
    }
    if (writes.contains("shell_pid") && !writes.contains("shell_pid_created_at")
        && provenance.seed.contains("shell_pid_created_at")) {
      state.remove("shell_pid_created_at");
      provenance.seed.remove("shell_pid_created_at");
    }
    provenance.real.addAll(writes);
    provenance.seed.removeAll(writes);
  }

  private static void foldLosingSeed(Transition event, Map<String, Object> state, Provenance provenance) {
    claimWrites(event).forEach((slot, value) -> {
      if (!provenance.real.contains(slot) && !(slot.equals("shell_pid_created_at") && provenance.real.contains("shell_pid"))) {
        state.put(slot, value);
        provenance.seed.add(slot);
      }
    });
    if (event.reviewResult() != null && !provenance.real.contains("review_result")) {
      state.put("review_result", event.reviewResult());
      provenance.seed.add("review_result");
    }
  }

  private enum Verdict { APPLY, LOST_TO_ROLLBACK, LOST_ON_CONTINUITY }

  private static final class Provenance {
    private final Set<String> real = new HashSet<>();
    private final Set<String> seed = new HashSet<>();
  }
}
