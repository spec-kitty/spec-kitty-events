package org.speckitty.commandcli.adapter.in.http.operations;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.speckitty.commandcli.application.write.WriteRequest;
import org.speckitty.missionstatus.adapter.out.filesystem.codec.StatusRowDecoder;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The shape check of the three write bodies, fifth in the contract's order (400 {@code REQUEST_MALFORMED}), written against
 * contracts/mission-write schemas TransitionUnit, TransitionBatch, AnnotationRequest, RequestContext, WriteSurface, EventRow, WpId and
 * MissionId. Beyond the schemas it applies the contract's cross-field and row rules. A refused batch unit is named by its JSON pointer.
 * The check never echoes a value it was given.
 */
public final class WriteBodies {

  /** A malformed body: the fixed detail and, for a batch, the pointer of the failing unit. */
  public record Malformed(String detail, String instance) {}

  /** Either the deeply immutable typed request or the shape refusal. */
  public sealed interface Parsed {
    record Success(WriteRequest request) implements Parsed {}

    record Failure(Malformed malformed) implements Parsed {}
  }

  static final Pattern ULID = Pattern.compile("^[0-9A-HJKMNP-TV-Z]{26}$");
  static final Pattern WP_ID = Pattern.compile("^WP[0-9]{2,}$");
  static final Pattern RELATIVE_PATH = Pattern.compile("^(\\.|(?!/)(?![A-Za-z]:)(?!.*\\\\)(?!(.*/)?\\.\\.(/|$)).+)$");
  static final Set<String> WRITE_SURFACES = Set.of("checkout_append", "coordination_ref_commit");
  static final int MAX_BATCH_UNITS = 64;

  private static final Set<String> UNIT_REQUIRED = Set.of("wpId", "rows", "context");
  private static final Set<String> UNIT_FIELDS = Set.of("wpId", "operation", "rows", "context");
  private static final Set<String> BATCH_FIELDS = Set.of("units");
  private static final Set<String> ANNOTATION_FIELDS = Set.of("wpId", "row", "context");
  private static final Set<String> CONTEXT_FIELDS = Set.of("actor", "checkout", "missionDirectory", "writeSurface", "destinationRef");
  /** One batch is one write (one commit for coordination_ref_commit), so its units name one location (ADR-0007). */
  private static final List<String> SHARED_BATCH_FIELDS = List.of("missionDirectory", "writeSurface", "destinationRef");
  static final String COORDINATION_REF_COMMIT = "coordination_ref_commit";

  private final JsonMapper mapper;

  /**
   * The request parser takes exactly the limits of the log reader (nesting, integer length, unbounded floats), set explicitly on its own
   * token factory, so the edge never accepts a row the reader of the log would refuse and never refuses one it would read.
   */
  public WriteBodies() {
    JsonFactory factory = JsonFactory.builder().streamReadConstraints(StatusRowDecoder.readConstraints()).build();
    this.mapper = JsonMapper.builder(factory).enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
  }

  public Optional<Malformed> missionId(String missionId) {
    return missionId != null && ULID.matcher(missionId).matches() ? Optional.empty() : bad("The missionId is not a ULID.");
  }

  public Parsed parseUnit(String missionId, byte[] body) {
    Optional<JsonNode> root = parse(body);
    return root.isEmpty() ? failure("The body is not one JSON object.") : parseTypedUnit(missionId, root.get());
  }

  public Parsed parseBatch(String missionId, byte[] body) {
    Optional<JsonNode> parsed = parse(body);
    if (parsed.isEmpty()) {
      return failure("The body is not one JSON object.");
    }
    JsonNode root = parsed.get();
    if (!onlyFields(root, BATCH_FIELDS, BATCH_FIELDS)) {
      return failure("The batch must have exactly the member units.");
    }
    JsonNode units = root.get("units");
    if (!units.isArray() || units.isEmpty() || units.size() > MAX_BATCH_UNITS) {
      return failure("The batch must hold 1 to " + MAX_BATCH_UNITS + " units.");
    }
    List<WriteRequest.UnitRequest> typedUnits = new ArrayList<>(units.size());
    WriteRequest.Context firstContext = null;
    for (int i = 0; i < units.size(); i++) {
      Parsed unit = parseTypedUnit(missionId, units.get(i));
      if (unit instanceof Parsed.Failure refused) {
        return new Parsed.Failure(new Malformed(refused.malformed().detail(), "#/units/" + i));
      }
      WriteRequest request = ((Parsed.Success) unit).request();
      typedUnits.add(request.units().get(0));
      if (i == 0) {
        firstContext = request.context();
      } else {
        Optional<Malformed> mixed = sharedLocation(units.get(i).get("context"), units.get(0).get("context"), i);
        if (mixed.isPresent()) {
          return new Parsed.Failure(mixed.get());
        }
      }
    }
    return new Parsed.Success(new WriteRequest(missionId, WriteRequest.Kind.BATCH, typedUnits, firstContext));
  }

