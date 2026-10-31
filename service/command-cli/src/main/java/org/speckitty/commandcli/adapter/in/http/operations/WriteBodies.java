package org.speckitty.commandcli.adapter.in.http.operations;

import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The shape check of the three write bodies, fifth in the contract's order (400 {@code REQUEST_MALFORMED}), written against
 * contracts/mission-write schemas TransitionUnit, TransitionBatch, AnnotationRequest, RequestContext, WriteSurface, EventRow, WpId and
 * MissionId. Beyond the schemas it applies the contract's cross-field rule: every row names the request's work package as its
 * {@code wp_id}, and a row that names a Mission names the Mission of the path. A refused batch unit is named by its JSON pointer. The
 * check never echoes a value it was given.
 */
public final class WriteBodies {

  /** A malformed body: the fixed detail and, for a batch, the pointer of the failing unit. */
  public record Malformed(String detail, String instance) {}

  static final Pattern ULID = Pattern.compile("^[0-9A-HJKMNP-TV-Z]{26}$");
  static final Pattern WP_ID = Pattern.compile("^WP[0-9]{2,}$");
  static final Pattern RELATIVE_PATH = Pattern.compile("^(\\.|(?!/)(?![A-Za-z]:)(?!.*\\\\)(?!(.*/)?\\.\\.(/|$)).+)$");
  static final Set<String> WRITE_SURFACES = Set.of("checkout_append", "coordination_transaction_append");
  static final int MAX_BATCH_UNITS = 64;

  private static final Set<String> UNIT_FIELDS = Set.of("wpId", "rows", "context");
  private static final Set<String> BATCH_FIELDS = Set.of("units");
  private static final Set<String> ANNOTATION_FIELDS = Set.of("wpId", "row", "context");
  private static final Set<String> CONTEXT_FIELDS = Set.of("actor", "checkout", "missionDirectory", "writeSurface", "destinationRef");

  private final JsonMapper mapper;

  public WriteBodies(JsonMapper mapper) {
    this.mapper = mapper.rebuild().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
  }

  public Optional<Malformed> missionId(String missionId) {
    return missionId != null && ULID.matcher(missionId).matches() ? Optional.empty() : bad("The missionId is not a ULID.");
  }

  public Optional<Malformed> transitionUnit(String missionId, byte[] body) {
    Optional<JsonNode> root = parse(body);
    return root.isEmpty() ? bad("The body is not one JSON object.") : unit(missionId, root.get());
  }

  public Optional<Malformed> transitionBatch(String missionId, byte[] body) {
    Optional<JsonNode> parsed = parse(body);
    if (parsed.isEmpty()) {
      return bad("The body is not one JSON object.");
    }
    JsonNode root = parsed.get();
    if (!onlyFields(root, BATCH_FIELDS, BATCH_FIELDS)) {
      return bad("The batch must have exactly the member units.");
    }
    JsonNode units = root.get("units");
    if (!units.isArray() || units.isEmpty() || units.size() > MAX_BATCH_UNITS) {
      return bad("The batch must hold 1 to " + MAX_BATCH_UNITS + " units.");
    }
    for (int i = 0; i < units.size(); i++) {
      Optional<Malformed> unit = unit(missionId, units.get(i));
      if (unit.isPresent()) {
        return Optional.of(new Malformed(unit.get().detail(), "#/units/" + i));
      }
    }
    return Optional.empty();
  }

  public Optional<Malformed> annotation(String missionId, byte[] body) {
    Optional<JsonNode> parsed = parse(body);
    if (parsed.isEmpty()) {
      return bad("The body is not one JSON object.");
    }
    JsonNode root = parsed.get();
    if (!onlyFields(root, ANNOTATION_FIELDS, ANNOTATION_FIELDS)) {
      return bad("The annotation must have exactly the members wpId, row and context.");
    }
    Optional<String> wpId = wpId(root.get("wpId"));
    if (wpId.isEmpty()) {
      return bad("The wpId is not a work package identifier.");
    }
    Optional<Malformed> row = row(root.get("row"), wpId.get(), missionId);
    return row.isPresent() ? row : context(root.get("context"));
  }

