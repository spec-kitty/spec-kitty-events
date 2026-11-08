package org.speckitty.commandcli.domain.authority.batch;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.speckitty.commandcli.domain.authority.CorpusAuthorityCases;

/** WP08's later-layer corpus ownership; ParityCorpusTest is the sole domain-case runner. */
class FullParityCorpusTest {
  private static final Map<String, String> LATER_LAYER_REASONS = Map.ofEntries(
      Map.entry("alias-noncanonical-row", "request alias canonicalization belongs to edge validation"),
      Map.entry("catalog-broken-sibling-meta", "one malformed sibling does not hide a healthy Mission"),
      Map.entry("write-location-coordination-routed-mission", "R16 primary appends are refused before taking a lock"),
      Map.entry("fold-crlf-replay", "CRLF log decoding and replay belong to service input"),
      Map.entry("fold-invalid-json", "malformed JSON is rejected while decoding the log"),
      Map.entry("fold-torn-tail", "torn-tail recovery belongs to status-log service input"),
      Map.entry("fold-unknown-kind", "event-kind validation belongs to log decoding"),
      Map.entry("own-meta-bom", "strict UTF-8 meta.json decoding belongs to Mission discovery"),
      Map.entry("replay-all-replayed-batch", "all-replayed batches need service acknowledgement and fsync behavior"),
      Map.entry("replay-duplicate-id-in-request", "duplicate ids within a request are service replay conflicts"),
      Map.entry("replay-new-then-replayed-conflict", "request replay conflicts precede new-unit authority"),
      Map.entry("replay-partial-unit-conflict", "partial-unit replay comparison belongs to replay classification"),
      Map.entry("replay-stale-from-is-still-replay", "an already-logged row is replay despite its stale from_lane"),
      Map.entry("replay-then-new-batch", "replay classification precedes sequential batch authority"));

  @Test
  void everyLaterLayerCaseHasAProtocolReasonForWp08() {
    assertThat(LATER_LAYER_REASONS).hasSize(14).allSatisfy((id, reason) -> {
      assertThat(id).isNotBlank();
      assertThat(reason).isNotBlank();
    });
    assertThat(CorpusAuthorityCases.laterLayerCaseIds())
        .containsExactlyInAnyOrderElementsOf(LATER_LAYER_REASONS.keySet());
  }
}
