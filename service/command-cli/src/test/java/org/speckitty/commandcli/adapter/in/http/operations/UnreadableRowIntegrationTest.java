package org.speckitty.commandcli.adapter.in.http.operations;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.speckitty.commandcli.adapter.in.http.edge.RawClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * PR #230 squad (renata, MAJOR 1 and 2): the service never acknowledges a row it cannot read back from its own log, so no accepted
 * row can make the Mission's log unreadable and every later write refuse. Each case is checked over real HTTP: the refusal is a
 * contract {@code WriteRefusal}, nothing is written, and the next legal write to the same Mission still succeeds.
 */
class UnreadableRowIntegrationTest {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String ID1 = "01J6XW9KQT7M0YB3N4R5CQZ2E1";
  private static final String ID2 = "01J6XW9KQT7M0YB3N4R5CQZ2E2";
  private static final String ID3 = "01J6XW9KQT7M0YB3N4R5CQZ2E3";
  private static final String FOUR_KEY_ACTOR = "{\"role\":\"implementer\",\"profile\":null,\"tool\":\"claude\",\"model\":null}";

  @Test
  void aRowNestedPastTheReadersLimitIsRefusedAtTheEdgeAndTheLogStaysWritable() throws Exception {
    try (WriteRig rig = WriteRig.start()) {
      String row = withMetadata(WriteRig.claimRow(ID1), "[".repeat(300) + "1" + "]".repeat(300));
      byte[] before = Files.readAllBytes(rig.log());
      assertRefusal(rig.post("unit", WriteRig.unit(row)), 400, null);
      assertThat(Files.readAllBytes(rig.log())).isEqualTo(before);
      assertThat(rig.post("unit", WriteRig.unit(WriteRig.claimRow(ID2))).status()).isEqualTo(201);
    }
  }

  @Test
  void aRowNestedWithinTheReadersLimitIsAcknowledgedAndReadBack() throws Exception {
    try (WriteRig rig = WriteRig.start()) {
      String row = withMetadata(WriteRig.claimRow(ID1), "[".repeat(200) + "1" + "]".repeat(200));
      assertThat(rig.post("unit", WriteRig.unit(row)).status()).isEqualTo(201);
      RawClient.Reply next = rig.post("annotation", WriteRig.annotation("WP01", WriteRig.annotationRow(ID2, "WP01", "n")));
      assertThat(next.status()).as(next.text()).isEqualTo(201);
    }
  }

  @Test
  void anIntegerTheReaderAcceptsIsAcceptedAtTheEdgeAndOneItRefusesIsNot() throws Exception {
    try (WriteRig rig = WriteRig.start()) {
      assertThat(rig.post("unit", WriteRig.unit(withMetadata(WriteRig.claimRow(ID1), "9".repeat(2000)))).status()).isEqualTo(201);
      assertThat(rig.post("annotation", WriteRig.annotation("WP01", WriteRig.annotationRow(ID2, "WP01", "n"))).status()).isEqualTo(201);
      byte[] before = Files.readAllBytes(rig.log());
      assertRefusal(rig.post("unit", WriteRig.unit(withMetadata(claimFrom("in_progress", ID3), "9".repeat(4301)))), 400, null);
      assertThat(Files.readAllBytes(rig.log())).isEqualTo(before);
    }
  }

  @Test
  void aStructuredActorTheFoldCannotDecodeIsRefusedAndTheLogStaysWritable() throws Exception {
    try (WriteRig rig = WriteRig.start()) {
      byte[] before = Files.readAllBytes(rig.log());
      for (String actor : List.of("{\"tool\":\"x\"}", FOUR_KEY_ACTOR.replace("\"claude\"", "7"))) {
        assertRefusal(rig.post("unit", WriteRig.unit(withActor(WriteRig.claimRow(ID1), actor))), 400, null);
        assertThat(Files.readAllBytes(rig.log())).isEqualTo(before);
      }
      assertThat(rig.post("unit", WriteRig.unit(WriteRig.claimRow(ID2))).status()).isEqualTo(201);
    }
  }

  @Test
  void aStructuredActorTheFoldDecodesIsAcknowledgedAndReadBack() throws Exception {
    try (WriteRig rig = WriteRig.start()) {
      assertThat(rig.post("unit", WriteRig.unit(withActor(WriteRig.claimRow(ID1), FOUR_KEY_ACTOR))).status()).isEqualTo(201);
      RawClient.Reply next = rig.post("annotation", WriteRig.annotation("WP01", WriteRig.annotationRow(ID2, "WP01", "n")));
      assertThat(next.status()).as(next.text()).isEqualTo(201);
    }
  }

  @Test
  void aBatchWithAnUnreadableClaimAndASecondUnitIsACodedRefusalNamingTheUnit() throws Exception {
    try (WriteRig rig = WriteRig.start()) {
      byte[] before = Files.readAllBytes(rig.log());
      String bad = WriteRig.unit("WP01", null, withActor(WriteRig.claimRow(ID1), "{\"tool\":\"x\"}"));
      String second = WriteRig.unit("WP02", null, WriteRig.transitionRow(ID2, "WP02", "genesis", "planned", "java-jenny"));
      RawClient.Reply reply = rig.post("batch", WriteRig.batch(bad, second));
      assertRefusal(reply, 400, "#/units/0");
      assertThat(Files.readAllBytes(rig.log())).isEqualTo(before);
      assertThat(rig.post("unit", WriteRig.unit(WriteRig.claimRow(ID3))).status()).isEqualTo(201);
    }
  }

  private static String claimFrom(String lane, String id) {
    return WriteRig.claimRow(id).replace("\"from_lane\":\"planned\"", "\"from_lane\":\"" + lane + "\"");
  }

  private static String withMetadata(String row, String json) {
    return row.replace("\"policy_metadata\":null", "\"policy_metadata\":" + json);
  }

  private static String withActor(String row, String actor) {
    return row.replace("\"actor\":\"java-jenny\"", "\"actor\":" + actor);
  }

  private static void assertRefusal(RawClient.Reply reply, int status, String instance) throws Exception {
    assertThat(reply.status()).as(reply.text()).isEqualTo(status);
    JsonNode body = JSON.readTree(reply.body());
    assertThat(body.get("code").asString()).isEqualTo("REQUEST_MALFORMED");
    assertThat(body.path("instance").asString(null)).isEqualTo(instance);
    assertThat(ContractSchemas.violations("WriteRefusal", reply.body())).isEmpty();
  }
}
