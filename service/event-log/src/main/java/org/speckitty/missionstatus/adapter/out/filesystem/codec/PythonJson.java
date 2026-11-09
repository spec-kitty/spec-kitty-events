package org.speckitty.missionstatus.adapter.out.filesystem.codec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.core.json.JsonReadFeature;

/**
 * {@code json.loads} as Python's standard library reads a document, on top of Jackson's streaming parser. The result is plain Java
 * data: {@code Map} (insertion order, the last duplicate key wins), {@code List}, {@code String}, {@code BigInteger}, {@code Double},
 * {@code Boolean} and {@code null}.
 *
 * <p>Python accepts {@code NaN}, {@code Infinity} and {@code -Infinity} (and nothing else of that family), keeps the last of duplicate
 * keys, reads integers of any size, accepts an escaped lone surrogate, and refuses raw control characters in strings, comments, single
 * quotes, trailing commas, leading zeros, {@code 1.}, {@code .5}, {@code +1} and any text after the value. Jackson refuses the same
 * set by default, except that its non-numeric-number feature (needed for the first three) also admits {@code +Infinity}, {@code INF},
 * {@code +INF} and {@code -INF}; those are refused here by looking at the token text.
 */
final class PythonJson {

  /** The text is not a JSON document, to Python's reader. Carries no text of the input. */
  static final class Invalid extends RuntimeException {
    private static final long serialVersionUID = 1L;

    Invalid() {
      super("not JSON", null, false, false);
    }
  }

  /** A fixed resource bound below any depth core's stack accepts; core's own limit depends on the caller's stack. */
  static final int MAX_NESTING_DEPTH = 256;
  /** CPython's integer-conversion limit; it does not apply to floats. */
  static final int MAX_INTEGER_DIGITS = 4300;

  private static final StreamReadConstraints CONSTRAINTS = new PythonConstraints();
  /**
   * The ledger reader's factory, exactly as it was before the write path existed: Jackson's default limits (nesting 500, number length
   * 1000). The read side is unchanged (mission constraint C-010), so nothing here may set limits on it.
   */
  private static final JsonFactory FACTORY = JsonFactory.builder().enable(JsonReadFeature.ALLOW_NON_NUMERIC_NUMBERS).build();
  /** The write path's factory: the same grammar with Python's limits. Used only through {@link #parseWithPythonLimits}. */
  private static final JsonFactory PYTHON_LIMITS_FACTORY =
      JsonFactory.builder().streamReadConstraints(CONSTRAINTS).enable(JsonReadFeature.ALLOW_NON_NUMERIC_NUMBERS).build();
  private static final Set<String> NON_NUMERIC_SPELLINGS = Set.of("NaN", "Infinity", "-Infinity");

  private PythonJson() {}

  /** The parse limits of this reader, for any other parser that must accept and refuse exactly what it does. */
  static StreamReadConstraints constraints() {
    return CONSTRAINTS;
  }

  /** Nesting capped at {@link #MAX_NESTING_DEPTH}, integers at {@link #MAX_INTEGER_DIGITS} digits, floats unbounded as in Python. */
  private static final class PythonConstraints extends StreamReadConstraints {
    private static final long serialVersionUID = 1L;

    private PythonConstraints() {
      super(MAX_NESTING_DEPTH, defaults().getMaxDocumentLength(), defaults().getMaxTokenCount(), MAX_INTEGER_DIGITS,
          defaults().getMaxStringLength(), defaults().getMaxNameLength());
    }

    @Override
    public void validateFPLength(int length) {
      // Deliberately unbounded, like Python's float parser.
    }
  }

  /** The limits of the ledger reader's factory, for the test that pins them. */
  static StreamReadConstraints ledgerConstraints() {
    return FACTORY.streamReadConstraints();
  }

  /** Parses one ledger document with Jackson's default limits. Whitespace around it is the caller's business (Python strips first). */
  static Object parse(String text) {
    return parse(FACTORY, text);
  }

  /** Parses one document with {@link #constraints()}: the write path's decoding of status rows. */
  static Object parseWithPythonLimits(String text) {
    return parse(PYTHON_LIMITS_FACTORY, text);
  }

  private static Object parse(JsonFactory factory, String text) {
    try (JsonParser parser = factory.createParser(text)) {
      JsonToken first = parser.nextToken();
      if (first == null) {
        throw new Invalid();
      }
      Object value = read(parser, first);
      if (parser.nextToken() != null) {
        throw new Invalid();
      }
      return value;
    } catch (JacksonException refused) {
      throw new Invalid();
    }
  }

  private static Object read(JsonParser parser, JsonToken token) {
    return switch (token) {
      case START_OBJECT -> readObject(parser);
      case START_ARRAY -> readArray(parser);
      case VALUE_STRING -> parser.getString();
      case VALUE_NUMBER_INT -> parser.getBigIntegerValue();
      case VALUE_NUMBER_FLOAT -> readFloat(parser);
      case VALUE_TRUE -> Boolean.TRUE;
      case VALUE_FALSE -> Boolean.FALSE;
      case VALUE_NULL -> null;
      default -> throw new Invalid();
    };
  }

  private static Map<String, Object> readObject(JsonParser parser) {
    Map<String, Object> object = new LinkedHashMap<>();
    JsonToken token = parser.nextToken();
    while (token != JsonToken.END_OBJECT) {
      if (token != JsonToken.PROPERTY_NAME) {
        throw new Invalid();
      }
      String name = parser.currentName();
      JsonToken valueToken = parser.nextToken();
      if (valueToken == null) {
        throw new Invalid();
      }
      object.put(name, read(parser, valueToken));
      token = parser.nextToken();
    }
    return object;
  }

  private static List<Object> readArray(JsonParser parser) {
    List<Object> array = new ArrayList<>();
    JsonToken token = parser.nextToken();
    while (token != JsonToken.END_ARRAY) {
      if (token == null) {
        throw new Invalid();
      }
      array.add(read(parser, token));
      token = parser.nextToken();
    }
    return array;
  }

  private static Double readFloat(JsonParser parser) {
    String text = parser.getText();
    boolean signed = text.startsWith("+") || text.startsWith("-");
    boolean spelledAsAWord = !text.isEmpty() && Character.isLetter(text.charAt(signed && text.length() > 1 ? 1 : 0));
    if (spelledAsAWord && !NON_NUMERIC_SPELLINGS.contains(text)) {
      throw new Invalid();
    }
    return parser.getDoubleValue();
  }
}
