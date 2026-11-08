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
  private static final java.util.Set<Integer> SINGLE_BYTE_SEPARATORS = java.util.Set.of(0x0a, 0x0b, 0x0c, 0x1c, 0x1d, 0x1e);

  static {
    StreamReadConstraints.overrideDefaultStreamReadConstraints(
        new PythonNumberConstraints(StreamReadConstraints.defaults()));
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

  /** Python caps integer conversion at 4300 digits but does not apply that limit to floats. */
  private static final class PythonNumberConstraints extends StreamReadConstraints {
    private static final long serialVersionUID = 1L;

    private PythonNumberConstraints(StreamReadConstraints defaults) {
      super(1000, defaults.getMaxDocumentLength(), defaults.getMaxTokenCount(), 4300,
          defaults.getMaxStringLength(), defaults.getMaxNameLength());
    }

    @Override
    public void validateFPLength(int length) {
      // Deliberately unbounded, like Python's float parser.
    }
  }

  private static int separatorLength(byte[] log, int index) {
    int value = log[index] & 0xff;
    if (value == '\r') return index + 1 < log.length && log[index + 1] == '\n' ? 2 : 1;
    if (SINGLE_BYTE_SEPARATORS.contains(value)) return 1;
    if (matches(log, index, 0xc2, 0x85)) return 2;
    if (matches(log, index, 0xe2, 0x80, 0xa8) || matches(log, index, 0xe2, 0x80, 0xa9)) return 3;
    return 0;
  }

  private static boolean matches(byte[] bytes, int start, int... expected) {
    if (start + expected.length > bytes.length) return false;
    for (int offset = 0; offset < expected.length; offset++) {
      if ((bytes[start + offset] & 0xff) != expected[offset]) return false;
    }
    return true;
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
