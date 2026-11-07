package org.speckitty.missionstatus.adapter.out.filesystem.codec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

class StatusRowEncoderTest {

  @Test
  void scalarsArraysAndObjectsUsePythonDumpsSpacing() {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("empty_object", Map.of());
    value.put("empty_array", List.of());
    value.put("values", java.util.Arrays.asList("x", BigInteger.ONE, 1.0, true, false, null));
    assertThat(StatusRowEncoder.encode(value))
        .isEqualTo("{\"empty_array\": [], \"empty_object\": {}, \"values\": [\"x\", 1, 1.0, true, false, null]}");
  }

  @Test
  void everyObjectDepthSortsKeysByUnicodeCodePoint() {
    Object decoded = PythonJson.parse("{\"😀\":0,\"\ufffd\":1,\"z\":{\"😀\":0,\"\ufffd\":1,\"a\":2},\"\":3}");
    assertThat(StatusRowEncoder.encode(decoded))
        .isEqualTo("{\"\": 3, \"z\": {\"a\": 2, \"\\ufffd\": 1, \"\\ud83d\\ude00\": 0}, "
            + "\"\\ufffd\": 1, \"\\ud83d\\ude00\": 0}");
  }

  @Test
  void stringsUseOnlyPythonsAsciiEscapeSet() {
    String input = "\b\f\n\r\t\u0000\u0001\u001f /\"\\ ~\u007f\u0080é\u2028😀\ud800";
    assertThat(StatusRowEncoder.encode(input))
        .isEqualTo("\"\\b\\f\\n\\r\\t\\u0000\\u0001\\u001f /\\\"\\\\ ~"
            + "\\u007f\\u0080\\u00e9\\u2028\\ud83d\\ude00\\ud800\"");
  }

  @Test
  void bigNegativeIntegersAndFloatsKeepTheirPythonNumberKinds() {
    BigInteger huge = new BigInteger("-1234567890123456789012345678901234567890");
    assertThat(StatusRowEncoder.encode(List.of(huge, -0.0, 100.0, 1e16)))
        .isEqualTo("[-1234567890123456789012345678901234567890, -0.0, 100.0, 1e+16]");
  }

  @Test
  void encodeLineSanitizesProducesAsciiAndEndsInExactlyOneLf() {
    byte[] bytes = StatusRowEncoder.encodeLine(row("hostname", "h", "text", "é", "line", "x\n"));
    assertThat(new String(bytes, StandardCharsets.US_ASCII))
        .isEqualTo("{\"line\": \"x\\n\", \"text\": \"\\u00e9\"}\n");
    assertThat(java.util.stream.IntStream.range(0, bytes.length).allMatch(index -> bytes[index] >= 0)).isTrue();
    assertThat(bytes[bytes.length - 2]).isNotEqualTo((byte) '\r');
  }

  @Test
  void emptyAndPiiOnlyRowsEncodeAsEmptyObjects() {
    assertThat(StatusRowEncoder.encodeLine(Map.of())).isEqualTo("{}\n".getBytes(StandardCharsets.US_ASCII));
    assertThat(StatusRowEncoder.encodeLine(row("machine_name", "m"))).isEqualTo("{}\n".getBytes(StandardCharsets.US_ASCII));
  }

  @Test
  void deepNestingsAndListsOfListsOfMapsAreHandled() {
    Object value = row("hostname", "h", "keep", "leaf");
    for (int depth = 0; depth < 10; depth++) {
      value = List.of(row("next", value));
    }
    Object sanitized = StatusRowSanitizer.sanitize(row("nested", value)).get("nested");
    String encoded = StatusRowEncoder.encode(sanitized);
    assertThat(encoded).contains("{\"keep\": \"leaf\"}").doesNotContain("hostname");
    assertThat(PythonJson.parse(encoded)).isEqualTo(sanitized);
  }

  @Test
  void unsupportedJavaValuesAndNonStringKeysAreRejected() {
    assertThatThrownBy(() -> StatusRowEncoder.encode(1)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> StatusRowEncoder.encode(new Object())).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> StatusRowEncoder.encode(Map.of(1, "x"))).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void decoderUsesPythonJsonForValidNonFiniteAndInvalidInput() {
    assertThat(StatusRowDecoder.parse("{\"a\": 1.0}\n")).isEqualTo(row("a", 1.0));
    assertThat(StatusRowDecoder.parse("[NaN, Infinity, -Infinity]"))
        .isEqualTo(List.of(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY));
    for (String invalid : List.of("", "{", "1 2", "abc")) {
      assertThatThrownBy(() -> StatusRowDecoder.parse(invalid)).isInstanceOf(RuntimeException.class);
    }
  }

  @Test
  void seededRowsRoundTripThroughThePythonShapedDecoder() {
    Random random = new Random(0x5eedc0deL);
    for (int index = 0; index < 64; index++) {
      Object row = randomValue(random, 2, true);
      assertThat(PythonJson.parse(StatusRowEncoder.encode(row))).as("row %s", index).isEqualTo(row);
    }
  }

  private static Object randomValue(Random random, int depth, boolean object) {
    if (depth > 0 && (object || random.nextInt(4) == 0)) {
      int size = random.nextInt(5);
      if (random.nextBoolean()) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int index = 0; index < size; index++) {
          map.put(randomString(random), randomValue(random, depth - 1, false));
        }
        return map;
      }
      List<Object> list = new ArrayList<>();
      for (int index = 0; index < size; index++) {
        list.add(randomValue(random, depth - 1, false));
      }
      return list;
    }
    return switch (random.nextInt(7)) {
      case 0 -> null;
      case 1 -> random.nextBoolean();
      case 2 -> new BigInteger(96, random).multiply(random.nextBoolean() ? BigInteger.ONE : BigInteger.valueOf(-1));
      case 3 -> randomFiniteDouble(random);
      case 4 -> randomString(random);
      case 5 -> BigInteger.valueOf(random.nextLong());
      default -> List.of();
    };
  }

  private static Double randomFiniteDouble(Random random) {
    double value = Double.longBitsToDouble(random.nextLong());
    return Double.isFinite(value) ? value : -0.0;
  }

  private static String randomString(Random random) {
    StringBuilder value = new StringBuilder();
    int length = random.nextInt(16);
    for (int index = 0; index < length; index++) {
      value.append((char) random.nextInt(0x10000));
    }
    return value.toString();
  }

  private static Map<String, Object> row(Object... pairs) {
    Map<String, Object> row = new LinkedHashMap<>();
    for (int index = 0; index < pairs.length; index += 2) {
      row.put((String) pairs[index], pairs[index + 1]);
    }
    return row;
  }
}
