package org.speckitty.commandcli.domain.authority.batch;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.speckitty.commandcli.domain.authority.CorpusAuthorityCases;
import org.speckitty.commandcli.domain.authority.Judgement;
import org.speckitty.commandcli.domain.authority.Refusal;

/** Every domain case, including lifecycle gates and sequential batches, through the standard authority. */
class FullParityCorpusTest {
  private static final List<CorpusAuthorityCases.Case> ALL = CorpusAuthorityCases.load();
  private static final Set<String> LATER_LAYER_CASES = Set.of(
      "replay-then-new-batch", "replay-stale-from-is-still-replay", "fold-torn-tail",
      "replay-new-then-replayed-conflict", "replay-all-replayed-batch", "alias-noncanonical-row",
      "replay-partial-unit-conflict", "fold-invalid-json", "replay-duplicate-id-in-request",
      "fold-unknown-kind", "fold-crlf-replay");
  private static final Set<String> PASSED = ConcurrentHashMap.newKeySet();

  @Test
  void onlyWp08ServiceAndEdgeLayersRemainOutsideTheDomainRunner() {
    // WP08 owns replay classification, torn-tail handling and non-canonical request-shape checks.
    assertThat(CorpusAuthorityCases.laterLayerCaseIds()).containsExactlyInAnyOrderElementsOf(LATER_LAYER_CASES);
  }

  @TestFactory
  Stream<DynamicTest> fullCorpus() {
    return ALL.stream().map(testCase -> DynamicTest.dynamicTest(testCase.id(), () -> run(testCase)));
  }

  private static void run(CorpusAuthorityCases.Case testCase) {
    Judgement judgement = testCase.judge();
    Map<String, Object> expected = testCase.expected();
    if ("ack".equals(expected.get("result"))) {
      assertThat(judgement).as(testCase.id()).isInstanceOf(Judgement.Accepted.class);
    } else {
      assertThat(judgement).as(testCase.id()).isInstanceOf(Judgement.Refused.class);
      Refusal refusal = ((Judgement.Refused) judgement).refusal();
      assertThat(refusal.code().name()).as(testCase.id() + " code").isEqualTo(expected.get("code"));
      String expectedReason = (String) expected.get("reason");
      assertThat(refusal.reason() == null ? null : refusal.reason().urn()).as(testCase.id() + " reason").isEqualTo(expectedReason);
      if ("core".equals(testCase.oracle())) {
        assertThat(refusal.message()).as(testCase.id() + " message").isEqualTo(expected.get("message"));
      }
      Object expectedUnit = expected.get("unit");
      assertThat(refusal.unitIndex()).as(testCase.id() + " unit")
          .isEqualTo(expectedUnit instanceof Number number ? number.intValue() : null);
    }
    PASSED.add(testCase.id());
  }

  @AfterAll
  static void report() {
    Map<String, Long> areas = new LinkedHashMap<>();
    ALL.forEach(testCase -> areas.merge(testCase.area(), 1L, Long::sum));
    assertThat(PASSED).containsExactlyInAnyOrderElementsOf(ALL.stream().map(CorpusAuthorityCases.Case::id).toList());
    System.out.printf("WP07 full parity corpus: %d/%d domain cases passed; areas=%s; domainExcluded=0; laterLayer=%d%n",
        PASSED.size(), ALL.size(), areas, LATER_LAYER_CASES.size());
  }
}
