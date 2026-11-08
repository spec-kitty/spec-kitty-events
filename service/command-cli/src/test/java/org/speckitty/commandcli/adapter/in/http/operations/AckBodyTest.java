package org.speckitty.commandcli.adapter.in.http.operations;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.speckitty.commandcli.application.write.WriteOutcome;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Contract-valid, reflection-free durability acknowledgement serialization. */
class AckBodyTest {
  private static final String ID = "01J6XW9KQT7M0YB3N4R5CQZ2EY";
  private final JsonMapper mapper = JsonMapper.builder().build();

  @Test
  void durabilityAckValidatesAndKeepsStatusCommitNull() throws Exception {
    WriteOutcome.Acked outcome = new WriteOutcome.Acked(List.of(new WriteOutcome.UnitAck(List.of(ID), 1, false)), false, 1);
    byte[] bytes = AckBody.bytes(outcome, mapper);
    String json = new String(bytes, StandardCharsets.UTF_8);
    assertThat(ContractSchemas.violations("DurabilityAck", json)).isEmpty();
    JsonNode body = mapper.readTree(json);
    assertThat(body.required("eventIds").get(0).asString()).isEqualTo(ID);
    assertThat(body.required("appendedLines").asInt()).isOne();
    assertThat(body.required("fsynced").asBoolean()).isTrue();
    assertThat(body.required("replayed").asBoolean()).isFalse();
    assertThat(body.has("statusCommit") && body.get("statusCommit").isNull()).isTrue();
    assertThat(json).endsWith("\n");
  }

  @Test
  void batchAckValidatesForNewAndFullyReplayedUnits() throws Exception {
    for (boolean replayed : List.of(false, true)) {
      WriteOutcome.Acked outcome = new WriteOutcome.Acked(
          List.of(new WriteOutcome.UnitAck(List.of(ID), replayed ? 0 : 1, replayed)), true, replayed ? 0 : 1);
      String json = new String(AckBody.bytes(outcome, mapper), StandardCharsets.UTF_8);
      assertThat(ContractSchemas.violations("BatchDurabilityAck", json)).isEmpty();
      JsonNode body = mapper.readTree(json);
      assertThat(body.required("fsyncs").asInt()).isEqualTo(replayed ? 0 : 1);
      assertThat(body.required("units").get(0).required("replayed").asBoolean()).isEqualTo(replayed);
      assertThat(body.required("units").get(0).required("statusCommit").isNull()).isTrue();
    }
  }
}
