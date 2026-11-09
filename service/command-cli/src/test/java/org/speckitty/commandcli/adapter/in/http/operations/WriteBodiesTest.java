package org.speckitty.commandcli.adapter.in.http.operations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.speckitty.commandcli.application.write.WriteRequest;
import org.speckitty.missionstatus.domain.model.Lane;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The typed request and the request-side row rules of FR-023, FR-024, FR-027 and FR-028. */
class WriteBodiesTest {

  private static final String MISSION = "01J6XW9KQT7M0YB3N4R5CQZ2EX";
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String CONTEXT =
      "{\"actor\":\"claude\",\"checkout\":\".\",\"missionDirectory\":\"kitty-specs/m\",\"writeSurface\":\"checkout_append\",\"destinationRef\":null}";
  private static final String ROW =
      "{\"event_id\":\"01J6XW9KQT7M0YB3N4R5CQZ2EY\",\"wp_id\":\"WP01\",\"from_lane\":\"planned\",\"to_lane\":\"claimed\","
          + "\"at\":\"2026-10-27T18:00:00+00:00\",\"actor\":\"claude\",\"force\":false,\"execution_mode\":\"direct_repo\","
          + "\"reason\":null,\"review_ref\":null,\"evidence\":null,\"policy_metadata\":null}";

  private final WriteBodies bodies = new WriteBodies();

  @Test
  void aClaimExampleBecomesATypedRequestWithPythonJsonValues() {
    WriteRequest request = success(bodies.parseUnit(MISSION, example("TransitionUnit.claim.yaml")));

    assertThat(request.missionId()).isEqualTo(MISSION);
    assertThat(request.kind()).isEqualTo(WriteRequest.Kind.UNIT);
    assertThat(request.units()).hasSize(1);
    WriteRequest.UnitRequest unit = request.units().get(0);
    assertThat(unit.wpId()).isEqualTo("WP01");
    assertThat(unit.operation()).isEqualTo(WriteRequest.Operation.TRANSITION);
    assertThat(unit.rows()).hasSize(1);
    WriteRequest.Row row = unit.rows().get(0);
    assertThat(row.eventId()).isEqualTo("01M5Z7A1B2C3D4E5F6G7H8J9KM");
    assertThat(row.value()).isInstanceOf(Map.class);
    assertThat(value(row).get("from_lane")).isEqualTo("planned");
    assertThat(value(row).get("force")).isEqualTo(false);
    assertThat(value(row)).containsEntry("reason", null);
    assertThat(request.context().actor()).isEqualTo("claude");
    assertThat(request.context().checkout()).isEqualTo(".");
    assertThat(request.context().missionDirectory()).isEqualTo("kitty-specs/write-service-demo-01M5Z6N5");
    assertThat(request.context().writeSurface()).isEqualTo("checkout_append");
    assertThat(request.context().destinationRef()).isNull();
  }

  @Test
  void theLifecycleOperationIsTypedAndAbsentMeansTransition() {
    WriteRequest request = success(bodies.parseUnit(MISSION, example("TransitionUnit.start-implementation.yaml")));
    assertThat(request.units().get(0).operation()).isEqualTo(WriteRequest.Operation.START_IMPLEMENTATION);
    assertThat(WriteRequest.Operation.START_IMPLEMENTATION.wireValue()).isEqualTo("start_implementation");
    assertThat(WriteRequest.Operation.START_REVIEW.wireValue()).isEqualTo("start_review");

    WriteRequest refused = requestWithRow(ROW);
    assertThat(refused.units().get(0).operation()).isEqualTo(WriteRequest.Operation.TRANSITION);
    WriteBodies.Malformed malformed = failure(bodies.parseUnit(MISSION, bytes(unit(ROW).replace("\"rows\"", "\"operation\":\"sideways\",\"rows\""))));
    assertThat(malformed.detail()).isEqualTo("The unit's operation is not a WriteOperation value.");
  }

  @Test
  void batchesAndStandaloneAnnotationsKeepTheirKindAndContext() {
    WriteRequest batch = success(bodies.parseBatch(MISSION, example("TransitionBatch.two-units.yaml")));
    assertThat(batch.kind()).isEqualTo(WriteRequest.Kind.BATCH);
    assertThat(batch.units()).extracting(WriteRequest.UnitRequest::wpId).containsExactly("WP01", "WP02");
    assertThat(batch.context().writeSurface()).isEqualTo("coordination_ref_commit");

    WriteRequest annotation = success(bodies.parseAnnotation(MISSION, example("AnnotationRequest.note.yaml")));
    assertThat(annotation.kind()).isEqualTo(WriteRequest.Kind.ANNOTATION);
    assertThat(annotation.units()).hasSize(1);
    assertThat(annotation.units().get(0).operation()).isNull();
    assertThat(annotation.units().get(0).rows()).hasSize(1);
    assertThat(value(annotation.units().get(0).rows().get(0)).get("kind")).isEqualTo("annotation");
  }

