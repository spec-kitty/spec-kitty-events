package org.speckitty.commandcli.adapter.in.http.operations;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import org.junit.jupiter.api.Test;
import org.speckitty.commandcli.adapter.in.http.edge.RawClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** US1-US5 acceptance scenarios over the real HTTP edge and a real git repository. */
class WriteIntegrationTest {
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Test
  void us1ScenarioOneALegalClaimIsDurablyWrittenByteExact() throws Exception {
    try (WriteRig rig = WriteRig.start()) {
      String eventId = "01J6XW9KQT7M0YB3N4R5CQZ2EY";
      RawClient.Reply reply = rig.post("unit", WriteRig.unit(WriteRig.claimRow(eventId)));

      assertThat(reply.status()).as(reply.text()).isEqualTo(201);
      JsonNode ack = JSON.readTree(reply.body());
      assertThat(ack.get("appendedLines").asInt()).isEqualTo(1);
      assertThat(ack.get("eventIds").get(0).asString()).isEqualTo(eventId);
      assertThat(ack.get("fsynced").asBoolean()).isTrue();
      assertThat(ack.get("replayed").asBoolean()).isFalse();
      assertThat(ack.has("statusCommit") && ack.get("statusCommit").isNull()).isTrue();
      assertThat(Files.readString(rig.log())).isEqualTo(WriteRig.priorPlannedLine() + WriteRig.expectedClaimLine(eventId));
    }
  }
}