  private Optional<Malformed> unit(String missionId, JsonNode unit) {
    if (!onlyFields(unit, UNIT_FIELDS, UNIT_FIELDS)) {
      return bad("A unit must have exactly the members wpId, rows and context.");
    }
    Optional<String> wpId = wpId(unit.get("wpId"));
    if (wpId.isEmpty()) {
      return bad("The wpId is not a work package identifier.");
    }
    JsonNode rows = unit.get("rows");
    if (!rows.isArray() || rows.isEmpty()) {
      return bad("A unit must hold at least one row.");
    }
    for (JsonNode row : rows) {
      Optional<Malformed> refused = row(row, wpId.get(), missionId);
      if (refused.isPresent()) {
        return refused;
      }
    }
    return context(unit.get("context"));
  }

  private static Optional<Malformed> row(JsonNode row, String wpId, String missionId) {
    if (row == null || !row.isObject()) {
      return bad("A row must be a JSON object.");
    }
    JsonNode eventId = row.get("event_id");
    if (eventId == null || !eventId.isString() || !ULID.matcher(eventId.asString()).matches()) {
      return bad("A row's event_id is missing or not a ULID.");
    }
    JsonNode rowWp = row.get("wp_id");
    if (rowWp == null || !rowWp.isString() || !rowWp.asString().equals(wpId)) {
      return bad("A row names another work package than the request.");
    }
    JsonNode rowMission = row.get("mission_id");
    if (rowMission != null && !rowMission.isNull() && !(rowMission.isString() && rowMission.asString().equals(missionId))) {
      return bad("A row names another Mission than the request.");
    }
    return Optional.empty();
  }

  private static Optional<Malformed> context(JsonNode context) {
    if (!onlyFields(context, CONTEXT_FIELDS, CONTEXT_FIELDS)) {
      return bad("The context must have exactly the members actor, checkout, missionDirectory, writeSurface and destinationRef.");
    }
    JsonNode actor = context.get("actor");
    if (!actor.isString() || actor.asString().isEmpty()) {
      return bad("The context's actor must be a non-empty string.");
    }
    for (String field : new String[] {"checkout", "missionDirectory"}) {
      JsonNode path = context.get(field);
      if (!path.isString() || !RELATIVE_PATH.matcher(path.asString()).matches()) {
        return bad("The context's " + field + " must be a relative forward-slash path without '..'.");
      }
    }
    JsonNode surface = context.get("writeSurface");
    if (!surface.isString() || !WRITE_SURFACES.contains(surface.asString())) {
      return bad("The context's writeSurface is not a WriteSurface value.");
    }
    JsonNode destination = context.get("destinationRef");
    if (!destination.isNull() && !destination.isString()) {
      return bad("The context's destinationRef must be a string or null.");
    }
    return Optional.empty();
  }

  private static Optional<String> wpId(JsonNode wpId) {
    if (wpId == null || !wpId.isString()) {
      return Optional.empty();
    }
    String text = wpId.asString();
    return text.length() <= 64 && WP_ID.matcher(text).matches() ? Optional.of(text) : Optional.empty();
  }

  /** True when {@code node} is an object holding every required member and no other. */
  private static boolean onlyFields(JsonNode node, Set<String> required, Set<String> allowed) {
    if (node == null || !node.isObject()) {
      return false;
    }
    for (String name : required) {
      if (!node.has(name)) {
        return false;
      }
    }
    Iterator<Map.Entry<String, JsonNode>> fields = node.properties().iterator();
    while (fields.hasNext()) {
      if (!allowed.contains(fields.next().getKey())) {
        return false;
      }
    }
    return true;
  }

  private Optional<JsonNode> parse(byte[] body) {
    if (body == null || body.length == 0) {
      return Optional.empty();
    }
    try {
      JsonNode root = mapper.readTree(body);
      return root != null && root.isObject() ? Optional.of(root) : Optional.empty();
    } catch (JacksonException e) {
      return Optional.empty();
    }
  }

  private static Optional<Malformed> bad(String detail) {
    return Optional.of(new Malformed(detail, null));
  }
}
