package org.speckitty.missionstatus.adapter.out.filesystem.codec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StatusRowSanitizerTest {

  @Test
  void stripsEveryPiiKeyAtEveryDepthWithoutChangingOtherKeyOrder() {
    Map<String, Object> nested = row("z", 1, "machine_name", "m", "a", 2);
    Map<String, Object> input = row(
        "first", nested,
        "hostname", "h",
        "second", List.of(List.of(row("workspace_path", "/x", "keep", true)), row("developer_name", "n")),
        "developer_email", "e",
        "last", null);

    Map<String, Object> result = StatusRowSanitizer.sanitize(input);

    assertThat(result.keySet()).containsExactly("first", "second", "last");
    assertThat(new java.util.ArrayList<>(((Map<?, ?>) result.get("first")).keySet())).isEqualTo(List.of("z", "a"));
    List<?> outer = (List<?>) result.get("second");
    Map<?, ?> deepest = (Map<?, ?>) ((List<?>) outer.get(0)).get(0);
    assertThat(deepest).isEqualTo(Map.of("keep", true));
    assertThat(outer.get(1)).isEqualTo(Map.of());
    assertThat(nested.keySet()).containsExactly("z", "machine_name", "a");
  }

  @Test
  void deepCopiesContainersAndLeavesTheCompleteInputTreeUnchanged() {
    Object decoded = PythonJson.parse("{\"a\":[{\"hostname\":\"h\",\"b\":[\"x\",{\"c\":1}]}],\"n\":null}");
    @SuppressWarnings("unchecked")
    Map<String, Object> input = (Map<String, Object>) decoded;
    Object before = PythonJson.parse("{\"a\":[{\"hostname\":\"h\",\"b\":[\"x\",{\"c\":1}]}],\"n\":null}");

    Map<String, Object> result = StatusRowSanitizer.sanitize(input);

    assertThat(input).isEqualTo(before);
    assertThat(result).isNotSameAs(input);
    assertThat(result.get("a")).isNotSameAs(input.get("a"));
    assertThat(result).isEqualTo(PythonJson.parse("{\"a\":[{\"b\":[\"x\",{\"c\":1}]}],\"n\":null}"));
  }

  @Test
  void bothParseableTimesReplaceAndOverwriteDurationIncludingNegativeFractionTruncation() {
    Map<String, Object> result = StatusRowSanitizer.sanitize(row(
        "session_duration_s", 99,
        "session_started_at", "2026-01-01T00:00:01.900000Z",
        "session_ended_at", "2026-01-01T00:00:00.100000Z",
        "keep", 1));
    assertThat(result).isEqualTo(row("session_duration_s", BigInteger.valueOf(-1), "keep", 1));
  }

  @Test
  void missingMalformedOrNonStringTimesLeaveAnExistingDurationUntouched() {
    List<Map<String, Object>> rows = List.of(
        row("session_started_at", "2026-01-01T00:00:00Z", "session_duration_s", 9),
        row("session_ended_at", "2026-01-01T00:00:01Z", "session_duration_s", 9),
        row("session_started_at", "bad", "session_ended_at", "2026-01-01T00:00:01Z", "session_duration_s", 9),
        row("session_started_at", 42, "session_ended_at", "2026-01-01T00:00:01Z", "session_duration_s", 9),
        row("session_started_at", null, "session_ended_at", null, "session_duration_s", 9));
    for (Map<String, Object> input : rows) {
      assertThat(StatusRowSanitizer.sanitize(input)).isEqualTo(row("session_duration_s", 9));
    }
  }

  @Test
  void nestedSessionKeysAreNotSessionFields() {
    Map<String, Object> nested = row(
        "session_started_at", "2026-01-01T00:00:00Z",
        "session_ended_at", "2026-01-01T00:00:01Z",
        "session_duration_s", 7);
    assertThat(StatusRowSanitizer.sanitize(row("nested", nested))).isEqualTo(row("nested", nested));
  }

  @Test
  void mixedAwarenessIsRefusedInEitherDirectionWithoutEchoingTheRow() {
    for (List<String> pair : List.of(
        List.of("2026-01-01T00:00:00Z", "2026-01-01T00:00:01"),
        List.of("2026-01-01T00:00:00", "2026-01-01T00:00:01Z"))) {
      Map<String, Object> input = row("session_started_at", pair.get(0), "session_ended_at", pair.get(1), "secret_marker", "xyzzy");
      assertThatThrownBy(() -> StatusRowSanitizer.sanitize(input))
          .isInstanceOfSatisfying(StatusRowRefusedException.class, refused -> {
            assertThat(refused.kind()).isEqualTo(StatusRowRefusedException.Kind.SESSION_TIMES_NOT_SUBTRACTABLE);
            assertThat(refused.getMessage()).doesNotContain(pair.get(0), pair.get(1), "xyzzy");
          });
    }
  }

  @Test
  void checkRunsNonFiniteAndSessionRefusalsAndAcceptsFiniteRows() {
    assertThatThrownBy(() -> StatusRowSanitizer.check(row("a", List.of(row("b", Double.NaN)))))
        .isInstanceOfSatisfying(StatusRowRefusedException.class,
            refused -> assertThat(refused.kind()).isEqualTo(StatusRowRefusedException.Kind.NON_FINITE));
    assertThatThrownBy(() -> StatusRowSanitizer.check(row(
            "session_started_at", "2026-01-01T00:00:00Z", "session_ended_at", "2026-01-01T00:00:01")))
        .isInstanceOf(StatusRowRefusedException.class);
    assertThatThrownBy(() -> StatusRowSanitizer.check(row("a", Double.POSITIVE_INFINITY, "hostname", "gone")))
        .isInstanceOf(StatusRowRefusedException.class);
    assertThatCode(() -> StatusRowSanitizer.check(row("a", List.of(1.0, 2.0)))).doesNotThrowAnyException();
  }

  private static Map<String, Object> row(Object... pairs) {
    Map<String, Object> row = new LinkedHashMap<>();
    for (int index = 0; index < pairs.length; index += 2) {
      row.put((String) pairs[index], pairs[index + 1]);
    }
    return row;
  }
}
