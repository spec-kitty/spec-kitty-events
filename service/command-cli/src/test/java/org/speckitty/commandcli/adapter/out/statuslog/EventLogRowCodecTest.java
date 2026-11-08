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
  void decodeSplitsRowsOnLfOnlyTolerantOfATrailingCrAndPythonWhitespaceBlanks() {
    String row = "{\"event_id\":\"abc\"}";
    var split = codec.decode((row + "\n" + row + "\r\n" + row + "\r\r\n").getBytes(StandardCharsets.UTF_8));
    assertThat(split.values()).hasSize(3).allSatisfy(line -> assertThat(line).isInstanceOf(LogLine.Value.class));
    assertThat(split.lines().get(1).bytesWithoutLfOrCr()).isEqualTo(row.getBytes(StandardCharsets.UTF_8));
    var bareCr = codec.decode((row + "\r" + row + "\n").getBytes(StandardCharsets.UTF_8));
    assertThat(bareCr.values()).as("a bare CR is not a row separator").singleElement().isInstanceOf(LogLine.Unparseable.class);
    var decoded = codec.decode((row + "\n\u00a0\r\n   \n" + row).getBytes(StandardCharsets.UTF_8));
    assertThat(decoded.lines()).hasSize(4);
    assertThat(decoded.values().get(0)).isInstanceOf(LogLine.Value.class);
    assertThat(decoded.values().get(1)).isInstanceOf(LogLine.Blank.class);
    assertThat(decoded.values().get(2)).isInstanceOf(LogLine.Blank.class);
    assertThat(decoded.values().get(3)).isInstanceOf(LogLine.Value.class);
  }

  @Test
  void aRawPythonLineBoundaryAnywhereMakesTheLogUnreducibleUnderR24() {
    String row = "{\"event_id\":\"abc\"}";
    for (String separator : new String[] {"\u0085", "\u2028", "\u2029", "\u000b", "\u000c", "\u001c", "\u001d", "\u001e"}) {
      for (String log : new String[] {
          row + "\n" + row + separator + row + "\n", // between rows
          "{\"event_id\":\"ab" + separator + "cd\"}\n", // inside a string
          row + "\n" + separator + "\n", // alone on a line
          row + separator}) { // at the end
        var decoded = codec.decode(log.getBytes(StandardCharsets.UTF_8));
        assertThat(decoded.values()).as("U+%04X", (int) separator.charAt(0)).singleElement().isInstanceOf(LogLine.Unparseable.class);
      }
    }
    for (String ordinary : new String[] {"\u00a0", "\u2027", "\u202a", "\u0084", "\u0086", "\u00c2"}) {
      var decoded = codec.decode(("{\"event_id\":\"ab" + ordinary + "cd\"}\n").getBytes(StandardCharsets.UTF_8));
      assertThat(decoded.values()).as("U+%04X", (int) ordinary.charAt(0)).singleElement().isInstanceOf(LogLine.Value.class);
    }
  }

  @Test
  void decodeRaisesJacksonLimitsToTheOracleLimitsWithAFixedNestingCap() {
    String nested200 = "{\"x\":" + "[".repeat(200) + "1" + "]".repeat(200) + "}\n";
    String nested300 = "{\"x\":" + "[".repeat(300) + "1" + "]".repeat(300) + "}\n";
    String integer2000 = "{\"x\":" + "9".repeat(2000) + "}\n";
    String integer4301 = "{\"x\":" + "9".repeat(4301) + "}\n";
    String float5000 = "{\"x\":1." + "9".repeat(5000) + "}\n";
    assertThat(codec.decode(nested200.getBytes(StandardCharsets.UTF_8)).values().getFirst()).isInstanceOf(LogLine.Value.class);
    assertThat(codec.decode(nested300.getBytes(StandardCharsets.UTF_8)).values().getFirst()).isInstanceOf(LogLine.Unparseable.class);
    assertThat(codec.decode(integer2000.getBytes(StandardCharsets.UTF_8)).values().getFirst()).isInstanceOf(LogLine.Value.class);
    assertThat(codec.decode(integer4301.getBytes(StandardCharsets.UTF_8)).values().getFirst()).isInstanceOf(LogLine.Unparseable.class);
    assertThat(codec.decode(float5000.getBytes(StandardCharsets.UTF_8)).values().getFirst()).isInstanceOf(LogLine.Value.class);
  }

  @Test
  void aCoreWrittenNonFiniteNumberDecodesDeliberatelyAsADouble() {
    for (String token : new String[] {"NaN", "Infinity", "-Infinity"}) {
      var decoded = codec.decode(("{\"evidence\":{\"x\":" + token + "}}\n").getBytes(StandardCharsets.UTF_8));
      assertThat(decoded.values()).as(token).singleElement().isInstanceOf(LogLine.Value.class);
      @SuppressWarnings("unchecked")
      Map<String, Object> evidence = (Map<String, Object>) ((LogLine.Value) decoded.values().getFirst()).row().get("evidence");
      assertThat(evidence.get("x")).as(token).isInstanceOf(Double.class);
    }
    for (String token : new String[] {"+Infinity", "INF", "nan", "inf"}) {
      var decoded = codec.decode(("{\"x\":" + token + "}\n").getBytes(StandardCharsets.UTF_8));
      assertThat(decoded.values()).as(token).singleElement().isInstanceOf(LogLine.Unparseable.class);
    }
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
