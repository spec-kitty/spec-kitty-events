package org.speckitty.missionstatus.adapter.out.filesystem.codec;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.speckitty.missionstatus.domain.model.CodePoints;

/** Core-compatible {@code json.dumps(..., sort_keys=True)} for one Python-shaped status row. */
public final class StatusRowEncoder {

  private static final char[] HEX = "0123456789abcdef".toCharArray();

  private StatusRowEncoder() {}

  /** Returns the dump without a trailing newline. */
  public static String encode(Object value) {
    StringBuilder out = new StringBuilder();
    write(value, out);
    return out.toString();
  }

  /** Sanitizes and returns pure-ASCII JSON followed by exactly one LF. */
  public static byte[] encodeLine(Map<String, Object> row) {
    return (encode(StatusRowSanitizer.sanitize(row)) + "\n").getBytes(StandardCharsets.US_ASCII);
  }

  private static void write(Object value, StringBuilder out) {
    if (value == null) {
      out.append("null");
    } else if (value instanceof String text) {
      writeString(text, out);
    } else if (value instanceof Map<?, ?> mapping) {
      writeObject(mapping, out);
    } else if (value instanceof List<?> list) {
      writeArray(list, out);
    } else if (value instanceof BigInteger integer) {
      out.append(integer);
    } else if (value instanceof Double number) {
      out.append(PythonFloatRepr.repr(number));
    } else if (value instanceof Boolean bool) {
      out.append(bool ? "true" : "false");
    } else {
      throw new IllegalArgumentException("not a PythonJson value: " + value.getClass().getName());
    }
  }

  private static void writeObject(Map<?, ?> mapping, StringBuilder out) {
    List<String> keys = stringKeys(mapping);
    keys.sort(CodePoints::compare);
    out.append('{');
    for (int index = 0; index < keys.size(); index++) {
      if (index > 0) {
        out.append(", ");
      }
      String key = keys.get(index);
      writeString(key, out);
      out.append(": ");
      write(mapping.get(key), out);
    }
    out.append('}');
  }

  private static List<String> stringKeys(Map<?, ?> mapping) {
    List<String> keys = new ArrayList<>(mapping.size());
    for (Object key : mapping.keySet()) {
      if (!(key instanceof String text)) {
        throw new IllegalArgumentException("JSON object keys must be strings");
      }
      keys.add(text);
    }
    return keys;
  }

  private static void writeArray(List<?> list, StringBuilder out) {
    out.append('[');
    for (int index = 0; index < list.size(); index++) {
      if (index > 0) {
        out.append(", ");
      }
      write(list.get(index), out);
    }
    out.append(']');
  }

  private static void writeString(String value, StringBuilder out) {
    out.append('"');
    for (int index = 0; index < value.length(); index++) {
      char character = value.charAt(index);
      switch (character) {
        case '"' -> out.append("\\\"");
        case '\\' -> out.append("\\\\");
        case '\b' -> out.append("\\b");
        case '\f' -> out.append("\\f");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\t' -> out.append("\\t");
        default -> writeOrdinaryCharacter(character, out);
      }
    }
    out.append('"');
  }

  private static void writeOrdinaryCharacter(char character, StringBuilder out) {
    if (character >= 0x20 && character <= 0x7e) {
      out.append(character);
      return;
    }
    out.append("\\u");
    out.append(HEX[(character >>> 12) & 0xf]);
    out.append(HEX[(character >>> 8) & 0xf]);
    out.append(HEX[(character >>> 4) & 0xf]);
    out.append(HEX[character & 0xf]);
  }
}
