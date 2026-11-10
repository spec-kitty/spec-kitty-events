package org.speckitty.commandcli.adapter.in.http.operations;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.speckitty.commandcli.adapter.in.http.edge.RawClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The request-only {@code implementationEvidencePresent} over real HTTP: byte parity, replay and refusal (FR-010, FR-011, NFR-002). */
class EvidenceFieldHttpTest {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String HANDOFF_ID = "01J6XW9KQT7M0YB3N4R5CQZ2F9";
  private static final String HANDOFF = WriteRig.transitionRow(HANDOFF_ID, "WP01", "in_progress", "for_review", "java-jenny");
  private static final String REFUSED_URN = "urn:spec-kitty:write-refusal:implementation_evidence_required";
  private static WriteRig rig;

  @BeforeAll
  static void start() {
    rig = WriteRig.start();
  }

  @AfterAll
  static void stop() throws Exception {
    rig.close();
  }

  @Test
  void theAppendedBytesAreIdenticalWithTrueNullAndWithoutTheField() throws Exception {
    byte[] without = appendedBytes(null);
    assertThat(appendedBytes("true")).isEqualTo(without);
    assertThat(appendedBytes("null")).isEqualTo(without);
    assertThat(new String(without, StandardCharsets.UTF_8)).contains(HANDOFF_ID).doesNotContain("mplementationEvidence");
  }

  @Test
  void anAcceptedUnitRetriedWithOrWithoutTheFieldIsAReplay() throws Exception {
    for (String[] pair : new String[][] {{"true", null}, {null, "true"}, {"true", "false"}, {null, "false"}, {"true", "null"}}) {
      seedInProgress();
      assertThat(JSON.readTree(rig.post("unit", unit(pair[0])).body()).path("appendedLines").asInt()).isOne();
      byte[] accepted = Files.readAllBytes(rig.log());
      RawClient.Reply retry = rig.post("unit", unit(pair[1]));
      assertThat(retry.status()).as(retry.text()).isEqualTo(201);
      JsonNode ack = JSON.readTree(retry.body());
      assertThat(ack.path("appendedLines").asInt()).as(pair[0] + " then " + pair[1]).isZero();
      assertThat(ack.path("replayed").asBoolean()).isTrue();
      assertThat(Files.readAllBytes(rig.log())).isEqualTo(accepted);
    }
  }

  @Test
  void explicitFalseRefusesAndLeavesTheLogByteIdenticalWhileTrueAppends() throws Exception {
    seedInProgress();
    byte[] before = Files.readAllBytes(rig.log());
    RawClient.Reply refused = rig.post("unit", unit("false"));
    assertThat(refused.status()).as(refused.text()).isEqualTo(409);
    JsonNode body = JSON.readTree(refused.body());
    assertThat(body.path("code").asString()).isEqualTo("TRANSITION_REJECTED");
    assertThat(body.path("type").asString()).isEqualTo(REFUSED_URN);
    assertThat(Files.readAllBytes(rig.log())).isEqualTo(before);
    // Positive control: the same unit, with the flag true, does append.
    assertThat(rig.post("unit", unit("true")).status()).isEqualTo(201);
    assertThat(Files.readAllBytes(rig.log())).isNotEqualTo(before);
  }

  @Test
  void aWrongTypedFieldIsMalformedAndWritesNothing() throws Exception {
    seedInProgress();
    byte[] before = Files.readAllBytes(rig.log());
    for (String bad : new String[] {"\"false\"", "0", "{}"}) {
      RawClient.Reply reply = rig.post("unit", unit(bad));
      assertThat(reply.status()).as(reply.text()).isEqualTo(400);
      assertThat(JSON.readTree(reply.body()).path("code").asString()).isEqualTo("REQUEST_MALFORMED");
      assertThat(Files.readAllBytes(rig.log())).isEqualTo(before);
    }
  }

  private static byte[] appendedBytes(String field) throws Exception {
    seedInProgress();
    RawClient.Reply reply = rig.post("unit", unit(field));
    assertThat(reply.status()).as(reply.text()).isEqualTo(201);
    return Files.readAllBytes(rig.log());
  }

  private static void seedInProgress() throws Exception {
    String log = WriteRig.priorPlannedLine()
        + WriteRig.encodedRow(WriteRig.transitionRow("01J6XW9KQT7M0YB3N4R5CQZ2F7", "WP01", "planned", "claimed", "java-jenny"))
        + WriteRig.encodedRow(WriteRig.transitionRow("01J6XW9KQT7M0YB3N4R5CQZ2F8", "WP01", "claimed", "in_progress", "java-jenny"));
    rig.replaceMission(WriteRig.MISSION, "mission", Map.of(
        "WP01.md", "---\nwork_package_id: WP01\ndependencies: []\nsubtasks: []\n---\n",
        "WP02-write.md", "---\nwork_package_id: WP02\ndependencies: []\nsubtasks: []\n---\n"), log.getBytes(StandardCharsets.UTF_8));
  }

  private static String unit(String field) {
    String body = WriteRig.unit(HANDOFF);
    return field == null ? body : body.replace("\"rows\"", "\"implementationEvidencePresent\":" + field + ",\"rows\"");
  }
}
