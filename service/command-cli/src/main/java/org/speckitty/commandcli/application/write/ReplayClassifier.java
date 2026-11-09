package org.speckitty.commandcli.application.write;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.speckitty.commandcli.application.write.port.RowCodec;
import org.speckitty.commandcli.domain.authority.LogLine;

/** FR-008 first-occurrence, byte-equality replay classification. */
public final class ReplayClassifier {
  public sealed interface Result permits Classified, Conflict {}
  public record Classified(List<Unit> units) implements Result {}
  public record Conflict(Integer unitIndex) implements Result {}
  public record Unit(int index, WriteRequest.UnitRequest request, List<byte[]> encodedRows, boolean replayed) {
    public Unit {
      encodedRows = List.copyOf(encodedRows);
    }
    public List<String> eventIds() {
      return request.rows().stream().map(WriteRequest.Row::eventId).toList();
    }
  }

  private ReplayClassifier() {}

  public static Result classify(WriteRequest request, RowCodec.DecodedLog log, RowCodec codec) {
    Map<String, byte[]> firstOccurrence = firstOccurrences(log);
    List<Unit> units = new ArrayList<>();
    boolean sawNew = false;
    for (int index = 0; index < request.units().size(); index++) {
      Unit unit = classifyUnit(index, request.units().get(index), firstOccurrence, codec);
      if (unit == null || (sawNew && unit.replayed())) return new Conflict(batchIndex(request, index));
      sawNew |= !unit.replayed();
      units.add(unit);
    }
    return new Classified(units);
  }

  private static Unit classifyUnit(int index, WriteRequest.UnitRequest request, Map<String, byte[]> logged, RowCodec codec) {
    List<byte[]> encoded = request.rows().stream().map(row -> codec.encodeLine(cast(row.value()))).toList();
    int present = 0;
    for (int row = 0; row < request.rows().size(); row++) {
      byte[] loggedBytes = logged.get(request.rows().get(row).eventId());
      if (loggedBytes != null) {
        if (!Arrays.equals(withoutLf(encoded.get(row)), loggedBytes)) return null;
        present++;
      }
    }
    if (present != 0 && present != request.rows().size()) return null;
    return new Unit(index, request, encoded, present == request.rows().size());
  }

  private static Map<String, byte[]> firstOccurrences(RowCodec.DecodedLog log) {
    Map<String, byte[]> first = new HashMap<>();
    for (RowCodec.Line line : log.lines()) {
      if (line.value() instanceof LogLine.Value value && value.row().get("event_id") instanceof String id) {
        first.putIfAbsent(id, line.bytesWithoutLfOrCr());
      }
    }
    return first;
  }

  private static byte[] withoutLf(byte[] encoded) {
    return Arrays.copyOf(encoded, encoded.length - 1);
  }

  private static Integer batchIndex(WriteRequest request, int index) {
    return request.kind() == WriteRequest.Kind.BATCH ? index : null;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> cast(Object value) {
    return (Map<String, Object>) value;
  }
}
