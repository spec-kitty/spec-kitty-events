package org.speckitty.commandcli.adapter.in.http.operations;

import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.speckitty.commandcli.domain.authority.Actors;
import org.speckitty.missionstatus.domain.model.Lane;
import org.speckitty.missionstatus.domain.model.PyIsoDateTime;
import org.speckitty.missionstatus.domain.model.PyText;
import tools.jackson.databind.JsonNode;

/** The row-kind, canonical-spelling, finite-number and session-time checks that run before the lane authority. */
final class RowRules {

  private static final Set<String> LANES = Arrays.stream(Lane.values()).map(Lane::wire).collect(Collectors.toUnmodifiableSet());
  private static final Pattern ANNOTATION_WP_ID = Pattern.compile("^WP[0-9]+$");

  private RowRules() {}

  /**
   * A transition row carries only canonical persisted lanes. Spellings core would normalize (case, surrounding whitespace or doing) are
   * malformed; an otherwise unknown string passes so the authority can answer {@code TRANSITION_REJECTED} with {@code unknown_lane}.
   */
  static Optional<WriteBodies.Malformed> transition(JsonNode row) {
    if (row.has("kind") || !row.path("from_lane").isString() || !row.path("to_lane").isString() || !validActor(row.get("actor"))) {
      return bad("A unit's first row must be a transition with no kind, string from_lane and to_lane, and a non-empty string actor or a structured actor the status log can read back.");
    }
    if (!canonical(row.get("from_lane")) || !canonical(row.get("to_lane"))) {
      return bad("A transition row's lanes must use canonical persisted spellings without aliases.");
    }
    return common(row);
  }

  static Optional<WriteBodies.Malformed> annotation(JsonNode row) {
    if (!row.path("kind").isString() || !"annotation".equals(row.get("kind").asString())) {
      return bad("An annotation row must have kind annotation.");
    }
    if (!row.path("wp_id").isString() || !ANNOTATION_WP_ID.matcher(row.get("wp_id").asString()).matches()) {
      return bad("An annotation row's wp_id is not a work package identifier.");
    }
    if (!nonEmptyDelta(row.get("delta"))) {
      return bad("An annotation row's delta must hold at least one non-empty value.");
    }
    return common(row);
  }

  private static Optional<WriteBodies.Malformed> common(JsonNode row) {
    if (containsNonFinite(row)) {
      return bad("A row must not contain a non-finite number.");
    }
    if (sessionTimesCannotBeSubtracted(row)) {
      return bad("A row's session timestamps must both be naive or both be aware.");
    }
    return Optional.empty();
  }

  private static boolean validActor(JsonNode actor) {
    if (actor == null) return false;
    return actor.isObject() ? Actors.decodable(WriteBodies.jsonValue(actor)) : actor.isString() && !actor.asString().isEmpty();
  }

  private static boolean canonical(JsonNode lane) {
    String value = lane.asString();
    if (LANES.contains(value)) {
      return true;
    }
    String normalized = PyText.strip(value).toLowerCase(Locale.ROOT);
    return !LANES.contains(normalized) && !"doing".equals(normalized);
  }

  private static boolean nonEmptyDelta(JsonNode delta) {
    if (delta == null || !delta.isObject() || delta.isEmpty()) {
      return false;
    }
    for (Map.Entry<String, JsonNode> member : delta.properties()) {
      if (!member.getValue().isNull() && !(member.getValue().isString() && member.getValue().asString().isEmpty())) {
        return true;
      }
    }
    return false;
  }

  private static boolean containsNonFinite(JsonNode value) {
    if (value.isFloatingPointNumber()) {
      return !Double.isFinite(value.doubleValue());
    }
    if (value.isArray()) {
      for (JsonNode item : value) {
        if (containsNonFinite(item)) {
          return true;
        }
      }
    } else if (value.isObject()) {
      for (JsonNode item : value) {
        if (containsNonFinite(item)) {
          return true;
        }
      }
    }
    return false;
  }

  private static boolean sessionTimesCannotBeSubtracted(JsonNode row) {
    JsonNode started = row.get("session_started_at");
    JsonNode ended = row.get("session_ended_at");
    if (started == null || ended == null || !started.isString() || !ended.isString()) {
      return false;
    }
    Optional<PyIsoDateTime> start = PyIsoDateTime.parse(started.asString());
    Optional<PyIsoDateTime> end = PyIsoDateTime.parse(ended.asString());
    return start.isPresent() && end.isPresent() && start.get().aware() != end.get().aware();
  }

  private static Optional<WriteBodies.Malformed> bad(String detail) {
    return Optional.of(new WriteBodies.Malformed(detail, null));
  }
}