  @Test
  void integersFloatsSignedZeroLargeIntegersAndStringsSurviveLikeJsonLoads() {
    String extras =
        ",\"integer\":1,\"floating\":1.0,\"negativeZero\":-0.0,\"large\":1180591620717411303424,"
            + "\"astral\":\"😀\",\"loneSurrogate\":\"\\ud83d\",\"nested\":[1,1.0]";
    WriteRequest request = requestWithRow(ROW.replace("\"force\":false", "\"force\":false" + extras));
    Map<String, Object> row = value(request.units().get(0).rows().get(0));

    assertThat(row.get("integer")).isEqualTo(BigInteger.ONE);
    assertThat(row.get("floating")).isEqualTo(1.0d).isInstanceOf(Double.class);
    assertThat(Double.doubleToRawLongBits((Double) row.get("negativeZero"))).isEqualTo(Double.doubleToRawLongBits(-0.0d));
    assertThat(row.get("large")).isEqualTo(BigInteger.ONE.shiftLeft(70));
    assertThat(row.get("astral")).isEqualTo("😀");
    assertThat((String) row.get("loneSurrogate")).isEqualTo("\ud83d");
    assertThat(((String) row.get("loneSurrogate")).length()).isEqualTo(1);
    assertThat(row.get("nested")).isEqualTo(List.of(BigInteger.ONE, 1.0d));
  }

