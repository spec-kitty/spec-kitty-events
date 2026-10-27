package ai.speckitty.events.cloudevents;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import tools.jackson.databind.JsonNode;

/**
 * Canonical serialization of CloudEvents rows: the bytes the upcast goldens pin.
 *
 * <p>The same rules as the Python binding's {@code canonical_bytes}: UTF-8 JSON, object keys
 * sorted by code point, {@code ,} and {@code :} separators with no whitespace, non-ASCII
 * written as is, integers as written and floating-point numbers in Python's shortest
 * round-trip {@code repr} form ({@code 1e-05}, {@code 1e+16}, {@code 2.0}).
 */
public final class CanonicalJson {

    private static final Comparator<String> CODE_POINT_ORDER = (a, b) -> {
        int i = 0;
        int j = 0;
        while (i < a.length() && j < b.length()) {
            int ca = a.codePointAt(i);
            int cb = b.codePointAt(j);
            if (ca != cb) {
                return Integer.compare(ca, cb);
            }
            i += Character.charCount(ca);
            j += Character.charCount(cb);
        }
        return Integer.compare(a.length() - i, b.length() - j);
    };

    private CanonicalJson() {}

    /** The canonical text of a JSON value. */
    public static String text(JsonNode node) {
        StringBuilder out = new StringBuilder();
        write(node, out);
        return out.toString();
    }

    /** The canonical UTF-8 bytes of a JSON value. */
    public static byte[] bytes(JsonNode node) {
        return text(node).getBytes(StandardCharsets.UTF_8);
    }

    private static void write(JsonNode node, StringBuilder out) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            out.append("null");
        } else if (node.isObject()) {
            List<Map.Entry<String, JsonNode>> entries = new ArrayList<>(node.properties());
            entries.sort(Map.Entry.comparingByKey(CODE_POINT_ORDER));
            out.append('{');
            boolean first = true;
            for (Map.Entry<String, JsonNode> entry : entries) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                string(entry.getKey(), out);
                out.append(':');
                write(entry.getValue(), out);
            }
            out.append('}');
        } else if (node.isArray()) {
            out.append('[');
            for (int i = 0; i < node.size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                write(node.get(i), out);
            }
            out.append(']');
        } else if (node.isString()) {
            string(node.asString(), out);
        } else if (node.isBoolean()) {
            out.append(node.booleanValue() ? "true" : "false");
        } else if (node.isIntegralNumber()) {
            out.append(node.bigIntegerValue().toString());
        } else if (node.isNumber()) {
            out.append(pythonFloat(node.doubleValue()));
        } else {
            throw new IllegalArgumentException("not a JSON value: " + node.getNodeType());
        }
    }

    private static void string(String value, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    /**
     * The fewest significant digits that read back as the same double, rounded half-even from
     * the exact value: what Python's {@code repr} prints. {@code Double.toString} is not used
     * because it prints two digits for some subnormals ({@code 4.9E-324} where Python prints
     * {@code 5e-324}).
     */
    private static BigDecimal shortestDigits(double magnitude) {
        BigDecimal exact = new BigDecimal(magnitude);
        for (int precision = 1; precision <= 17; precision++) {
            BigDecimal candidate = exact.round(new MathContext(precision, RoundingMode.HALF_EVEN));
            if (candidate.doubleValue() == magnitude) {
                return candidate.stripTrailingZeros();
            }
        }
        return exact.round(new MathContext(17, RoundingMode.HALF_EVEN)).stripTrailingZeros();
    }

    /**
     * Python's {@code repr(float)}: the shortest digits that round-trip, positional when the
     * decimal exponent is in [-4, 16), scientific otherwise.
     */
    static String pythonFloat(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            throw new IllegalArgumentException("NaN and infinities have no JSON form");
        }
        if (value == 0.0) {
            return (1.0 / value < 0) ? "-0.0" : "0.0";
        }
        BigDecimal shortest = shortestDigits(Math.abs(value));
        String digits = shortest.unscaledValue().toString();
        int decpt = digits.length() - shortest.scale(); // value = 0.DIGITS x 10^decpt
        StringBuilder out = new StringBuilder(value < 0 ? "-" : "");
        if (decpt > -4 && decpt <= 16) {
            if (decpt <= 0) {
                out.append("0.").append("0".repeat(-decpt)).append(digits);
            } else if (decpt >= digits.length()) {
                out.append(digits).append("0".repeat(decpt - digits.length())).append(".0");
            } else {
                out.append(digits, 0, decpt).append('.').append(digits.substring(decpt));
            }
        } else {
            out.append(digits.charAt(0));
            if (digits.length() > 1) {
                out.append('.').append(digits.substring(1));
            }
            int exponent = decpt - 1;
            out.append('e').append(exponent < 0 ? '-' : '+');
            String magnitude = Integer.toString(Math.abs(exponent));
            out.append(magnitude.length() < 2 ? "0" + magnitude : magnitude);
        }
        return out.toString();
    }
}