  public Parsed parseAnnotation(String missionId, byte[] body) {
    Optional<JsonNode> parsed = parse(body);
    if (parsed.isEmpty()) {
      return failure("The body is not one JSON object.");
    }
    JsonNode root = parsed.get();
    if (!onlyFields(root, ANNOTATION_FIELDS, ANNOTATION_FIELDS)) {
      return failure("The annotation must have exactly the members wpId, row and context.");
    }
    Optional<String> wpId = wpId(root.get("wpId"));
    if (wpId.isEmpty()) {
      return failure("The wpId is not a work package identifier.");
    }
    JsonNode row = root.get("row");
    Optional<Malformed> invalid = row(row, wpId.get(), missionId);
    if (invalid.isEmpty()) {
      invalid = RowRules.annotation(row);
    }
    if (invalid.isPresent()) {
      return new Parsed.Failure(invalid.get());
    }
    Optional<Malformed> context = context(root.get("context"));
    if (context.isPresent()) {
      return new Parsed.Failure(context.get());
    }
    WriteRequest.Row typedRow = typedRow(row);
    WriteRequest.UnitRequest unit = new WriteRequest.UnitRequest(wpId.get(), null, List.of(typedRow));
    return new Parsed.Success(new WriteRequest(missionId, WriteRequest.Kind.ANNOTATION, List.of(unit), typedContext(root.get("context"))));
  }

  private static Parsed parseTypedUnit(String missionId, JsonNode unit) {
    if (!onlyFields(unit, UNIT_REQUIRED, UNIT_FIELDS)) {
      return failure("A unit must have exactly the members wpId, rows and context, and optionally operation.");
    }
    Optional<String> wpId = wpId(unit.get("wpId"));
    if (wpId.isEmpty()) {
      return failure("The wpId is not a work package identifier.");
    }
    WriteRequest.Operation operation = operation(unit.get("operation"));
    if (operation == null) {
      return failure("The unit's operation is not a WriteOperation value.");
    }
    JsonNode rows = unit.get("rows");
    if (!rows.isArray() || rows.isEmpty()) {
      return failure("A unit must hold at least one row.");
    }
    List<WriteRequest.Row> typedRows = new ArrayList<>(rows.size());
    for (int i = 0; i < rows.size(); i++) {
      JsonNode row = rows.get(i);
      Optional<Malformed> refused = row(row, wpId.get(), missionId);
      if (refused.isEmpty()) {
        refused = i == 0 ? RowRules.transition(row) : RowRules.annotation(row);
      }
      if (refused.isPresent()) {
        return new Parsed.Failure(refused.get());
      }
      typedRows.add(typedRow(row));
    }
    Optional<Malformed> context = context(unit.get("context"));
    if (context.isPresent()) {
      return new Parsed.Failure(context.get());
    }
    WriteRequest.UnitRequest typedUnit = new WriteRequest.UnitRequest(wpId.get(), operation, typedRows);
    return new Parsed.Success(new WriteRequest(missionId, WriteRequest.Kind.UNIT, List.of(typedUnit), typedContext(unit.get("context"))));
  }

  private static WriteRequest.Operation operation(JsonNode operation) {
    if (operation == null) {
      return WriteRequest.Operation.TRANSITION;
    }
    if (!operation.isString()) {
      return null;
    }
    return switch (operation.asString()) {
      case "transition" -> WriteRequest.Operation.TRANSITION;
      case "start_implementation" -> WriteRequest.Operation.START_IMPLEMENTATION;
      case "start_review" -> WriteRequest.Operation.START_REVIEW;
      default -> null;
    };
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

  private static Optional<Malformed> sharedLocation(JsonNode context, JsonNode first, int index) {
    for (String field : SHARED_BATCH_FIELDS) {
      if (!context.get(field).equals(first.get(field))) {
        return Optional.of(new Malformed("Every unit of a batch must share missionDirectory, writeSurface and destinationRef.", "#/units/" + index));
      }
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
    if (COORDINATION_REF_COMMIT.equals(surface.asString()) && (!destination.isString() || destination.asString().isEmpty())) {
      return bad("The context's destinationRef must be a non-empty string for coordination_ref_commit.");
    }
    return Optional.empty();
  }

  private static WriteRequest.Context typedContext(JsonNode context) {
    return new WriteRequest.Context(
        context.get("actor").asString(),
        context.get("checkout").asString(),
        context.get("missionDirectory").asString(),
        context.get("writeSurface").asString(),
        context.get("destinationRef").isNull() ? null : context.get("destinationRef").asString());
  }

  private static WriteRequest.Row typedRow(JsonNode row) {
    return new WriteRequest.Row(row.get("event_id").asString(), jsonValue(row));
  }

  /** Objects retain document order; integers stay arbitrary precision and floating-point values stay doubles, including -0.0. */
  static Object jsonValue(JsonNode node) {
    if (node.isObject()) {
      Map<String, Object> value = new LinkedHashMap<>();
      for (Map.Entry<String, JsonNode> member : node.properties()) {
        value.put(member.getKey(), jsonValue(member.getValue()));
      }
      return value;
    }
    if (node.isArray()) {
      List<Object> value = new ArrayList<>(node.size());
      for (JsonNode item : node) {
        value.add(jsonValue(item));
      }
      return value;
    }
    if (node.isString()) {
      return node.asString();
    }
    if (node.isIntegralNumber()) {
      return node.bigIntegerValue();
    }
    if (node.isFloatingPointNumber()) {
      return node.doubleValue();
    }
    if (node.isBoolean()) {
      return node.booleanValue();
    }
    if (node.isNull()) {
      return null;
    }
    throw new IllegalArgumentException("Unsupported JSON value");
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

  private static Parsed failure(String detail) {
    return new Parsed.Failure(new Malformed(detail, null));
  }

  private static Optional<Malformed> bad(String detail) {
    return Optional.of(new Malformed(detail, null));
  }
}
