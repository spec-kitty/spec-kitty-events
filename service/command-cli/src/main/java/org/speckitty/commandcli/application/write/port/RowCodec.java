package org.speckitty.commandcli.application.write.port;

import java.util.List;
import java.util.Map;
import org.speckitty.commandcli.domain.authority.LogLine;

/** The canonical status-row codec, including physical lines needed for replay comparison. */
public interface RowCodec {
  record Line(LogLine value, byte[] bytesWithoutLfOrCr) {}
  record DecodedLog(List<Line> lines) {
    public DecodedLog {
      lines = List.copyOf(lines);
    }
    public List<LogLine> values() {
      return lines.stream().map(Line::value).toList();
    }
  }

  /** A fixed safe refusal detail, or null when the row can be encoded. */
  String refusal(Map<String, Object> row);

  byte[] encodeLine(Map<String, Object> row);

  DecodedLog decode(byte[] log);
}
