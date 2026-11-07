package org.speckitty.commandcli.domain.authority;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.speckitty.missionstatus.domain.model.Lane;

/** Core-parity write fold: partition, dedupe, order, transition arbitration, annotations and CLI projections. */
public final class WriteFold {
  private WriteFold() {}

  public static FoldView fold(Iterable<LogLine> lines) {
    List<Map<String, Object>> rows = new ArrayList<>();
    for (LogLine line : lines) {
      switch (line) {
        case LogLine.Blank ignored -> { }
        case LogLine.Value value -> rows.add(value.row());
        case LogLine.Unparseable ignored -> throw new UnreducibleLog("Invalid JSON in status log");
      }
    }
    return fold(rows);
  }

  public static FoldView fold(List<Map<String, Object>> rows) {
    try {
      return foldRows(rows);
    } catch (UnreducibleLog exception) {
      throw exception;
    } catch (RuntimeException exception) {
      throw new UnreducibleLog("Invalid status event structure: " + exception.getMessage());
    }
  }

  private static FoldView foldRows(List<Map<String, Object>> rows) {
    List<Transition> transitions = new ArrayList<>();
    List<AnnotationRow> annotations = new ArrayList<>();
    Set<String> transitionWps = new LinkedHashSet<>();
    for (Map<String, Object> row : rows) {
      Object kind = row.get("kind");
      if (kind != null) {
        if (!"annotation".equals(kind)) throw new UnreducibleLog("Unknown event kind " + kind);
        annotations.add(new AnnotationRow(row));
      } else if (!row.containsKey("event_type")) {
        Transition transition = new Transition(row);
        transitions.add(transition);
        transitionWps.add(transition.wpId());
      }
    }
    List<Transition> unique = EventOrdering.dedup(transitions);
    List<Transition> ordered = EventOrdering.order(unique);
    Map<String, Map<String, Object>> states = TransitionFold.fold(ordered);
    AnnotationFold.apply(states, annotations);
    StateProjections.apply(states, transitions);
    Map<String, WpWriteState> result = new LinkedHashMap<>();
    states.forEach((wpId, state) -> result.put(wpId, stateView(state, transitionWps.contains(wpId))));
    return new FoldView(result);
  }

  private static WpWriteState stateView(Map<String, Object> state, boolean hasTransitions) {
    Map<String, Object> slots = new LinkedHashMap<>(state);
    Object rawActor = immutable(slots.remove("__raw_actor"));
    Object projectedActor = slots.get("actor");
    Object subtasks = slots.getOrDefault("subtasks", Map.of());
    Map<String, String> subtaskView = new LinkedHashMap<>();
    if (subtasks instanceof Map<?, ?> map) {
      map.forEach((id, status) -> subtaskView.put(String.valueOf(id), String.valueOf(status)));
    }
    Map<String, Object> review = slots.get("review") instanceof Map<?, ?> map ? castMap(map) : null;
    return new WpWriteState(Lane.fromWire(String.valueOf(slots.get("lane"))), rawActor, Actors.project(rawActor),
        text(slots.get("role")), text(slots.get("agent")), text(slots.get("reason_source")), hasTransitions,
        subtaskView, text(slots.get("implementer_of_record")), review, immutableMap(slots));
  }

  static String text(Object value) {
    return value == null ? null : String.valueOf(value);
  }

  static boolean truthy(Object value) {
    return value != null && !String.valueOf(value).isEmpty();
  }

  static Map<String, Object> immutableMap(Map<String, ?> source) {
    Map<String, Object> result = new LinkedHashMap<>();
    source.forEach((key, value) -> result.put(key, immutable(value)));
    return Collections.unmodifiableMap(result);
  }

  private static Object immutable(Object value) {
    if (value instanceof Map<?, ?> map) {
      Map<String, Object> result = new LinkedHashMap<>();
      map.forEach((key, item) -> result.put(String.valueOf(key), immutable(item)));
      return Collections.unmodifiableMap(result);
    }
    if (value instanceof List<?> list) {
      return Collections.unmodifiableList(list.stream().map(WriteFold::immutable).toList());
    }
    return value;
  }

  @SuppressWarnings("unchecked")
  static Map<String, Object> castMap(Object value) {
    return (Map<String, Object>) value;
  }

  /** Parsed transition row; its original map is retained only for same-id seed canonicalization. */
  record Transition(Map<String, Object> row) {
    Transition {
      row = new LinkedHashMap<>(row);
      Lane.fromWire(text(row.get("from_lane")));
      Lane.fromWire(text(row.get("to_lane")));
      if (row.get("event_id") == null) throw new IllegalArgumentException("event_id is required");
    }

    String id() { return text(row.get("event_id")); }
    String wpId() { return text(row.get("wp_id")); }
    String at() { return text(row.get("at")); }
    Lane from() { return Lane.fromWire(text(row.get("from_lane"))); }
    Lane to() { return Lane.fromWire(text(row.get("to_lane"))); }
    Object actor() { return row.get("actor"); }
    boolean force() { return Boolean.TRUE.equals(row.get("force")); }
    String reason() { return text(row.get("reason")); }
    String reasonSource() { return text(row.get("reason_source")); }
    Object reviewResult() { return row.get("review_result"); }
    Object reviewRef() { return row.get("review_ref"); }
    boolean seed() { return Actors.isMigration(actor()); }
  }

  /** Parsed annotation row. */
  record AnnotationRow(String id, String wpId, String at, Object actor, Map<String, Object> delta) {
    AnnotationRow(Map<String, Object> row) {
      this(text(row.get("event_id")), text(row.get("wp_id")), text(row.get("at")), row.get("actor"),
          castMap(row.get("delta")));
    }
  }
}
