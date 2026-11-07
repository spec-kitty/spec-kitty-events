package org.speckitty.missionstatus.adapter.out.filesystem.codec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Runs the normative status_append documents through the production Python-shaped decoder and encoder. */
class StatusAppendFixtureTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Path FIXTURES = fixtureDirectory();

  @TestFactory
  Stream<DynamicTest> everyStatusAppendFixture() throws Exception {
    return files("valid").stream()
        .map(path -> dynamicTest("valid/" + path.getFileName(), () -> assertValid(path)))
        .collect(java.util.stream.Collectors.collectingAndThen(
            java.util.stream.Collectors.toList(),
            valid -> Stream.concat(valid.stream(), files("invalid").stream()
                .map(path -> dynamicTest("invalid/" + path.getFileName(), () -> assertInvalid(path))))));
  }

  @Test
  void theCorpusIsPresentAndNonVacuous() {
    assertThat(FIXTURES).isDirectory();
    assertThat(files("valid")).hasSizeGreaterThanOrEqualTo(12);
    assertThat(files("invalid")).hasSizeGreaterThanOrEqualTo(4);
  }

  private static void assertValid(Path path) throws Exception {
    JsonNode fixture = JSON.readTree(Files.readString(path));
    Object decoded = PythonJson.parse(fixture.required("input_json").asString());
    assertThat(decoded).isInstanceOf(Map.class);
    @SuppressWarnings("unchecked")
    Map<String, Object> row = (Map<String, Object>) decoded;
    byte[] actual = StatusRowEncoder.encodeLine(row);
    byte[] expected = fixture.required("expected_line").asString().getBytes(StandardCharsets.US_ASCII);
    assertThat(actual)
        .as(() -> "expected <%s> but was <%s>".formatted(
            new String(expected, StandardCharsets.US_ASCII), new String(actual, StandardCharsets.US_ASCII)))
        .containsExactly(expected);
  }

  private static void assertInvalid(Path path) throws Exception {
    JsonNode fixture = JSON.readTree(Files.readString(path));
    Object decoded = PythonJson.parse(fixture.required("input_json").asString());
    assertThat(decoded).isInstanceOf(Map.class);
    @SuppressWarnings("unchecked")
    Map<String, Object> row = (Map<String, Object>) decoded;
    StatusRowRefusedException.Kind kind = switch (fixture.required("expected_error").asString()) {
      case "NonFiniteNumber" -> StatusRowRefusedException.Kind.NON_FINITE;
      case "SessionTimesNotSubtractable" -> StatusRowRefusedException.Kind.SESSION_TIMES_NOT_SUBTRACTABLE;
      default -> throw new AssertionError("unknown expected_error in " + path);
    };
    assertThatThrownBy(() -> StatusRowEncoder.encodeLine(row))
        .isInstanceOfSatisfying(StatusRowRefusedException.class, refused -> assertThat(refused.kind()).isEqualTo(kind));
  }

  private static Path fixtureDirectory() {
    String configured = System.getProperty("eventLog.statusAppendFixtures");
    assertThat(configured).as("eventLog.statusAppendFixtures system property").isNotBlank();
    return Path.of(configured);
  }

  private static List<Path> files(String kind) {
    Path directory = FIXTURES.resolve(kind);
    assertThat(directory).isDirectory();
    try (Stream<Path> paths = Files.list(directory)) {
      return paths.filter(path -> path.getFileName().toString().endsWith(".json")).sorted().toList();
    } catch (java.io.IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
  }
}
