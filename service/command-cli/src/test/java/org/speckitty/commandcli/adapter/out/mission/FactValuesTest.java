package org.speckitty.commandcli.adapter.out.mission;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class FactValuesTest {
  /** Character.isDigit minus Python 3.11 str.isdecimal for the Gradle JDK 25 test toolchain. */
  private static final int[][] JDK25_DECIMAL_DIGITS_ADDED_AFTER_UNICODE_14 = {
    {0x10D40, 0x10D49}, {0x116D0, 0x116E3}, {0x11BF0, 0x11BF9},
    {0x11F50, 0x11F59}, {0x16130, 0x16139}, {0x16D70, 0x16D79}, {0x1CCF0, 0x1CCF9},
    {0x1E4F0, 0x1E4F9}, {0x1E5F1, 0x1E5FA}
  };

  @Test
  void shellPidDecimalDigitsExactlyMatchPython311Unicode14() {
    List<Integer> jdkAdditions = IntStream.range(0, Character.MAX_CODE_POINT + 1)
        .filter(Character::isDigit)
        .filter(codePoint -> !FactValues.isPython311DecimalDigit(codePoint))
        .boxed()
        .toList();
    assertThat(jdkAdditions).containsExactlyElementsOf(expectedJdk25Additions());
    assertThat(FactValues.isPython311DecimalDigit(0x11F51)).isFalse();
    assertThat(FactValues.isPython311DecimalDigit(0x1D7D9)).isTrue();
  }

  private static List<Integer> expectedJdk25Additions() {
    List<Integer> codePoints = new ArrayList<>();
    for (int[] range : JDK25_DECIMAL_DIGITS_ADDED_AFTER_UNICODE_14) {
      codePoints.addAll(IntStream.rangeClosed(range[0], range[1]).boxed().toList());
    }
    return codePoints;
  }
}
