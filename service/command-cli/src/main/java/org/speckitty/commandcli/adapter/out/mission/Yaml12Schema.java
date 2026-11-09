package org.speckitty.commandcli.adapter.out.mission;

import java.math.BigInteger;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.constructor.AbstractConstruct;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeId;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.Tag;
import org.yaml.snakeyaml.resolver.Resolver;

/**
 * The YAML 1.2.2 core schema (section 10.3.2) for plain scalars, which SnakeYAML does not implement.
 *
 * <p>SnakeYAML resolves with YAML 1.1 rules ({@code 1:20}, {@code 010}, {@code 1_000}, {@code yes}).
 * Core's ruamel resolves by 1.2, so those forms must stay strings here too. Where ruamel itself
 * deviates from the 1.2 text (underscore and signed {@code 0b}/{@code 0o}/{@code 0x} integers and
 * floats) the scalar is refused rather than copied (WP08 audit).
 */
final class Yaml12Schema {
  /** The most decimal digits CPython converts to or from an int (R29). */
  static final int MAX_DECIMAL_DIGITS = 4300;
  private static final BigInteger DECIMAL_LIMIT = BigInteger.TEN.pow(MAX_DECIMAL_DIGITS);
  /** R30: ruamel rounds a seventh fractional digit into the seconds, which can overflow {@code datetime}. */
  private static final Pattern LONG_FRACTION = Pattern.compile("\\.[0-9]{7}");

  // The numeric spellings ruamel's 1.2 resolver accepts: the text allows none of the extra forms.
  private static final Pattern RUAMEL_NUMBER = Pattern.compile(
      "^(?=[-+0-9.])[-+]?(?:0b[01_]+|0o?[0-7_]+|[0-9_]+|0x[0-9a-fA-F_]+"
          + "|[0-9][0-9_]*\\.[0-9_]*(?:[eE][-+]?[0-9]+)?|[0-9][0-9_]*[eE][-+]?[0-9]+|\\.[0-9_]+(?:[eE][-+][0-9]+)?)$");
  private static final Pattern TIMESTAMP_PARTS = Pattern.compile(
      "^(\\d{4})-(\\d\\d?)-(\\d\\d?)(?:(?:[Tt]|[ \\t]+)(\\d\\d?):(\\d\\d):(\\d\\d)(?:\\.\\d*)?"
          + "(?:[ \\t]*(?:Z|([-+])(\\d\\d?)(?::(\\d\\d))?))?)?$");

  private Yaml12Schema() {}

  static Resolver resolver() {
    return new Yaml12Resolver();
  }

  static Constructor constructor(LoaderOptions options) {
    return new Constructor(options);
  }

