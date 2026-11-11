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

/**
 * WP04's edge/service cases, every guard reason, every invalid status_append row and every ack case of the parity corpus
 * (domain ones included) over real HTTP. Ack cases also assert the bytes appended to the log against
 * {@code conformance/ack-bytes.json}, a committed test resource extracted from core (ruling M2-R2); the tests never read core.
 */
class ProtocolCorpusTest {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Path CASES = repositoryPath("src/spec_kitty_events/conformance/status_transition_parity/cases");
  private static final Path APPEND_FIXTURES = repositoryPath("src/spec_kitty_events/conformance/fixtures/status_append/invalid");
  private static final Set<String> PASSED = ConcurrentHashMap.newKeySet();
  private static final String ACK_BYTES_RESOURCE = "/conformance/ack-bytes.json";
  /** Ack cases that cannot run over HTTP, each with its principled reason: an explicit list, never a silent skip. */
  private static final Map<String, AckExclusion> ACK_EXCLUSIONS = Map.of(
      "dependency-no-tasks-file-accepted", new AckExclusion(RefusalCode.WP_NOT_FOUND, null,
          "(#256) The case has no tasks files at all: core's authority accepts the transition, but the service's wire-level "
              + "precondition (WriteService.checkWorkPackages) refuses an unknown work package before the authority "
              + "is consulted, so this domain ack is unreachable over HTTP."),
      "annotation-subtasks-complete-unlocks-review", new AckExclusion(RefusalCode.REQUEST_MALFORMED,
          "The unit's operation is not a WriteOperation value.",
          "(#256) The case is a batch whose first unit is a standalone annotation unit. The wire's TransitionBatch holds only "
              + "transition units (operation is transition, start_implementation or start_review); a standalone "
              + "annotation travels alone on /inner-state-annotations, so this batch shape has no wire form."));
  private static final Set<String> ACK_RUN = ConcurrentHashMap.newKeySet();
  private static final Set<String> GUARD_REASONS = ConcurrentHashMap.newKeySet();
  private static final String EVIDENCE_FIELD_SUFFIX = " (with implementationEvidencePresent)";
  private static final Set<String> EVIDENCE_FIELD_CASES = ConcurrentHashMap.newKeySet();
  private static final int ACK_COUNT = 91; // 86 domain + 5 service ack cases of the corpus.
  private static WriteRig rig;

  /** What the service answers instead of the domain ack, and why the ack cannot be reached over the wire. */
  private record AckExclusion(RefusalCode code, String detail, String reason) {}

  @BeforeAll
  static void start() {
    rig = WriteRig.start();
  }

