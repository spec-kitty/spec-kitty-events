package org.speckitty.commandcli.adapter.in.http.operations;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.speckitty.commandcli.adapter.in.http.edge.RawClient;
import org.speckitty.commandcli.domain.authority.DetailPolicy;
import org.speckitty.commandcli.domain.service.RefusalCode;
import org.speckitty.commandcli.domain.service.RefusalReason;
import org.speckitty.missionstatus.adapter.out.filesystem.codec.StatusRowEncoder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** WP04's edge/service cases, every guard reason and every invalid status_append row over real HTTP. */
class ProtocolCorpusTest {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Path CASES = repositoryPath("src/spec_kitty_events/conformance/status_transition_parity/cases");
  private static final Path APPEND_FIXTURES = repositoryPath("src/spec_kitty_events/conformance/fixtures/status_append/invalid");
  private static final Set<String> PASSED = ConcurrentHashMap.newKeySet();
  private static final Set<String> GUARD_REASONS = ConcurrentHashMap.newKeySet();
  private static WriteRig rig;

  @BeforeAll
  static void start() {
    rig = WriteRig.start();
  }

  @TestFactory
  Stream<DynamicTest> protocolCases() throws IOException {
    List<Path> paths;
    try (Stream<Path> listed = Files.list(CASES)) {
      paths = listed.filter(path -> path.toString().endsWith(".json")).sorted()
          .filter(path -> !"domain".equals(read(path).get("layer").asString())).toList();
    }
    return paths.stream().map(path -> DynamicTest.dynamicTest(path.getFileName().toString(), () -> runCase(rig, path)));
  }

  @TestFactory
  Stream<DynamicTest> everyGuardReasonOverHttp() throws IOException {
    Map<String, Path> guards = new LinkedHashMap<>();
    try (Stream<Path> listed = Files.list(CASES)) {
      for (Path path : listed.filter(item -> item.toString().endsWith(".json")).sorted().toList()) {
        JsonNode testCase = read(path);
        JsonNode expected = testCase.required("expected");
        if ("domain".equals(testCase.required("layer").asString())
            && "refusal".equals(expected.required("result").asString()) && expected.hasNonNull("reason")) {
          guards.putIfAbsent(expected.required("reason").asString(), path);
        }
      }
    }
    return guards.entrySet().stream().map(entry -> DynamicTest.dynamicTest("guard " + entry.getKey(), () -> {
      runCase(rig, entry.getValue());
      GUARD_REASONS.add(entry.getKey());
    }));
  }

  @TestFactory
  Stream<DynamicTest> invalidStatusRows() throws IOException {
    List<Path> paths;
    try (Stream<Path> listed = Files.list(APPEND_FIXTURES)) {
      paths = listed.sorted().toList();
    }
    return paths.stream().map(path -> DynamicTest.dynamicTest(path.getFileName().toString(), () -> {
      rig.replaceMission(WriteRig.MISSION, "mission", Map.of(
          "WP01.md", "---\nwork_package_id: WP01\ndependencies: []\nsubtasks: []\n---\n",
          "WP02-write.md", "---\nwork_package_id: WP02\ndependencies: []\nsubtasks: []\n---\n"),
          WriteRig.priorPlannedLine().getBytes(StandardCharsets.UTF_8));
      String fixture = JSON.readTree(Files.readString(path)).required("input_json").asString();
      String row = WriteRig.claimRow("01J6XW9KQT7M0YB3N4R5CQZ2EZ");
      row = row.substring(0, row.length() - 1) + "," + fixture.substring(1, fixture.length() - 1) + "}";
      byte[] before = Files.exists(rig.log()) ? Files.readAllBytes(rig.log()) : null;
      RawClient.Reply reply = rig.post("unit", WriteRig.unit(row));
      assertThat(reply.status()).as(reply.text()).isEqualTo(400);
      assertThat(JSON.readTree(reply.body()).get("code").asString()).isEqualTo("REQUEST_MALFORMED");
      assertThat(before == null ? null : Files.readAllBytes(rig.log())).isEqualTo(before);
      PASSED.add(path.getFileName().toString());
    }));
  }

  private static void runCase(WriteRig rig, Path path) throws Exception {
    JsonNode testCase = read(path);
    JsonNode mission = testCase.required("mission");
    String missionId = mission.required("mission_id").asString();
    String directory = mission.required("directory").asString();
    Map<String, String> tasks = new LinkedHashMap<>();
    testCase.required("tasks").properties().forEach(entry -> tasks.put(entry.getKey(), entry.getValue().asString()));
    rig.replaceMission(missionId, directory, tasks, initialLog(testCase));
    byte[] before = Files.exists(rig.log()) ? Files.readAllBytes(rig.log()) : null;
    JsonNode request = testCase.required("request");
    String kind = request.required("kind").asString();
    String body = requestBody(request, directory);
    RawClient.Reply reply = rig.post(kind.equals("batch") ? "batch" : "unit", body);
    JsonNode expected = testCase.required("expected");
    if ("ack".equals(expected.required("result").asString())) {
      assertThat(reply.status()).as(testCase.required("id").asString() + ": " + reply.text()).isEqualTo(201);
      JsonNode ack = JSON.readTree(reply.body());
      JsonNode units = expected.path("units");
      for (int index = 0; index < units.size(); index++) {
        JsonNode actual = kind.equals("batch") ? ack.path("units").get(index) : ack;
        assertThat(actual.path("appendedLines").asInt()).as(testCase.path("id").asString())
            .isEqualTo(units.get(index).path("appendedLines").asInt());
        assertThat(actual.path("replayed").asBoolean()).isEqualTo(units.get(index).path("replayed").asBoolean());
      }
      if (kind.equals("batch")) assertThat(ack.path("fsyncs").asInt()).isEqualTo(expected.path("fsyncs").asInt());
    } else {
      RefusalCode code = RefusalCode.valueOf(expected.required("code").asString());
      assertThat(reply.status()).as(testCase.required("id").asString() + ": " + reply.text()).isEqualTo(code.status());
      JsonNode refusal = JSON.readTree(reply.body());
      assertThat(refusal.path("code").asString()).isEqualTo(code.name());
      assertThat(refusal.path("type").asString(null)).isEqualTo(expected.path("reason").asString(null));
      int instance = expected.path("unit").isNumber() ? expected.path("unit").asInt() : -1;
      assertThat(refusal.path("instance").asString(null)).isEqualTo(instance < 0 ? null : "#/units/" + instance);
      assertThat(refusal.path("detail").asString()).isEqualTo(fixedDetail(testCase, code));
      byte[] after = Files.exists(rig.log()) ? Files.readAllBytes(rig.log()) : null;
      assertThat(after).isEqualTo(before);
    }
    PASSED.add(testCase.required("id").asString());
  }