  @Test
  void aRowRequiresAnObjectValue() {
    assertThatThrownBy(() -> new WriteRequest.Row("event", null)).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new WriteRequest.Row("event", List.of())).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void theTypedRequestDefendsEveryMutableContainer() {
    WriteRequest request = requestWithRow(ROW.replace("\"actor\":\"claude\"",
        "\"actor\":{\"role\":\"implementer\",\"profile\":null,\"tool\":\"claude\",\"model\":null}")
        .replace("\"policy_metadata\":null", "\"policy_metadata\":{\"tags\":[\"x\"]}"));
    WriteRequest.UnitRequest unit = request.units().get(0);
    Map<String, Object> row = value(unit.rows().get(0));

    assertThatThrownBy(() -> request.units().add(unit)).isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> unit.rows().add(unit.rows().get(0))).isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> row.put("x", "y")).isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> ((Map<String, Object>) row.get("actor")).put("x", "y")).isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> ((List<Object>) ((Map<String, Object>) row.get("policy_metadata")).get("tags")).add("y"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void aStructuredActorTheLogFoldCannotDecodeOrNoWriterMayEmitIsMalformedAtTheEdge() {
    String four = "{\"role\":\"implementer\",\"profile\":null,\"tool\":\"claude\",\"model\":null}";
    String five = "{\"role\":\"r\",\"profile\":\"p\",\"tool\":\"t\",\"model\":null,\"profile_source\":\"flag\"}";
    for (String accepted : List.of(four, "\"claude\"")) {
      success(bodies.parseUnit(MISSION, bytes(unit(ROW.replace("\"actor\":\"claude\"", "\"actor\":" + accepted)))));
    }
    for (String refused : List.of(
        five, "{}", "{\"tool\":\"x\"}", "{\"role\":null,\"profile\":null,\"tool\":null}",
        "{\"role\":\"r\",\"profile\":null,\"tool\":\"t\",\"model\":null,\"extra\":null}",
        "{\"role\":1,\"profile\":null,\"tool\":\"t\",\"model\":null}",
        "{\"role\":[],\"profile\":null,\"tool\":\"t\",\"model\":null}",
        "{\"role\":\"r\",\"profile\":\"p\",\"tool\":\"t\",\"model\":null,\"profile_source\":\"made_up\"}",
        "{\"role\":\"r\",\"profile\":\"p\",\"tool\":\"t\",\"model\":null,\"profile_source\":null}",
        "{\"role\":\"r\",\"profile\":null,\"tool\":\"t\",\"model\":null,\"profile_source\":\"flag\"}")) {
      WriteBodies.Malformed malformed =
          failure(bodies.parseUnit(MISSION, bytes(unit(ROW.replace("\"actor\":\"claude\"", "\"actor\":" + refused)))));
      assertThat(malformed.detail()).as(refused).contains("actor").doesNotContain("made_up");
    }
  }

  @Test
  void theRequestParserTakesTheLogReadersLimitsExplicitly() {
    var limits = org.speckitty.missionstatus.adapter.out.filesystem.codec.StatusRowDecoder.readConstraints();
    assertThat(limits.getMaxNestingDepth()).isEqualTo(256);
    assertThat(limits.getMaxNumberLength()).isEqualTo(4300);
    String deep = "[".repeat(300) + "1" + "]".repeat(300);
    assertThat(failure(bodies.parseUnit(MISSION, bytes(unit(ROW.replace("\"policy_metadata\":null", "\"policy_metadata\":" + deep))))).detail()).contains("not one JSON object");
    String digits = "{\"n\":" + "9".repeat(4300) + "}";
    success(bodies.parseUnit(MISSION, bytes(unit(ROW.replace("\"policy_metadata\":null", "\"policy_metadata\":" + digits)))));
    assertThat(failure(bodies.parseUnit(MISSION, bytes(unit(ROW.replace("\"policy_metadata\":null", "\"policy_metadata\":" + digits.replace("}", "9}")))))).detail())
        .contains("not one JSON object");
    String floating = "{\"x\":1." + "9".repeat(5000) + "}";
    success(bodies.parseUnit(MISSION, bytes(unit(ROW.replace("\"policy_metadata\":null", "\"policy_metadata\":" + floating)))));
  }

  @Test
  void rowZeroMustBeATransitionEvenIfKindIsNull() {
    WriteBodies.Malformed malformed = failure(bodies.parseUnit(MISSION, bytes(unit(ROW.replace("\"force\":false", "\"kind\":null,\"force\":false")))));
    assertThat(malformed.detail()).contains("first row").contains("transition");
    assertThat(failure(bodies.parseUnit(MISSION, bytes(unit(ROW.replace("\"actor\":\"claude\"", "\"actor\":\"\""))))).detail()).contains("actor");
    assertThat(failure(bodies.parseUnit(MISSION, bytes(unit(ROW.replace("\"from_lane\":\"planned\"", "\"from_lane\":null"))))).detail())
        .contains("from_lane");
  }

  @Test
  void laterAndStandaloneRowsMustBeUsefulAnnotations() {
    String annotation =
        "{\"event_id\":\"01J6XW9KQT7M0YB3N4R5CQZ2EZ\",\"kind\":\"annotation\",\"wp_id\":\"WP01\","
            + "\"actor\":\"claude\",\"delta\":{\"note\":\"ok\"}}";
    assertThat(requestWithRows(ROW + "," + annotation).units().get(0).rows()).hasSize(2);
    assertThat(failure(bodies.parseUnit(MISSION, bytes(unit(ROW + "," + annotation.replace("\"kind\":\"annotation\"", "\"kind\":null"))))).detail())
        .contains("annotation");
    String body = "{\"wpId\":\"WP01\",\"row\":" + annotation + ",\"context\":" + CONTEXT + "}";
    assertThat(success(bodies.parseAnnotation(MISSION, bytes(body))).kind()).isEqualTo(WriteRequest.Kind.ANNOTATION);
    assertThat(failure(bodies.parseAnnotation(MISSION, bytes(body.replace("{\"note\":\"ok\"}", "{}")))).detail()).contains("delta");
    assertThat(failure(bodies.parseAnnotation(MISSION, bytes(body.replace("{\"note\":\"ok\"}", "{\"note\":\"\"}")))).detail())
        .contains("delta");
  }

  @ParameterizedTest
  @ValueSource(strings = {"doing", "Planned", " planned", "IN_PROGRESS"})
  void laneAliasesAndNonCanonicalSpellingsAreMalformed(String lane) {
    WriteBodies.Malformed malformed = failure(bodies.parseUnit(MISSION, bytes(unit(ROW.replace("\"from_lane\":\"planned\"", "\"from_lane\":\"" + lane + "\"")))));
    assertThat(malformed.detail()).contains("canonical");
  }

  @Test
  void unknownLaneStringsPassToTheAuthorityAndEveryPersistedLaneIsCanonical() {
    assertThat(requestWithRow(ROW.replace("planned", "in-review")).units()).hasSize(1);
    for (Lane lane : Lane.values()) {
      String row = ROW.replace("planned", lane.wire()).replace("claimed", lane.wire());
      assertThat(requestWithRow(row).units()).as(lane.wire()).hasSize(1);
    }
  }

  @Test
  void aNonFiniteNumberAnywhereInARowIsMalformed() {
    WriteBodies.Malformed malformed =
        failure(bodies.parseUnit(MISSION, bytes(unit(ROW.replace("\"evidence\":null", "\"evidence\":{\"values\":[1,1e400]}")))));
    assertThat(malformed.detail()).contains("non-finite");
  }

  @Test
  void onlySessionTimesThatCannotBeSubtractedAreMalformed() {
    String mixed = times("2026-01-01T00:00:00", "2026-01-01T00:00:01Z");
    assertThat(failure(bodies.parseUnit(MISSION, bytes(unit(mixed)))).detail()).contains("naive").contains("aware");
    assertThat(requestWithRow(times("2026-01-01T00:00:00Z", "2026-01-01T00:00:01+00:00")).units()).hasSize(1);
    assertThat(requestWithRow(times("2026-01-01T00:00:00", "2026-01-01T00:00:01")).units()).hasSize(1);
    assertThat(requestWithRow(times("not-a-time", "also-not-a-time")).units()).hasSize(1);
  }

  @Test
  void aBatchRuleFailureNamesItsUnit() {
    String fourth = unit(ROW.replace("planned", " planned"));
    String body = "{\"units\":[" + String.join(",", unit(ROW), unit(ROW), unit(ROW), fourth) + "]}";

    WriteBodies.Malformed malformed = failure(bodies.parseBatch(MISSION, bytes(body)));

    assertThat(malformed.instance()).isEqualTo("#/units/3");
  }

  @Test
  void everyContractExampleWithRowsPassesTheNewRules() throws Exception {
    try (Stream<Path> files = Files.list(ContractSchemas.root().resolve("examples"))) {
      List<Path> examples = files.filter(path -> path.toString().endsWith(".yaml")).filter(WriteBodiesTest::carriesRows).sorted().toList();
      assertThat(examples).hasSize(4);
      for (Path example : examples) {
        JsonNode root = ContractSchemas.yaml(example);
        byte[] body = OperationsTest.body(example).getBytes(StandardCharsets.UTF_8);
        WriteBodies.Parsed parsed =
            root.has("units") ? bodies.parseBatch(MISSION, body) : root.has("row") ? bodies.parseAnnotation(MISSION, body) : bodies.parseUnit(MISSION, body);
        assertThat(parsed).as(example.getFileName().toString()).isInstanceOf(WriteBodies.Parsed.Success.class);
      }
    }
  }

  private static String times(String start, String end) {
    return ROW.replace("\"force\":false", "\"session_started_at\":\"" + start + "\",\"session_ended_at\":\"" + end + "\",\"force\":false");
  }

  private static boolean carriesRows(Path path) {
    JsonNode root = ContractSchemas.yaml(path);
    return root.has("rows") || root.has("row") || (root.has("units") && root.get("units").get(0).has("rows"));
  }

  private static byte[] example(String name) {
    return OperationsTest.body(ContractSchemas.root().resolve("examples").resolve(name)).getBytes(StandardCharsets.UTF_8);
  }

  private static String unit(String rows) {
    return "{\"wpId\":\"WP01\",\"rows\":[" + rows + "],\"context\":" + CONTEXT + "}";
  }

  private static byte[] bytes(String body) {
    return body.getBytes(StandardCharsets.UTF_8);
  }

  private WriteRequest requestWithRow(String row) {
    return success(bodies.parseUnit(MISSION, bytes(unit(row))));
  }

  private WriteRequest requestWithRows(String rows) {
    return success(bodies.parseUnit(MISSION, bytes(unit(rows))));
  }

  private static WriteRequest success(WriteBodies.Parsed parsed) {
    if (parsed instanceof WriteBodies.Parsed.Success success) {
      return success.request();
    }
    throw new AssertionError("expected success but was " + parsed);
  }

  private static WriteBodies.Malformed failure(WriteBodies.Parsed parsed) {
    if (parsed instanceof WriteBodies.Parsed.Failure failure) {
      return failure.malformed();
    }
    throw new AssertionError("expected failure but was " + parsed);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> value(WriteRequest.Row row) {
    return (Map<String, Object>) row.value();
  }
}
