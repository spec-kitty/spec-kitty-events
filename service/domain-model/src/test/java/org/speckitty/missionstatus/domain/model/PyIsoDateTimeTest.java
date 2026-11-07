package org.speckitty.missionstatus.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Every committed vector comes from datetime.fromisoformat under core's pinned Python 3.11.15. */
class PyIsoDateTimeTest {

  private static final Set<String> REJECTION_BRANCHES =
      Set.of(
          "date_length", "date_digits", "non_ascii_digits", "month", "day", "year", "week", "weekday", "separator",
          "date_separator", "time_short", "time_incomplete", "time_digits", "time_separator", "hour", "minute", "second",
          "fraction", "fraction_digits", "trailing_text", "offset", "offset_digits", "offset_range", "offset_fraction",
          "offset_trailing_text", "offset_z_position");

  @Test
  void everyPinnedInterpreterVectorMatchesAndEveryRejectionBranchIsExercised() throws Exception {
    JsonNode vectors;
    try (InputStream input = getClass().getResourceAsStream("/pyisodatetime/vectors.json")) {
      assertThat(input).isNotNull();
      vectors = JsonMapper.builder().build().readTree(input);
    }
    assertThat(vectors.size()).isGreaterThanOrEqualTo(60);
    Set<String> branches = new HashSet<>();
    for (JsonNode vector : vectors) {
      String text = vector.get("text").asString();
      String branch = vector.get("branch").asString();
      branches.add(branch);
      Optional<PyIsoDateTime> parsed = PyIsoDateTime.parse(text);
      assertThat(parsed.isPresent()).as("%s (%s)", text, branch).isEqualTo(vector.get("parses").asBoolean());
      if (parsed.isPresent()) {
        assertThat(parsed.get().aware()).as(text).isEqualTo(vector.get("aware").asBoolean());
        String microsField = vector.get("aware").asBoolean() ? "utc_epoch_micros" : "naive_micros";
        assertThat(parsed.get().micros()).as("%s: %s", text, microsField).isEqualTo(vector.get(microsField).asLong());
      } else {
        assertThatThrownBy(() -> PyIsoDateTime.parseOrThrow(text)).as(text).hasMessage(branch);
      }
    }
    assertThat(branches).containsAll(REJECTION_BRANCHES);
  }

  @Test
  void subtractionUsesMicrosecondsAndTruncatesTowardZero() {
    PyIsoDateTime start = parsed("2026-01-02T03:04:05.100000");
    assertThat(PyIsoDateTime.truncatedSecondsBetween(start, parsed("2026-01-02T03:04:06.999999"))).isEqualTo(1);
    assertThat(PyIsoDateTime.truncatedSecondsBetween(start, parsed("2026-01-02T03:04:06.100000"))).isEqualTo(1);
    assertThat(PyIsoDateTime.truncatedSecondsBetween(start, parsed("2026-01-02T03:04:06.000000"))).isZero();
    assertThat(PyIsoDateTime.truncatedSecondsBetween(start, parsed("2026-01-02T03:04:04.000000"))).isEqualTo(-1);
    assertThat(PyIsoDateTime.truncatedSecondsBetween(start, parsed("2026-01-02T03:04:04.200000"))).isZero();
    assertThat(PyIsoDateTime.truncatedSecondsBetween(start, start)).isZero();
  }

  @Test
  void awareSubtractionUsesTheInstantRatherThanTheWallClock() {
    PyIsoDateTime offset = parsed("2026-01-01T00:00:00+05:30");
    PyIsoDateTime utc = parsed("2025-12-31T18:30:00Z");
    assertThat(PyIsoDateTime.truncatedSecondsBetween(offset, utc)).isZero();
    assertThat(PyIsoDateTime.truncatedSecondsBetween(utc, parsed("2025-12-31T18:30:01.900000-00:00"))).isEqualTo(1);
  }

  @Test
  void exactlyOneAwareSideCannotBeSubtracted() {
    PyIsoDateTime naive = parsed("2026-01-01T00:00:00");
    PyIsoDateTime aware = parsed("2026-01-01T00:00:00Z");
    assertThatThrownBy(() -> PyIsoDateTime.truncatedSecondsBetween(naive, aware)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> PyIsoDateTime.truncatedSecondsBetween(aware, naive)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void nullIsNotAnIsoDateTime() {
    assertThat(PyIsoDateTime.parse(null)).isEmpty();
  }

  private static PyIsoDateTime parsed(String text) {
    return PyIsoDateTime.parse(text).orElseThrow();
  }
}
