package org.speckitty.commandcli.adapter.out.statuslog;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
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
import org.speckitty.missionstatus.domain.model.PyText;
import tools.jackson.core.StreamReadConstraints;

/** command-cli's application port over the event-log module's canonical codec. */
public final class EventLogRowCodec implements RowCodec {
  static {
    StreamReadConstraints.overrideDefaultStreamReadConstraints(StreamReadConstraints.defaults().rebuild()
        .maxNestingDepth(1000)
        .maxNumberLength(4300)
        .build());
  }

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
    if (!validUtf8(log)) return new DecodedLog(List.of(new Line(new LogLine.Unparseable(), log)));
    List<Line> lines = new ArrayList<>();
    int start = 0;
    int index = 0;
    while (index < log.length) {
      int separator = separatorLength(log, index);
      if (separator == 0) {
        index++;
        continue;
      }
      lines.add(line(Arrays.copyOfRange(log, start, index)));
      index += separator;
      start = index;
    }
    if (start < log.length) lines.add(line(Arrays.copyOfRange(log, start, log.length)));
    return new DecodedLog(lines);
  }

  private static int separatorLength(byte[] log, int index) {
    int value = log[index] & 0xff;
    if (value == '\r') return index + 1 < log.length && log[index + 1] == '\n' ? 2 : 1;
    if (value == '\n' || value == 0x0b || value == 0x0c || value >= 0x1c && value <= 0x1e) return 1;
    if (value == 0xc2 && index + 1 < log.length && (log[index + 1] & 0xff) == 0x85) return 2;
    if (value == 0xe2 && index + 2 < log.length && (log[index + 1] & 0xff) == 0x80
        && ((log[index + 2] & 0xff) == 0xa8 || (log[index + 2] & 0xff) == 0xa9)) return 3;
    return 0;
  }

  private static Line line(byte[] raw) {
    String text = new String(raw, StandardCharsets.UTF_8);
    if (PyText.isBlank(text)) return new Line(new LogLine.Blank(), raw);
    try {
      if (StatusRowDecoder.parse(PyText.strip(text)) instanceof Map<?, ?> value) {
        @SuppressWarnings("unchecked")
        Map<String, Object> row = (Map<String, Object>) value;
        return new Line(new LogLine.Value(row), raw);
      }
    } catch (RuntimeException ignored) {
      // An unparseable physical line makes the whole log unreducible in WriteFold.
    }
    return new Line(new LogLine.Unparseable(), raw);
  }

  private static boolean validUtf8(byte[] log) {
    try {
      StandardCharsets.UTF_8.newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(log));
      return true;
    } catch (CharacterCodingException invalid) {
      return false;
    }
  }
}
