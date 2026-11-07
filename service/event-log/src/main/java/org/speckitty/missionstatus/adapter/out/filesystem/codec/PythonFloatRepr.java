package org.speckitty.missionstatus.adapter.out.filesystem.codec;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/** CPython's shortest round-trip {@code repr(float)} for the doubles produced by {@link PythonJson}. */
final class PythonFloatRepr {

  private PythonFloatRepr() {}

  /**
   * Ported from {@code java/.../CanonicalJson.java}'s {@code shortestDigits} and {@code pythonFloat} (lines 117-168 at WP05's base).
   * Only the number algorithm is shared; status rows use different separators and string escaping.
   */
  static String repr(double value) {
    if (!Double.isFinite(value)) {
      throw new StatusRowRefusedException(StatusRowRefusedException.Kind.NON_FINITE);
    }
    if (value == 0.0) {
      return 1.0 / value < 0 ? "-0.0" : "0.0";
    }
    BigDecimal shortest = shortestDigits(Math.abs(value));
    String digits = shortest.unscaledValue().toString();
    int decimalPoint = digits.length() - shortest.scale();
    StringBuilder out = new StringBuilder(value < 0 ? "-" : "");
    if (decimalPoint > -4 && decimalPoint <= 16) {
      appendPositional(out, digits, decimalPoint);
    } else {
      appendScientific(out, digits, decimalPoint - 1);
    }
    return out.toString();
  }

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

  private static void appendPositional(StringBuilder out, String digits, int decimalPoint) {
    if (decimalPoint <= 0) {
      out.append("0.").append("0".repeat(-decimalPoint)).append(digits);
    } else if (decimalPoint >= digits.length()) {
      out.append(digits).append("0".repeat(decimalPoint - digits.length())).append(".0");
    } else {
      out.append(digits, 0, decimalPoint).append('.').append(digits.substring(decimalPoint));
    }
  }

  private static void appendScientific(StringBuilder out, String digits, int exponent) {
    out.append(digits.charAt(0));
    if (digits.length() > 1) {
      out.append('.').append(digits.substring(1));
    }
    out.append('e').append(exponent < 0 ? '-' : '+');
    String magnitude = Integer.toString(Math.abs(exponent));
    out.append(magnitude.length() < 2 ? "0" + magnitude : magnitude);
  }
}
