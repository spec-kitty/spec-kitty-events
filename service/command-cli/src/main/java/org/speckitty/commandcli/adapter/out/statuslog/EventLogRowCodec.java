package org.speckitty.commandcli.adapter.out.statuslog;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.speckitty.commandcli.application.write.port.RowCodec;
import org.speckitty.commandcli.domain.authority.LogLine;
import org.speckitty.missionstatus.adapter.out.filesystem.codec.StatusRowDecoder;
import org.speckitty.missionstatus.adapter.out.filesystem.codec.StatusRowEncoder;
import org.speckitty.missionstatus.adapter.out.filesystem.codec.StatusRowRefusedException;
import org.speckitty.missionstatus.adapter.out.filesystem.codec.StatusRowSanitizer;

/** command-cli's application port over the event-log module's canonical codec. */
public final class EventLogRowCodec implements RowCodec {
  @Override
  public String refusal(Map<String, Object> row) {
    try {
      StatusRowSanitizer.check(row);
      return null;
    } catch (StatusRowRefusedException refused) {
      return refused.getMessage();
    }
  }

  @Override
  public byte[] encodeLine(Map<String, Object> row) {
    return StatusRowEncoder.encodeLine(row);
  }

  @Override
  public DecodedLog decode(byte[] log) {
    List<Line> lines = new ArrayList<>();
    int start = 0;
    for (int index = 0; index < log.length; index++) {
      if (log[index] == '\n') {
        lines.add(line(Arrays.copyOfRange(log, start, index)));
        start = index + 1;
      }
    }
    if (start < log.length) lines.add(line(Arrays.copyOfRange(log, start, log.length)));
    return new DecodedLog(lines);
  }

  private static Line line(byte[] raw) {
    byte[] bytes = raw.length > 0 && raw[raw.length - 1] == '\r' ? Arrays.copyOf(raw, raw.length - 1) : raw;
    String text = new String(bytes, StandardCharsets.UTF_8);
    if (text.isBlank()) return new Line(new LogLine.Blank(), bytes);
    try {
      if (StatusRowDecoder.parse(text) instanceof Map<?, ?> value) {
        @SuppressWarnings("unchecked")
        Map<String, Object> row = (Map<String, Object>) value;
        return new Line(new LogLine.Value(row), bytes);
      }
    } catch (RuntimeException ignored) {
      // An unparseable physical line makes the whole log unreducible in WriteFold.
    }
    return new Line(new LogLine.Unparseable(), bytes);
  }
}
