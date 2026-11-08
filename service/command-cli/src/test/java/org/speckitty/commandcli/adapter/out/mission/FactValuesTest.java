package org.speckitty.commandcli.adapter.out.mission;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class FactValuesTest {
  @Test
  void shellPidDecimalDigitsExactlyMatchPython311Unicode14() throws Exception {
    List<Integer> actual = IntStream.range(0, Character.MAX_CODE_POINT + 1)
        .filter(FactValues::isPython311DecimalDigit)
        .boxed()
        .toList();
    ByteBuffer sortedCodePoints = ByteBuffer.allocate(actual.size() * Integer.BYTES);
    actual.forEach(sortedCodePoints::putInt);

    // Generated once with core 512567ee's Python 3.11.15 (Unicode 14.0.0):
    // points = [cp for cp in range(sys.maxunicode + 1) if chr(cp).isdecimal()]
    // digest = sha256(b"".join(cp.to_bytes(4, "big") for cp in points)).hexdigest()
    assertThat(actual).hasSize(660);
    assertThat(HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(sortedCodePoints.array())))
        .isEqualTo("6a3d60c3fba3543c006ac150be6fe964a320bf8d2e9ca061ba194acfa25df5fb");
  }
}
