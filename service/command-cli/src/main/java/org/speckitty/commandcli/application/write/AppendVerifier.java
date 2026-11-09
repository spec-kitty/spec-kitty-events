package org.speckitty.commandcli.application.write;

import java.util.ArrayList;
import java.util.List;
import org.speckitty.commandcli.application.write.port.RowCodec;
import org.speckitty.commandcli.domain.authority.LogLine;
import org.speckitty.commandcli.domain.authority.UnreducibleLog;
import org.speckitty.commandcli.domain.authority.WriteFold;

/**
 * Proves, before anything is appended, that the service can read its own write back: the encoded bytes of every new row decode, with
 * the log reader's codec, to one row each, and the log followed by those rows folds with the fold the next write will use. A row that
 * fails either check must never be acknowledged, because it would make every later write to the Mission refuse (unreducible log).
 */
final class AppendVerifier {
  private AppendVerifier() {}

  /** The position, among {@code newUnits}, of the first unit the service could not read back, or -1 when all of them can be read. */
  static int firstUnreadable(RowCodec codec, RowCodec.DecodedLog log, List<ReplayClassifier.Unit> newUnits) {
    List<List<LogLine>> decoded = new ArrayList<>(newUnits.size());
    for (int position = 0; position < newUnits.size(); position++) {
      List<LogLine> rows = decodeRows(codec, newUnits.get(position));
      if (rows == null) return position;
      decoded.add(rows);
    }
    List<LogLine> lines = new ArrayList<>(log.values());
    decoded.forEach(lines::addAll);
    if (folds(lines)) return -1;
    return firstUnfoldable(log, decoded);
  }

  /** The rows one unit would append, as the reader decodes them, or null when a row does not decode to exactly one row. */
  private static List<LogLine> decodeRows(RowCodec codec, ReplayClassifier.Unit unit) {
    List<LogLine> rows = new ArrayList<>(unit.encodedRows().size());
    for (byte[] encoded : unit.encodedRows()) {
      List<RowCodec.Line> lines = codec.decode(encoded).lines().stream()
          .filter(line -> !(line.value() instanceof LogLine.Blank)).toList();
      if (lines.size() != 1 || !(lines.getFirst().value() instanceof LogLine.Value)) return null;
      rows.add(lines.getFirst().value());
    }
    return rows;
  }

  /** Every earlier prefix folded, so the first unit whose addition makes the fold fail is the one that cannot be read back. */
  private static int firstUnfoldable(RowCodec.DecodedLog log, List<List<LogLine>> decoded) {
    List<LogLine> lines = new ArrayList<>(log.values());
    for (int position = 0; position < decoded.size(); position++) {
      lines.addAll(decoded.get(position));
      if (!folds(lines)) return position;
    }
    return decoded.size() - 1;
  }

  private static boolean folds(List<LogLine> lines) {
    try {
      WriteFold.fold(lines);
      return true;
    } catch (UnreducibleLog unreadable) {
      return false;
    }
  }
}
