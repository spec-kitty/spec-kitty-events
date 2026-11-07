package org.speckitty.missionstatus.adapter.out.filesystem.codec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PythonFloatReprTest {

  @Test
  void matchesPythonShortestReprAtEveryFormatBoundary() {
    Map<Double, String> vectors = new LinkedHashMap<>();
    vectors.put(1.0, "1.0");
    vectors.put(-0.0, "-0.0");
    vectors.put(0.0, "0.0");
    vectors.put(0.1, "0.1");
    vectors.put(1e-5, "1e-05");
    vectors.put(0.0001, "0.0001");
    vectors.put(1e16, "1e+16");
    vectors.put(1000000000000000.0, "1000000000000000.0");
    vectors.put(123456789012345678.0, "1.2345678901234568e+17");
    vectors.put(Double.MIN_VALUE, "5e-324");
    vectors.put(1e-323, "1e-323");
    vectors.put(Double.MIN_NORMAL, "2.2250738585072014e-308");
    vectors.put(Double.MAX_VALUE, "1.7976931348623157e+308");
    vectors.put(-1.5, "-1.5");
    vectors.put(100.0, "100.0");
    vectors.put(2.0, "2.0");
    vectors.put(123.456, "123.456");
    vectors.put(1.5e300, "1.5e+300");
    vectors.forEach((value, expected) -> assertThat(PythonFloatRepr.repr(value)).as("repr(%s)", value).isEqualTo(expected));
  }

  @Test
  void refusesEveryNonFiniteDouble() {
    for (double value : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
      assertThatThrownBy(() -> PythonFloatRepr.repr(value))
          .isInstanceOfSatisfying(StatusRowRefusedException.class,
              refused -> assertThat(refused.kind()).isEqualTo(StatusRowRefusedException.Kind.NON_FINITE));
    }
  }
}
