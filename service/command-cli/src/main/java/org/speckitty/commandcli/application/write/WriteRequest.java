package org.speckitty.commandcli.application.write;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** One validated write request, with JSON values in the shape Python's {@code json.loads} produces. All containers are deeply immutable. */
public record WriteRequest(String missionId, Kind kind, List<UnitRequest> units, Context context) {

  public WriteRequest {
    Objects.requireNonNull(missionId, "missionId");
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(context, "context");
    units = List.copyOf(Objects.requireNonNull(units, "units"));
  }

  public enum Kind {
    UNIT,
    BATCH,
    ANNOTATION
  }

  /**
   * One work package's transition and annotations; {@code operation} is null only for a standalone annotation. {@code
   * implementationEvidencePresent} is the request-only evidence flag of the unforced in_progress to for_review guard (null: absent or null
   * on the wire); it is never part of a row.
   */
  public record UnitRequest(String wpId, Operation operation, List<Row> rows, Boolean implementationEvidencePresent) {
    public UnitRequest {
      Objects.requireNonNull(wpId, "wpId");
      rows = List.copyOf(Objects.requireNonNull(rows, "rows"));
    }

    /** A unit without the request-only evidence flag (null means absent; it is never persisted). */
    public UnitRequest(String wpId, Operation operation, List<Row> rows) {
      this(wpId, operation, rows, null);
    }
  }

  public enum Operation {
    TRANSITION("transition"),
    START_IMPLEMENTATION("start_implementation"),
    START_REVIEW("start_review");

    private final String wireValue;

    Operation(String wireValue) {
      this.wireValue = wireValue;
    }

    public String wireValue() {
      return wireValue;
    }
  }

  /** One event id and its insertion-ordered tree of String, BigInteger, Double, Boolean, null, List and Map values. */
  public record Row(String eventId, Object value) {
    public Row {
      Objects.requireNonNull(eventId, "eventId");
      Objects.requireNonNull(value, "value");
      if (!(value instanceof Map<?, ?>)) {
        throw new IllegalArgumentException("A row value must be a JSON object");
      }
      value = immutableJson(value);
    }

    private static Object immutableJson(Object value) {
      if (value instanceof Map<?, ?> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
          if (!(entry.getKey() instanceof String key)) {
            throw new IllegalArgumentException("JSON object keys must be strings");
          }
          copy.put(key, immutableJson(entry.getValue()));
        }
        return Collections.unmodifiableMap(copy);
      }
      if (value instanceof List<?> source) {
        List<Object> copy = new ArrayList<>(source.size());
        for (Object item : source) {
          copy.add(immutableJson(item));
        }
        return Collections.unmodifiableList(copy);
      }
      return value;
    }
  }

  public record Context(String actor, String checkout, String missionDirectory, String writeSurface, String destinationRef) {
    public Context {
      Objects.requireNonNull(actor, "actor");
      Objects.requireNonNull(checkout, "checkout");
      Objects.requireNonNull(missionDirectory, "missionDirectory");
      Objects.requireNonNull(writeSurface, "writeSurface");
    }
  }
}
