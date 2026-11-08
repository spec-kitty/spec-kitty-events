package org.speckitty.commandcli.application.write;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.speckitty.commandcli.adapter.out.statuslog.EventLogRowCodec;
import org.speckitty.commandcli.application.write.port.RowCodec;

/** FR-008 first-occurrence and whole-unit replay rules. */
class ReplayClassifierTest {
  private static final String ID = "01J6XW9KQT7M0YB3N4R5CQZ2EY";
  private final EventLogRowCodec codec = new EventLogRowCodec();

  @Test
  void theFirstDuplicateIdOccurrenceWins() {
    Map<String, Object> first = WriteServiceTest.row(ID, "WP01", "planned", "claimed", "first");
    Map<String, Object> second = WriteServiceTest.row(ID, "WP01", "planned", "claimed", "second");
    RowCodec.DecodedLog log = log(first, second);

    assertThat(ReplayClassifier.classify(request(first), log, codec)).isInstanceOf(ReplayClassifier.Classified.class);
    assertThat(ReplayClassifier.classify(request(second), log, codec)).isInstanceOf(ReplayClassifier.Conflict.class);
  }

  @Test
  void aPartialUnitAndAReplayAfterNewAreConflicts() {
    Map<String, Object> transition = WriteServiceTest.row(ID, "WP01", "planned", "claimed", "actor");
    Map<String, Object> annotation = WriteServiceTest.row("01J6XW9KQT7M0YB3N4R5CQZ2EZ", "WP01", "claimed", "in_progress", "actor");
    var partialUnit = new WriteRequest.UnitRequest("WP01", WriteRequest.Operation.TRANSITION,
        List.of(row(transition), row(annotation)));
    WriteRequest partial = new WriteRequest("mission", WriteRequest.Kind.UNIT, List.of(partialUnit), context());
    assertThat(ReplayClassifier.classify(partial, log(transition), codec)).isInstanceOf(ReplayClassifier.Conflict.class);

    Map<String, Object> fresh = WriteServiceTest.row("01J6XW9KQT7M0YB3N4R5CQZ2F0", "WP01", "planned", "claimed", "actor");
    WriteRequest reordered = new WriteRequest("mission", WriteRequest.Kind.BATCH,
        List.of(unit(fresh), unit(transition)), context());
    ReplayClassifier.Result conflict = ReplayClassifier.classify(reordered, log(transition), codec);
    assertThat(((ReplayClassifier.Conflict) conflict).unitIndex()).isEqualTo(1);
  }

  @Test
  void aReplayThenNewBatchKeepsOriginalIndexesAndEncodedRows() {
    Map<String, Object> replay = WriteServiceTest.row(ID, "WP01", "planned", "claimed", "actor");
    Map<String, Object> fresh = WriteServiceTest.row("01J6XW9KQT7M0YB3N4R5CQZ2EZ", "WP01", "claimed", "in_progress", "actor");
    WriteRequest request = new WriteRequest("mission", WriteRequest.Kind.BATCH, List.of(unit(replay), unit(fresh)), context());
    var classified = (ReplayClassifier.Classified) ReplayClassifier.classify(request, log(replay), codec);
    assertThat(classified.units()).extracting(ReplayClassifier.Unit::replayed).containsExactly(true, false);
    assertThat(classified.units().getFirst().eventIds()).containsExactly(ID);
    assertThat(new String(classified.units().get(1).encodedRows().getFirst(), StandardCharsets.UTF_8)).endsWith("\n");
  }

  private static WriteRequest request(Map<String, Object> row) {
    return new WriteRequest("mission", WriteRequest.Kind.UNIT, List.of(unit(row)), context());
  }

  private static WriteRequest.UnitRequest unit(Map<String, Object> row) {
    return new WriteRequest.UnitRequest("WP01", WriteRequest.Operation.TRANSITION, List.of(row(row)));
  }

  private static WriteRequest.Row row(Map<String, Object> row) {
    return new WriteRequest.Row((String) row.get("event_id"), row);
  }

  private static WriteRequest.Context context() {
    return new WriteRequest.Context("actor", ".", "kitty-specs/m", "checkout_append", null);
  }

  private RowCodec.DecodedLog log(Map<String, Object>... rows) {
    byte[] bytes = new byte[0];
    for (Map<String, Object> row : rows) {
      byte[] encoded = codec.encodeLine(row);
      byte[] joined = java.util.Arrays.copyOf(bytes, bytes.length + encoded.length);
      System.arraycopy(encoded, 0, joined, bytes.length, encoded.length);
      bytes = joined;
    }
    return codec.decode(bytes);
  }
}
