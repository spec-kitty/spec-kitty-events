package org.speckitty.commandcli.adapter.out.statuslog;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.speckitty.commandcli.domain.authority.LogLine;

/** Physical-line decoding plus byte-exact delegation to WP05's fixture-proven encoder. */
class EventLogRowCodecTest {
  private final EventLogRowCodec codec = new EventLogRowCodec();

  @Test
  void validStatusAppendFixturesKeepTheirExpectedBytes() throws Exception {
    Path fixtures = root().resolve("src/spec_kitty_events/conformance/fixtures/status_append/valid");
    try (Stream<Path> paths = Files.list(fixtures)) {
      for (Path path : paths.filter(item -> item.toString().endsWith(".json")).sorted().toList()) {
        var fixture = tools.jackson.databind.json.JsonMapper.builder().build().readTree(Files.readString(path));
        Object decoded = org.speckitty.missionstatus.adapter.out.filesystem.codec.StatusRowDecoder.parse(
            fixture.required("input_json").asString());
        @SuppressWarnings("unchecked")
        Map<String, Object> row = (Map<String, Object>) decoded;
        assertThat(codec.encodeLine(row)).as(path.getFileName().toString())
            .isEqualTo(fixture.required("expected_line").asString().getBytes(StandardCharsets.US_ASCII));
      }
    }
  }

  @Test
  void decodeSkipsFinalEmptySegmentAndRetainsFirstClassPhysicalLines() {
    String json = "{\"event_id\":\"abc\"}";
    var decoded = codec.decode((json + "\r\n\n{bad\n").getBytes(StandardCharsets.UTF_8));
    assertThat(decoded.lines()).hasSize(3);
    assertThat(decoded.lines().get(0).value()).isInstanceOf(LogLine.Value.class);
    assertThat(decoded.lines().get(0).bytesWithoutLfOrCr()).isEqualTo(json.getBytes(StandardCharsets.UTF_8));
    assertThat(decoded.lines().get(1).value()).isInstanceOf(LogLine.Blank.class);
    assertThat(decoded.lines().get(2).value()).isInstanceOf(LogLine.Unparseable.class);
  }

  @Test
  void refusalIsSafeAndFixed() {
    assertThat(codec.refusal(Map.of("n", Double.NaN))).isEqualTo("status rows cannot contain NaN or infinity");
    assertThat(codec.refusal(Map.of("n", 1.0))).isNull();
  }

  private static Path root() {
    Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
    while (current != null) {
      if (Files.isRegularFile(current.resolve("pyproject.toml"))) return current;
      current = current.getParent();
    }
    throw new IllegalStateException("repository root not found");
  }
}