  @TestFactory
  Stream<DynamicTest> protocolCases() throws IOException {
    List<Path> paths;
    try (Stream<Path> listed = Files.list(CASES)) {
      paths = listed.filter(path -> path.toString().endsWith(".json")).sorted()
          .filter(path -> isAck(read(path)) ? !ACK_EXCLUSIONS.containsKey(read(path).get("id").asString())
              : !"domain".equals(read(path).get("layer").asString())).toList();
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
          // One representative per (reason, has implementationEvidencePresent): the evidence half of
          // implementation_evidence_required must not displace the subtask half over HTTP (#224).
          guards.putIfAbsent(guardKey(testCase), path);
        }
      }
    }
    return guards.entrySet().stream().map(entry -> DynamicTest.dynamicTest("guard " + entry.getKey(), () -> {
      runCase(rig, entry.getValue());
      GUARD_REASONS.add(entry.getKey().replace(EVIDENCE_FIELD_SUFFIX, ""));
      if (entry.getKey().endsWith(EVIDENCE_FIELD_SUFFIX)) EVIDENCE_FIELD_CASES.add(entry.getValue().getFileName().toString());
    }));
  }

  /** The exclusions are asserted, not assumed: each excluded ack case is refused as recorded and appends nothing. */
  @TestFactory
  Stream<DynamicTest> excludedAckCasesAreRefusedAsRecorded() {
    return ACK_EXCLUSIONS.keySet().stream().sorted().map(id -> DynamicTest.dynamicTest(id, () -> {
      AckExclusion exclusion = ACK_EXCLUSIONS.get(id);
      JsonNode testCase = read(CASES.resolve(id + ".json"));
      assertThat(isAck(testCase)).isTrue();
      JsonNode mission = testCase.required("mission");
      Map<String, String> tasks = new LinkedHashMap<>();
      testCase.required("tasks").properties().forEach(entry -> tasks.put(entry.getKey(), entry.getValue().asString()));
      rig.replaceMission(mission.required("mission_id").asString(), mission.required("directory").asString(), tasks,
          initialLog(testCase));
      byte[] before = Files.readAllBytes(rig.log());
      RawClient.Reply reply = rig.post(testCase.required("request").required("kind").asString(),
          requestBody(testCase.required("request"), mission.required("directory").asString()));
      assertThat(reply.status()).as(reply.text()).isEqualTo(exclusion.code().status());
      JsonNode refusal = JSON.readTree(reply.body());
      assertThat(refusal.path("code").asString()).isEqualTo(exclusion.code().name());
      if (exclusion.detail() != null) assertThat(refusal.path("detail").asString()).isEqualTo(exclusion.detail());
      assertThat(Files.readAllBytes(rig.log())).isEqualTo(before);
    }));
  }

  private static boolean isAck(JsonNode testCase) {
    return "ack".equals(testCase.required("expected").required("result").asString());
  }

  private static final JsonNode ACK_BYTES = loadAckBytes();

  private static JsonNode ackBytes() {
    return ACK_BYTES;
  }

  private static JsonNode loadAckBytes() {
    try (java.io.InputStream stream = ProtocolCorpusTest.class.getResourceAsStream(ACK_BYTES_RESOURCE)) {
      if (stream == null) throw new IllegalStateException("missing test resource " + ACK_BYTES_RESOURCE);
      return JSON.readTree(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
    } catch (IOException exception) {
      throw new IllegalStateException(exception);
    }
  }

  private static String guardKey(JsonNode testCase) {
    boolean withField = false;
    for (JsonNode unit : testCase.required("request").required("units")) withField |= unit.has("implementationEvidencePresent");
    return testCase.required("expected").required("reason").asString() + (withField ? EVIDENCE_FIELD_SUFFIX : "");
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
      JsonNode fixtureDocument = JSON.readTree(Files.readString(path));
      String fixture = fixtureDocument.required("input_json").asString();
      String row = WriteRig.claimRow("01J6XW9KQT7M0YB3N4R5CQZ2EZ");
      row = row.substring(0, row.length() - 1) + "," + fixture.substring(1, fixture.length() - 1) + "}";
      byte[] before = Files.exists(rig.log()) ? Files.readAllBytes(rig.log()) : null;
      RawClient.Reply reply = rig.post("unit", WriteRig.unit(row));
      assertThat(reply.status()).as(reply.text()).isEqualTo(400);
      JsonNode refusal = JSON.readTree(reply.body());
      assertThat(refusal.get("code").asString()).isEqualTo("REQUEST_MALFORMED");
      String expectedDetail = switch (path.getFileName().toString()) {
        case "non_finite_nan.json", "non_finite_infinity.json" -> "The body is not one JSON object.";
        case "non_finite_overflow.json" -> "A row must not contain a non-finite number.";
        case "session_pair_naive_then_aware.json", "session_pair_aware_then_naive.json" ->
            "A row's session timestamps must both be naive or both be aware.";
        default -> throw new AssertionError("unknown invalid status fixture");
      };
      assertThat(refusal.path("detail").asString()).isEqualTo(expectedDetail);
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
    String caseId = testCase.required("id").asString();
    if ("write-location-coordination-routed-mission".equals(caseId)) {
      Files.writeString(rig.missionDirectory.resolve("meta.json"),
          "{\"mission_id\":\"" + missionId + "\",\"coordination_branch\":\"kitty/mission-status\"}\n");
    }
    if ("catalog-broken-sibling-meta".equals(caseId)) {
      Path broken = rig.repository.resolve("kitty-specs/broken-sibling");
      Files.createDirectories(broken);
      Files.writeString(broken.resolve("meta.json"), "{not json");
    }
    if ("own-meta-bom".equals(caseId)) {
      Path metadata = rig.missionDirectory.resolve("meta.json");
      Files.writeString(metadata, "\uFEFF" + Files.readString(metadata));
    }
    byte[] before = Files.exists(rig.log()) ? Files.readAllBytes(rig.log()) : null;
    JsonNode request = testCase.required("request");
    String kind = request.required("kind").asString();
    String body = requestBody(request, directory);
    RawClient.Reply reply = rig.post(kind.equals("batch") || kind.equals("annotation") ? kind : "unit", body);
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
      assertAppendedBytes(caseId, before, units.size());
      ACK_RUN.add(caseId);
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

  /** The log grew by exactly the committed expectation: the old bytes untouched, then each unit's text in order. */
  private static void assertAppendedBytes(String caseId, byte[] before, int unitCount) throws IOException {
    JsonNode expectedUnits = ackBytes().get(caseId);
    assertThat(expectedUnits).as(caseId + " has an entry in " + ACK_BYTES_RESOURCE).isNotNull();
    assertThat(expectedUnits.size()).as(caseId + " unit texts").isEqualTo(unitCount);
    StringBuilder appended = new StringBuilder();
    expectedUnits.forEach(text -> appended.append(text.asString()));
    byte[] prior = before == null ? new byte[0] : before;
    byte[] after = Files.readAllBytes(rig.log());
    assertThat(Arrays.copyOf(after, Math.min(after.length, prior.length))).as(caseId + " prior bytes").isEqualTo(prior);
    assertThat(new String(after, prior.length, after.length - prior.length, StandardCharsets.UTF_8))
        .as(caseId + " appended bytes").isEqualTo(appended.toString());
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
      case WRITE_LOCATION_UNVERIFIED -> "The write location could not be verified.";
      case MISSION_NOT_FOUND -> "The Mission was not found.";
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
      if (original.has("implementationEvidencePresent")) {
        unit.put("implementationEvidencePresent", valueTree(original.get("implementationEvidencePresent")));
      }
      unit.put("rows", valueTree(original.required("rows")));
      unit.put("context", context);
      units.add(unit);
    }
    if (request.required("kind").asString().equals("annotation")) {
      // The standalone annotation endpoint takes one row, not a unit with rows.
      Map<String, Object> only = units.getFirst();
      Map<String, Object> annotation = new LinkedHashMap<>();
      annotation.put("wpId", only.get("wpId"));
      annotation.put("row", ((List<?>) only.get("rows")).getFirst());
      annotation.put("context", only.get("context"));
      return JSON.writeValueAsString(annotation);
    }
    Object body = request.required("kind").asString().equals("batch") ? Map.of("units", units) : units.getFirst();
    return JSON.writeValueAsString(body);
  }

  private static byte[] initialLog(JsonNode testCase) throws IOException {
    if (testCase.has("log_text")) return testCase.get("log_text").asString().getBytes(StandardCharsets.UTF_8);
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    for (JsonNode row : testCase.path("log")) {
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
    assertThat(GUARD_REASONS).hasSize(15); // workspace_context_required is recorded unreachable over the wire.
    JsonNode unreachable = read(CASES.resolveSibling("unreachable.json"));
    assertThat(unreachable.has("urn:spec-kitty:write-refusal:workspace_context_required")).isTrue();
    assertThat(unreachable.has("partial")).isFalse(); // both halves of implementation_evidence_required are reached.
    // The evidence half goes over HTTP next to the subtask half: 34 before the field, plus one representative.
    assertThat(EVIDENCE_FIELD_CASES).containsExactly("guard-evidence-explicit-false.json");
    JsonNode expectedBytes = ackBytes();
    try (Stream<Path> listed = Files.list(CASES)) {
      assertThat(listed.filter(path -> path.toString().endsWith(".json")).filter(path -> isAck(read(path))).count())
          .as("ack cases in the corpus").isEqualTo(ACK_COUNT);
    }
    assertThat(ACK_RUN).as("every ack case of the corpus, or an explicit exclusion").hasSize(ACK_COUNT - ACK_EXCLUSIONS.size());
    assertThat(ACK_RUN).as("resource keys are exactly the ack cases run (no orphans)")
        .containsExactlyInAnyOrderElementsOf(expectedBytes.propertyNames().stream()
            .filter(key -> !ACK_EXCLUSIONS.containsKey(key)).toList());
    assertThat(PASSED).hasSize(35 + ACK_COUNT - 5 - ACK_EXCLUSIONS.size());
    System.out.printf("Protocol/service corpus 14/14; guard reasons 15/15 (+1 recorded unreachable; evidence field case 1/1); invalid status rows 5/5 passed; ack cases %d/%d over HTTP with byte assertions%n", ACK_RUN.size(), ACK_COUNT);
  }
}