  private static String fixedDetail(JsonNode testCase, RefusalCode code) {
    JsonNode expected = testCase.required("expected");
    if (expected.hasNonNull("reason")) {
      String urn = expected.required("reason").asString();
      RefusalReason reason = Arrays.stream(RefusalReason.values()).filter(item -> item.urn().equals(urn)).findFirst().orElseThrow();
      return DetailPolicy.detail(reason, expected.required("message").asString());
    }
    if ("alias-noncanonical-row".equals(testCase.required("id").asString())) {
      return "A transition row's lanes must use canonical persisted spellings without aliases.";
    }
    return switch (code) {
      case BOOKKEEPING_DOUBLE_EVENT_ID -> "An event_id occurs more than once in the request.";
      case EVENT_ID_CONFLICT -> "The request's event ids conflict with the status event log.";
      case STATUS_EVENT_PERSISTENCE_VERIFICATION_FAILED ->
          "The status event log could not be verified or persisted; nothing was acknowledged.";
      default -> throw new AssertionError("No fixed protocol detail for " + code);
    };
  }

  private static String requestBody(JsonNode request, String directory) {
    Map<String, Object> context = new LinkedHashMap<>();
    context.put("actor", "corpus");
    context.put("checkout", ".");
    context.put("missionDirectory", "kitty-specs/" + directory);
    context.put("writeSurface", "checkout_append");
    context.put("destinationRef", null);
    List<Map<String, Object>> units = new ArrayList<>();
    for (JsonNode original : request.required("units")) {
      Map<String, Object> unit = new LinkedHashMap<>();
      unit.put("wpId", original.required("wpId").asString());
      if (original.has("operation")) unit.put("operation", original.get("operation").asString());
      unit.put("rows", valueTree(original.required("rows")));
      unit.put("context", context);
      units.add(unit);
    }
    Object body = request.required("kind").asString().equals("batch") ? Map.of("units", units) : units.getFirst();
    return JSON.writeValueAsString(body);
  }

  private static byte[] initialLog(JsonNode testCase) throws IOException {
    if (testCase.has("log_text")) return testCase.get("log_text").asString().getBytes(StandardCharsets.UTF_8);
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    JsonNode firstRequestId = testCase.path("request").path("units").path(0).path("rows").path(0).path("event_id");
    for (JsonNode row : testCase.path("log")) {
      // The new-then-replayed protocol case carries the new row's bytes as reference data; its prior log omits that row.
      if ("replay-new-then-replayed-conflict".equals(testCase.required("id").asString())
          && row.path("event_id").equals(firstRequestId)) continue;
      @SuppressWarnings("unchecked")
      Map<String, Object> value = (Map<String, Object>) valueTree(row);
      bytes.write(StatusRowEncoder.encodeLine(value));
    }
    return bytes.toByteArray();
  }

  private static Object valueTree(JsonNode node) {
    if (node == null || node.isNull()) return null;
    if (node.isObject()) {
      Map<String, Object> value = new LinkedHashMap<>();
      node.properties().forEach(entry -> value.put(entry.getKey(), valueTree(entry.getValue())));
      return value;
    }
    if (node.isArray()) {
      List<Object> value = new ArrayList<>();
      node.forEach(item -> value.add(valueTree(item)));
      return value;
    }
    if (node.isIntegralNumber()) return node.bigIntegerValue();
    if (node.isFloatingPointNumber()) return node.doubleValue();
    if (node.isBoolean()) return node.booleanValue();
    return node.asString();
  }

  private static JsonNode read(Path path) {
    try {
      return JSON.readTree(Files.readString(path));
    } catch (IOException exception) {
      throw new IllegalStateException(exception);
    }
  }

  private static Path repositoryPath(String relative) {
    Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
    while (current != null) {
      if (Files.isRegularFile(current.resolve("pyproject.toml"))) return current.resolve(relative);
      current = current.getParent();
    }
    throw new IllegalStateException("repository root not found");
  }

  @AfterAll
  static void report() throws Exception {
    rig.close();
    assertThat(GUARD_REASONS).hasSize(14); // workspace_context_required is recorded unreachable over the wire.
    JsonNode unreachable = read(CASES.resolveSibling("unreachable.json"));
    assertThat(unreachable.has("urn:spec-kitty:write-refusal:workspace_context_required")).isTrue();
    assertThat(PASSED).hasSize(30);
    System.out.printf("Protocol/service corpus 11/11; guard reasons 14/14 (+1 recorded unreachable); invalid status rows 5/5 passed%n");
  }
}
