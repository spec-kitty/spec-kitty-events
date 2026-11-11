package org.speckitty.commandcli.adapter.in.http.operations;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.speckitty.commandcli.adapter.in.http.edge.RawClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * A prior transition whose {@code event_id} is a non-finite number (#231 item 4.17). The log decoder reads {@code NaN}, {@code Infinity}
 * and {@code 1e400} as doubles, which have no exact decimal key, so the fold cannot read the log: the write is a coded 500 and nothing is
 * written. A finite numeric id on the same row is read and acknowledged.
 */
class NonFiniteLogIdTest {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String NEW_ID = "01J6XW9KQT7M0YB3N4R5CQZ2EY";

  private static String planned(String eventIdJson) {
    return WriteRig.priorPlannedLine().replace("\"event_id\": \"" + WriteRig.PRIOR_ID + "\"", "\"event_id\": " + eventIdJson);
  }

  @Test
  void aNonFiniteLogEventIdIsACodedRefusalAndTheLogIsUnchanged() throws Exception {
    for (String id : List.of("NaN", "Infinity", "1e400")) {
      try (WriteRig rig = WriteRig.start()) {
        String seeded = planned(id);
        rig.seedLog(seeded);
        RawClient.Reply reply = rig.post("unit", WriteRig.unit(WriteRig.claimRow(NEW_ID)));
        assertThat(reply.status()).as(id + ": " + reply.text()).isEqualTo(500);
        assertThat(JSON.readTree(reply.body()).get("code").asString()).as(id)
            .isEqualTo("STATUS_EVENT_PERSISTENCE_VERIFICATION_FAILED");
        assertThat(Files.readString(rig.log())).as(id).isEqualTo(seeded);
      }
    }
  }

  @Test
  void aFiniteNumericLogEventIdOnTheSameRowIsAcknowledged() throws Exception {
    try (WriteRig rig = WriteRig.start()) {
      String seeded = planned("5");
      rig.seedLog(seeded);
      RawClient.Reply reply = rig.post("unit", WriteRig.unit(WriteRig.claimRow(NEW_ID)));
      assertThat(reply.status()).as(reply.text()).isEqualTo(201);
      assertThat(Files.readString(rig.log())).isEqualTo(seeded + WriteRig.expectedClaimLine(NEW_ID));
    }
  }
}
