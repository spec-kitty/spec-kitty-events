package org.speckitty.missionstatus.adapter.out.filesystem.codec;

import static org.assertj.core.api.Assertions.assertThat;

import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Cucumber-JVM steps of the {@code status/append} scenarios (spec-kitty-events#270): the service's status-row encoder
 * against the normative {@code status_append} documents. The same step texts are bound in Python by
 * {@code tests/scenarios/status/conftest.py}. Test-only: no step code enters the service's production sources. They live in
 * the codec package because the fixtures' raw JSON is parsed with the package-private {@link PythonJson}, as every binding
 * parses it itself.
 */
public class StatusAppendSteps {
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private record Fixture(String id, JsonNode document, boolean valid) {}

  private record Appended(Fixture fixture, byte[] line, StatusRowRefusedException refusal) {}

  private List<Fixture> fixtures = List.of();
  private List<Appended> appended = List.of();

  @Given("every status-append fixture")
  public void everyStatusAppendFixture() {
    List<Fixture> found = new ArrayList<>();
    found.addAll(read("valid", true));
    found.addAll(read("invalid", false));
    // The same floors as StatusAppendFixtureTest, so the scenario never passes on a shrunken corpus.
    assertThat(found.stream().filter(Fixture::valid).count()).as("valid status_append fixtures").isGreaterThanOrEqualTo(12);
    assertThat(found.stream().filter(fixture -> !fixture.valid()).count()).as("invalid status_append fixtures")
        .isGreaterThanOrEqualTo(4);
    fixtures = found;
  }

  @When("each fixture's row is appended")
  public void eachFixturesRowIsAppended() {
    List<Appended> results = new ArrayList<>();
    for (Fixture fixture : fixtures) {
      Map<String, Object> row = row(fixture);
      try {
        results.add(new Appended(fixture, StatusRowEncoder.encodeLine(row), null));
      } catch (StatusRowRefusedException refused) {
        results.add(new Appended(fixture, null, refused));
      }
    }
    appended = results;
  }

  @Then("every append writes the bytes or raises the refusal its fixture pins")
  public void everyAppendAsPinned() {
    assertThat(appended).hasSameSizeAs(fixtures).isNotEmpty();
    for (Appended result : appended) {
      Fixture fixture = result.fixture();
      if (fixture.valid()) {
        assertThat(result.refusal()).as(fixture.id() + " refused").isNull();
        byte[] expected = fixture.document().required("expected_line").asString().getBytes(StandardCharsets.US_ASCII);
        assertThat(new String(result.line(), StandardCharsets.US_ASCII)).as(fixture.id())
            .isEqualTo(new String(expected, StandardCharsets.US_ASCII));
        assertThat(result.line()).as(fixture.id()).containsExactly(expected);
      } else {
        assertThat(result.line()).as(fixture.id() + " wrote a line").isNull();
        assertThat(result.refusal()).as(fixture.id() + " refusal").isNotNull();
        assertThat(result.refusal().kind()).as(fixture.id()).isEqualTo(pinnedKind(fixture));
      }
    }
  }

  private static StatusRowRefusedException.Kind pinnedKind(Fixture fixture) {
    String pinned = fixture.document().required("expected_error").asString();
    return switch (pinned) {
      case "NonFiniteNumber" -> StatusRowRefusedException.Kind.NON_FINITE;
      case "SessionTimesNotSubtractable" -> StatusRowRefusedException.Kind.SESSION_TIMES_NOT_SUBTRACTABLE;
      default -> throw new AssertionError(fixture.id() + ": unknown expected_error " + pinned);
    };
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> row(Fixture fixture) {
    Object decoded = PythonJson.parse(fixture.document().required("input_json").asString());
    assertThat(decoded).as(fixture.id() + " input_json").isInstanceOf(Map.class);
    return (Map<String, Object>) decoded;
  }

  private static List<Fixture> read(String kind, boolean valid) {
    String configured = System.getProperty("eventLog.statusAppendFixtures");
    assertThat(configured).as("eventLog.statusAppendFixtures system property").isNotBlank();
    Path directory = Path.of(configured).resolve(kind);
    try (Stream<Path> paths = Files.list(directory)) {
      return paths.filter(path -> path.getFileName().toString().endsWith(".json")).sorted()
          .map(path -> new Fixture(kind + "/" + path.getFileName(), parse(path), valid)).toList();
    } catch (IOException error) {
      throw new UncheckedIOException(error);
    }
  }

  private static JsonNode parse(Path path) {
    try {
      return JSON.readTree(Files.readString(path));
    } catch (IOException error) {
      throw new UncheckedIOException(error);
    }
  }
}