  /**
   * R29, the one shared check over the constructed tree: an integer of any base whose decimal form exceeds 4300 digits,
   * anywhere (any field, nested in lists and maps, keys included), makes the document unresolvable. CPython refuses to
   * {@code str()} such an int (a decimal one cannot even be built, see {@link Constructor}), and core renders
   * {@code phase}, roster ids and more with {@code str()}. Hex and octal are unlimited when parsed, so they pass the
   * constructor and are caught here.
   */
  static void requireRenderableIntegers(Object value) {
    if (value instanceof BigInteger number) {
      if (number.abs().compareTo(DECIMAL_LIMIT) >= 0) throw new IllegalArgumentException("R29: an integer over 4300 decimal digits");
    } else if (value instanceof Map<?, ?> map) {
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        requireRenderableIntegers(entry.getKey());
        requireRenderableIntegers(entry.getValue());
      }
    } else if (value instanceof Iterable<?> items) {
      for (Object item : items) requireRenderableIntegers(item);
    }
  }

  /** Ordered like the spec's table: an integer is tried before a float, which also matches one. */
  private static final List<Map.Entry<Tag, Pattern>> RULES = List.of(
      Map.entry(Tag.NULL, Pattern.compile("^(?:~|null|Null|NULL)?$")),
      Map.entry(Tag.BOOL, Pattern.compile("^(?:true|True|TRUE|false|False|FALSE)$")),
      Map.entry(Tag.INT, Pattern.compile("^(?:[-+]?[0-9]+|0o[0-7]+|0x[0-9a-fA-F]+)$")),
      Map.entry(Tag.FLOAT, Pattern.compile(
          "^(?:[-+]?(?:\\.[0-9]+|[0-9]+(?:\\.[0-9]*)?)(?:[eE][-+]?[0-9]+)?|[-+]?\\.(?:inf|Inf|INF)|\\.(?:nan|NaN|NAN))$")),
      Map.entry(Tag.MERGE, Pattern.compile("^<<$")),
      Map.entry(Tag.TIMESTAMP, Pattern.compile(
          "^(?:[0-9]{4}-[0-9]{2}-[0-9]{2}|[0-9]{4}-[0-9][0-9]?-[0-9][0-9]?(?:[Tt]|[ \\t]+)[0-9][0-9]?:[0-9]{2}:[0-9]{2}"
              + "(?:\\.[0-9]*)?(?:[ \\t]*(?:Z|[-+][0-9][0-9]?(?::[0-9]{2})?))?)$")));

  /** SnakeYAML's own resolver is bypassed: it never resolves a scalar over 1024 characters, which would blur R29. */
  private static final class Yaml12Resolver extends Resolver {
    @Override
    public Tag resolve(NodeId kind, String value, boolean implicit) {
      if (kind != NodeId.scalar || !implicit) return super.resolve(kind, value, implicit);
      for (Map.Entry<Tag, Pattern> rule : RULES) {
        if (rule.getValue().matcher(value).matches()) return rule.getKey();
      }
      if (RUAMEL_NUMBER.matcher(value).matches()) {
        throw new IllegalArgumentException("a numeric spelling only ruamel reads as a number");
      }
      return Tag.STR;
    }
  }

  static final class Constructor extends SafeConstructor {
    private Constructor(LoaderOptions options) {
      super(options);
      yamlConstructors.put(Tag.INT, new ConstructInt());
      yamlConstructors.put(Tag.FLOAT, new ConstructFloat());
      yamlConstructors.put(Tag.TIMESTAMP, new ConstructTimestamp());
    }

    Object constructSingle(Node node) {
      return constructDocument(node);
    }

    private final class ConstructInt extends AbstractConstruct {
      @Override
      public Object construct(Node node) {
        String text = constructScalar((ScalarNode) node);
        if (text.startsWith("0o")) return new BigInteger(text.substring(2), 8);
        if (text.startsWith("0x")) return new BigInteger(text.substring(2), 16);
        int digits = text.length() - (text.charAt(0) == '+' || text.charAt(0) == '-' ? 1 : 0);
        // CPython counts the digits of the text, leading zeros included, so this is a load failure of its own.
        if (digits > MAX_DECIMAL_DIGITS) throw new IllegalArgumentException("R29: more than 4300 decimal digits");
        return new BigInteger(text);
      }
    }

    private final class ConstructFloat extends AbstractConstruct {
      @Override
      public Object construct(Node node) {
        String text = constructScalar((ScalarNode) node);
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".inf")) return lower.startsWith("-") ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
        if (lower.equals(".nan")) return Double.NaN;
        return Double.parseDouble(text);
      }
    }

    /**
     * A timestamp must be one Python's {@code datetime} can build, or core's file is unreadable. Beyond the date and time
     * fields that includes R30: a fraction of more than 6 digits is refused, because ruamel rounds the seventh digit into
     * the seconds and can overflow ({@code 9999-12-31 23:59:59.9999995}).
     */
    private final class ConstructTimestamp extends AbstractConstruct {
      @Override
      public Object construct(Node node) {
        String text = constructScalar((ScalarNode) node);
        Matcher parts = TIMESTAMP_PARTS.matcher(text);
        if (!parts.matches()) throw new IllegalArgumentException("not a timestamp");
        if (LONG_FRACTION.matcher(text).find()) throw new IllegalArgumentException("R30: more than 6 fractional digits");
        int year = Integer.parseInt(parts.group(1));
        if (year < 1) throw new IllegalArgumentException("year out of range");
        int month = Integer.parseInt(parts.group(2));
        int day = Integer.parseInt(parts.group(3));
        if (month < 1 || month > 12 || day < 1 || day > YearMonth.of(year, month).lengthOfMonth()) {
          throw new IllegalArgumentException("date out of range");
        }
        if (parts.group(4) == null) return LocalDate.of(year, month, day);
        if (parts.group(7) != null) {
          int minutes = Integer.parseInt(parts.group(8)) * 60 + (parts.group(9) == null ? 0 : Integer.parseInt(parts.group(9)));
          if (minutes >= 24 * 60) throw new IllegalArgumentException("offset out of range");
        }
        return LocalDateTime.of(year, month, day,
            Integer.parseInt(parts.group(4)), Integer.parseInt(parts.group(5)), Integer.parseInt(parts.group(6)));
      }
    }
  }
}
