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
  void decodeRejectsInvalidUtf8InsteadOfReplacingIt() {
    var decoded = codec.decode(new byte[] {'{', '"', 'x', '"', ':', '"', (byte) 0xff, '"', '}', '\n'});
    assertThat(decoded.values()).singleElement().isInstanceOf(LogLine.Unparseable.class);
  }

  @Test
  void decodeUsesEveryPythonSplitlinesSeparatorAndPythonWhitespace() {
    String row = "{\"event_id\":\"abc\"}";
    for (String separator : new String[] {"\n", "\r\n", "\r", "\u000b", "\u000c", "\u001c", "\u001d", "\u001e", "\u0085", "\u2028", "\u2029"}) {
      var split = codec.decode((row + separator + row).getBytes(StandardCharsets.UTF_8));
      assertThat(split.values()).as("separator U+%04X", (int) separator.charAt(0)).hasSize(2)
          .allSatisfy(line -> assertThat(line).isInstanceOf(LogLine.Value.class));
    }
    String document = row + "\f\n\u00a0\r\n" + row;
    var decoded = codec.decode(document.getBytes(StandardCharsets.UTF_8));
    assertThat(decoded.lines()).hasSize(4);
    assertThat(decoded.values().get(0)).isInstanceOf(LogLine.Value.class);
    assertThat(decoded.values().get(1)).isInstanceOf(LogLine.Blank.class);
    assertThat(decoded.values().get(2)).isInstanceOf(LogLine.Blank.class);
    assertThat(decoded.values().get(3)).isInstanceOf(LogLine.Value.class);
  }

  @Test
  void rawLineSeparatorsInsideJsonStringsMakeTheLogUnreducible() {
    for (String separator : new String[] {"\u2028", "\u0085"}) {
      var decoded = codec.decode(("{\"event_id\":\"ab" + separator + "cd\"}\n").getBytes(StandardCharsets.UTF_8));
      assertThat(decoded.values()).as("U+%04X", (int) separator.charAt(0))
          .allSatisfy(line -> assertThat(line).isInstanceOf(LogLine.Unparseable.class));
    }
  }

  @Test
  void decodeRaisesJacksonLimitsToThePythonOracleLimits() {
    String nested600 = "{\"x\":" + "[".repeat(600) + "1" + "]".repeat(600) + "}\n";
    String nested1200 = "{\"x\":" + "[".repeat(1200) + "1" + "]".repeat(1200) + "}\n";
    String integer2000 = "{\"x\":" + "9".repeat(2000) + "}\n";
    String integer4301 = "{\"x\":" + "9".repeat(4301) + "}\n";
    assertThat(codec.decode(nested600.getBytes(StandardCharsets.UTF_8)).values().getFirst()).isInstanceOf(LogLine.Value.class);
    assertThat(codec.decode(nested1200.getBytes(StandardCharsets.UTF_8)).values().getFirst()).isInstanceOf(LogLine.Unparseable.class);
    assertThat(codec.decode(integer2000.getBytes(StandardCharsets.UTF_8)).values().getFirst()).isInstanceOf(LogLine.Value.class);
    assertThat(codec.decode(integer4301.getBytes(StandardCharsets.UTF_8)).values().getFirst()).isInstanceOf(LogLine.Unparseable.class);
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
