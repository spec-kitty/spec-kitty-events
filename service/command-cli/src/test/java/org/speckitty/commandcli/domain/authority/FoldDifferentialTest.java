package org.speckitty.commandcli.domain.authority;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.speckitty.commandcli.adapter.out.statuslog.EventLogRowCodec;
import org.speckitty.missionstatus.domain.model.Lane;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The write fold against the lanes core's reducer reaches for hand-written logs ({@code fold-differential/}, generated from the package
 * reducer by {@code scripts/conformance/generate_fold_differential.py}). Rows are decoded by the log codec, so ids and numbers keep
 * their decoded types.
 */
class FoldDifferentialTest {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String DIRECTORY = "/fold-differential";

  @TestFactory
  Stream<DynamicTest> everyCaseFoldsToTheLanesCoreReaches() throws Exception {
    List<Path> files = caseFiles();
    assertTrue(files.size() >= 6, "the differential directory must hold its cases, found " + files.size());
    return files.stream().map(file -> DynamicTest.dynamicTest(file.getFileName().toString(), () -> check(file)));
  }

  private static void check(Path file) throws IOException {
    JsonNode document = JSON.readTree(Files.readString(file));
    String logText = logOf(document.get("log"));
    List<LogLine> lines = new ArrayList<>();
    new EventLogRowCodec().decode(logText.getBytes(StandardCharsets.UTF_8)).values().forEach(lines::add);
    JsonNode expected = document.get("expected");
    if (expected.has("unreducible")) {
      assertThatThrownBy(() -> WriteFold.fold(lines)).as(document.get("id").asString()).isInstanceOf(UnreducibleLog.class);
      return;
    }
    FoldView folded = WriteFold.fold(lines);
    assertThat(folded.all().keySet()).as(document.get("id").asString()).containsExactlyInAnyOrderElementsOf(expected.propertyNames());
    for (String wp : expected.propertyNames()) {
      assertThat(folded.of(wp).lane()).as(document.get("id").asString() + " " + wp).isEqualTo(Lane.fromWire(expected.get(wp).asString()));
    }
  }

  /** The log as the service stores it: one compact JSON object per line, ids exactly as the case spells them. */
  private static String logOf(JsonNode log) {
    StringBuilder text = new StringBuilder();
    log.forEach(row -> text.append(JSON.writeValueAsString(row)).append('\n'));
    return text.toString();
  }

  private static List<Path> caseFiles() throws IOException, URISyntaxException {
    var resource = FoldDifferentialTest.class.getResource(DIRECTORY);
    assertThat(resource).as("test resource directory " + DIRECTORY).isNotNull();
    try (Stream<Path> files = Files.list(Path.of(resource.toURI()))) {
      return files.filter(path -> path.toString().endsWith(".json")).sorted().toList();
    }
  }
}
