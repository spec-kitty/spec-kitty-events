package org.speckitty.commandcli.domain.authority;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.speckitty.commandcli.domain.authority.WriteFold.AnnotationRow;
import org.speckitty.missionstatus.domain.model.CodePoints;

/**
 * Annotation post-pass: sorted independently and folded after every transition, except that a {@code release_runtime_claim} never
 * erases a claim slot a transition ordered after it wrote (spec-kitty/spec-kitty#7794).
 */
final class AnnotationFold {
  private static final List<String> REPLACE_SLOTS = List.of("shell_pid", "shell_pid_created_at", "agent", "assignee",
      "role", "agent_profile", "agent_profile_version", "model", "provider");

  private AnnotationFold() {}

  static void apply(Map<String, Map<String, Object>> states, List<AnnotationRow> annotations,
      Map<String, Map<String, TransitionFold.ClaimWrite>> claimWriters) {
    annotations.stream().sorted(AnnotationFold::compare).forEach(annotation -> {
      Map<String, Object> state = states.computeIfAbsent(annotation.wpId(), ignored -> runtimeOnly(annotation.actor()));
      applyDelta(state, annotation.delta(), laterClaim(annotation, claimWriters.getOrDefault(annotation.wpId(), Map.of())));
    });
  }

  /**
   * Core's {@code _later_claim_writes}: the claim slots a transition after the annotation by stored {@code (at, event_id)} wrote. On an
   * equal {@code at} only a string transition id is compared ({@code _transition_after}); any other id orders before the annotation.
   */
  private static Map<String, Object> laterClaim(AnnotationRow annotation, Map<String, TransitionFold.ClaimWrite> writers) {
    Map<String, Object> kept = new HashMap<>();
    writers.forEach((slot, write) -> {
      int at = CodePoints.compare(write.at(), annotation.at());
      boolean after = at != 0 ? at > 0 : write.id() != null && CodePoints.compare(write.id(), annotation.id()) > 0;
      if (after) kept.put(slot, write.value());
    });
    return kept;
  }

  private static int compare(AnnotationRow left, AnnotationRow right) {
    int at = CodePoints.compare(left.at(), right.at());
    return at != 0 ? at : CodePoints.compare(left.id(), right.id());
  }

  private static Map<String, Object> runtimeOnly(Object actor) {
    Map<String, Object> state = new LinkedHashMap<>();
    state.put("lane", "uninitialized");
    state.put("actor", Actors.project(actor));
    state.put("__raw_actor", actor);
    state.put("last_transition_at", null);
    state.put("last_event_id", null);
    state.put("force_count", 0);
    return state;
  }

  private static void applyDelta(Map<String, Object> state, Map<String, Object> delta, Map<String, Object> laterClaim) {
    if (Boolean.TRUE.equals(delta.get("release_runtime_claim"))) {
      for (String slot : TransitionFold.CLAIM_RELEASE_SLOTS) state.put(slot, laterClaim.get(slot));
    }
    // A later claim that overtook the release owns the whole triple over the delta's own replants (core's ``overtaken``).
    boolean overtaken = Boolean.TRUE.equals(delta.get("release_runtime_claim")) && !laterClaim.isEmpty();
    for (String slot : REPLACE_SLOTS) {
      if (overtaken && TransitionFold.CLAIM_RELEASE_SLOTS.contains(slot)) continue;
      Object value = delta.get(slot);
      if (value != null && !value.equals("")) state.put(slot, value);
    }
    mergeSubtasks(state, delta);
    appendNote(state, delta.get("note"));
    mergeTrackerRefs(state, delta);
    if (delta.get("review") != null) {
      state.put("review", releaseReview(delta.get("review")) ? null : WriteFold.immutableMap(WriteFold.castMap(delta.get("review"))));
    }
  }

  private static void mergeSubtasks(Map<String, Object> state, Map<String, Object> delta) {
    if (!(delta.get("subtasks") instanceof Map<?, ?> additions)) return;
    Map<String, Object> current = new LinkedHashMap<>();
    if (state.get("subtasks") instanceof Map<?, ?> previous) current.putAll(WriteFold.castMap(previous));
    additions.forEach((id, value) -> current.put(String.valueOf(id), "not_applicable".equals(value) ? "skipped" : value));
    state.put("subtasks", current);
  }

  private static void appendNote(Map<String, Object> state, Object note) {
    if (note == null) return;
    List<Object> notes = new ArrayList<>();
    if (state.get("notes") instanceof List<?> previous) notes.addAll(previous);
    notes.add(note);
    state.put("notes", notes);
  }

  private static void mergeTrackerRefs(Map<String, Object> state, Map<String, Object> delta) {
    if (delta.get("tracker_refs_replace") instanceof List<?> replacement) {
      state.put("tracker_refs", distinct(replacement));
    } else if (delta.get("tracker_refs") instanceof List<?> additions) {
      List<Object> merged = new ArrayList<>();
      if (state.get("tracker_refs") instanceof List<?> previous) merged.addAll(previous);
      additions.forEach(value -> { if (!merged.contains(value)) merged.add(value); });
      state.put("tracker_refs", merged);
    }
  }

  private static List<Object> distinct(List<?> values) {
    return new ArrayList<>(new LinkedHashSet<>(values));
  }

  private static boolean releaseReview(Object review) {
    if (!(review instanceof Map<?, ?> map)) return false;
    return List.of("at", "actor", "wp_id", "reason").stream().allMatch(field -> !WriteFold.truthy(map.get(field)));
  }
}
