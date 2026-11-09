package org.speckitty.commandcli.domain.authority;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/** Runs every domain corpus case through the complete authority; no WP07 exclusions remain. */
class ParityCorpusTest {
  private static final List<CorpusAuthorityCases.Case> ALL = CorpusAuthorityCases.load();
  private static final Set<String> PASSED = ConcurrentHashMap.newKeySet();

  @TestFactory
  Stream<DynamicTest> corpus() {
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
  static void floorsAndReport() {
    long legalEdges = ALL.stream()
        .filter(testCase -> testCase.area().equals("lane_machine") && testCase.expected().get("result").equals("ack"))
        .count();
    assertThat(legalEdges).isGreaterThanOrEqualTo(29);
    Set<String> required = Set.of(
        "unknown_lane", "illegal_transition", "force_requires_actor_and_reason", "actor_required",
        "implementation_evidence_required", "reason_required", "review_ref_required",
        "reviewer_approval_required", "review_evidence_invalid", "force_note_required", "done_evidence_incomplete");
    Map<String, Long> reasons = new LinkedHashMap<>();
    required.forEach(reason -> reasons.put(reason, countReason(reason)));
    assertThat(reasons).allSatisfy((reason, count) -> assertThat(count).as(reason).isPositive());
    assertThat(countArea("stale")).isPositive();
    assertThat(PASSED).containsExactlyInAnyOrderElementsOf(ALL.stream().map(CorpusAuthorityCases.Case::id).toList());
    Map<String, Long> areas = new LinkedHashMap<>();
    ALL.forEach(testCase -> areas.merge(testCase.area(), 1L, Long::sum));
    System.out.printf("Full parity corpus: %d/%d passed; areas=%s; excluded=0%n", PASSED.size(), ALL.size(), areas);
  }

  private static long countReason(String suffix) {
    return ALL.stream().filter(testCase -> ("urn:spec-kitty:write-refusal:" + suffix).equals(testCase.expected().get("reason"))).count();
  }

  private static long countArea(String area) {
    return ALL.stream().filter(testCase -> testCase.area().equals(area)).count();
  }
}
