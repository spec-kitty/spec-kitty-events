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
    if (!validUtf8(log) || hasRawSeparator(log)) return new DecodedLog(List.of(new Line(new LogLine.Unparseable(), log)));
    List<Line> lines = new ArrayList<>();
    int start = 0;
    for (int index = 0; index < log.length; index++) {
      if (log[index] != '\n') continue;
      lines.add(line(Arrays.copyOfRange(log, start, index > start && log[index - 1] == '\r' ? index - 1 : index)));
      start = index + 1;
    }
    if (start < log.length) lines.add(line(Arrays.copyOfRange(log, start, log.length)));
    return new DecodedLog(lines);
  }

  /**
   * R24: U+0085, U+2028, U+2029, VT, FF, FS, GS and RS are never written raw, and core's Python {@code splitlines()}
   * would split a row on them. The bytes are already valid UTF-8, so a byte scan is exact.
   */
  private static boolean hasRawSeparator(byte[] log) {
    for (int index = 0; index < log.length; index++) {
      if (separatorAt(log, index)) return true;
    }
    return false;
  }

  private static boolean separatorAt(byte[] log, int index) {
    int value = log[index] & 0xff;
    if (value == 0x0b || value == 0x0c || value >= 0x1c && value <= 0x1e) return true;
    if (value == 0xc2) return next(log, index, 1) == 0x85;
    return value == 0xe2 && next(log, index, 1) == 0x80 && (next(log, index, 2) == 0xa8 || next(log, index, 2) == 0xa9);
  }

  private static int next(byte[] log, int index, int offset) {
    return index + offset < log.length ? log[index + offset] & 0xff : -1;
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
