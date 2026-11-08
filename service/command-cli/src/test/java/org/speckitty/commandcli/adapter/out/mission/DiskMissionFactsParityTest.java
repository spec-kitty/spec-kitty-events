package org.speckitty.commandcli.adapter.out.mission;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.speckitty.commandcli.adapter.out.statuslog.EventLogRowCodec;
import org.speckitty.commandcli.domain.authority.Authority;
import org.speckitty.commandcli.domain.authority.Judgement;
import org.speckitty.commandcli.domain.authority.LogLine;
import org.speckitty.commandcli.domain.authority.Operation;
import org.speckitty.commandcli.domain.authority.Refusal;
import org.speckitty.commandcli.domain.authority.UnitInput;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Every domain case through the production disk reader, not WP06's in-memory frontmatter mirror. */
class DiskMissionFactsParityTest {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Set<String> PASSED = ConcurrentHashMap.newKeySet();

  @TestFactory
  Stream<DynamicTest> everyDomainCorpusCase() throws IOException {
    Path cases = corpus();
    List<Path> domain;
    try (Stream<Path> paths = Files.list(cases)) {
      domain = paths.filter(path -> path.toString().endsWith(".json")).sorted()
          .filter(path -> "domain".equals(read(path).required("layer").asString())).toList();
    }
    return domain.stream().map(path -> DynamicTest.dynamicTest(path.getFileName().toString(), () -> run(path)));
  }

  private static void run(Path path) throws Exception {
    JsonNode testCase = read(path);
    Path repository = Files.createTempDirectory("disk-facts-parity");
    try {
      gitInit(repository);
      Path mission = repository.resolve("kitty-specs/parity");
      Files.createDirectories(mission.resolve("tasks"));
      for (var task : testCase.required("tasks").properties()) {
        Files.writeString(mission.resolve("tasks").resolve(task.getKey()), task.getValue().asString());
      }
      List<LogLine> log = log(testCase);
      List<UnitInput> units = new ArrayList<>();
      for (JsonNode unit : testCase.required("request").required("units")) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (JsonNode row : unit.required("rows")) rows.add(map(row));
        units.add(new UnitInput(unit.required("wpId").asString(),
            Operation.fromWire(unit.path("operation").asString("transition")), rows));
      }
      boolean batch = "batch".equals(testCase.required("request").required("kind").asString());
      Judgement judgement = Authority.standard().judge(log, units, batch, new DiskMissionFacts(mission));
      assertOutcome(testCase, judgement);
      PASSED.add(testCase.required("id").asString());
    } finally {
      try (Stream<Path> paths = Files.walk(repository)) {
        paths.sorted(java.util.Comparator.reverseOrder()).forEach(item -> item.toFile().delete());
      }
    }
  }

  private static void assertOutcome(JsonNode testCase, Judgement judgement) {
    JsonNode expected = testCase.required("expected");
    String id = testCase.required("id").asString();
    if ("ack".equals(expected.required("result").asString())) {
      assertThat(judgement).as(id).isInstanceOf(Judgement.Accepted.class);
      return;
    }
    assertThat(judgement).as(id).isInstanceOf(Judgement.Refused.class);
    Refusal refusal = ((Judgement.Refused) judgement).refusal();
    assertThat(refusal.code().name()).as(id + " code").isEqualTo(expected.required("code").asString());
    assertThat(refusal.reason() == null ? null : refusal.reason().urn()).as(id + " reason")
        .isEqualTo(expected.path("reason").asString(null));
    if ("core".equals(testCase.required("oracle").asString())) {
      assertThat(refusal.message()).as(id + " message").isEqualTo(expected.path("message").asString(null));
    } else if (!expected.path("message").isNull() && expected.has("message")) {
      assertThat(refusal.detail()).as(id + " detail").isEqualTo(expected.path("message").asString());
    }
    assertThat(refusal.unitIndex()).as(id + " unit")
        .isEqualTo(expected.path("unit").isNumber() ? expected.path("unit").asInt() : null);
  }

  private static List<LogLine> log(JsonNode testCase) {
    if (testCase.has("log_text")) {
      return new EventLogRowCodec().decode(testCase.get("log_text").asString().getBytes(StandardCharsets.UTF_8)).values();
    }
    List<LogLine> log = new ArrayList<>();
    for (JsonNode row : testCase.path("log")) log.add(new LogLine.Value(map(row)));
    return log;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(JsonNode row) {
    return JSON.convertValue(row, Map.class);
  }

  private static void gitInit(Path repository) throws Exception {
    Process process = new ProcessBuilder("git", "init", "--quiet").directory(repository.toFile()).start();
    if (!process.waitFor(30, TimeUnit.SECONDS) || process.exitValue() != 0) throw new IOException("git init failed");
  }

  private static Path corpus() {
    Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
    while (current != null) {
      if (Files.isRegularFile(current.resolve("pyproject.toml"))) {
        return current.resolve("src/spec_kitty_events/conformance/status_transition_parity/cases");
      }
      current = current.getParent();
    }
    throw new IllegalStateException("repository root not found");
  }

  private static JsonNode read(Path path) {
    try {
      return JSON.readTree(Files.readString(path));
    } catch (IOException exception) {
      throw new IllegalStateException(exception);
    }
  }

  @AfterAll
  static void report() {
    assertThat(PASSED).hasSize(218);
    System.out.printf("DiskMissionFacts parity: %d/218 domain cases passed%n", PASSED.size());
  }
}
