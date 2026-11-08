package org.speckitty.commandcli.domain.authority;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * Runs WP06 domain cases. dependency, claim, review_claim, start_rejected and batch areas are owned by WP07; multi-unit
 * requests in any other area are also WP07 batch sequencing, not hidden by another skip mechanism. This test is deliberately
 * untagged: native tests cannot read this repository corpus.
 */
class ParityCorpusTest {
  static final Set<String> WP07_AREAS = Set.of("dependency", "claim", "review_claim", "start_rejected", "batch");
  private static final Set<String> WP07_MULTI_UNIT_CASES = Set.of(
      "annotation-subtasks-complete-unlocks-review",
      "batch-clock-ahead-approved-departure",
      "stale-batch-after-earlier-unit");
  private static final List<CorpusCases.CorpusCase> ALL = CorpusCases.load();
  private static final List<CorpusCases.CorpusCase> SELECTED = ALL.stream().filter(ParityCorpusTest::owned).toList();
  private static final Set<String> PASSED = ConcurrentHashMap.newKeySet();

  private static boolean owned(CorpusCases.CorpusCase testCase) {
    return testCase.domain() && !WP07_AREAS.contains(testCase.area()) && !WP07_MULTI_UNIT_CASES.contains(testCase.id());
  }

  @Test
  void multiUnitExclusionsAreExplicit() {
    assertThat(WP07_MULTI_UNIT_CASES).hasSize(3).containsExactlyInAnyOrder(
        "annotation-subtasks-complete-unlocks-review",
        "batch-clock-ahead-approved-departure",
        "stale-batch-after-earlier-unit");
    assertThat(ALL.stream().filter(testCase -> WP07_MULTI_UNIT_CASES.contains(testCase.id())))
        .allSatisfy(testCase -> assertThat(testCase.units()).hasSizeGreaterThan(1));
  }

  @TestFactory
  Stream<DynamicTest> wp06Corpus() {
    return SELECTED.stream().map(testCase -> DynamicTest.dynamicTest(testCase.id(), () -> run(testCase)));
  }

  private static void run(CorpusCases.CorpusCase testCase) {
    Optional<Refusal> actual = judge(testCase);
    Map<String, Object> expected = testCase.expected();
    if ("ack".equals(expected.get("result"))) {
      assertThat(actual).as(testCase.id()).isEmpty();
    } else {
      assertThat(actual).as(testCase.id()).isPresent();
      Refusal refusal = actual.orElseThrow();
      assertThat(refusal.code().name()).as(testCase.id() + " code").isEqualTo(expected.get("code"));
      String expectedReason = (String) expected.get("reason");
      assertThat(refusal.reason() == null ? null : refusal.reason().urn()).as(testCase.id() + " reason").isEqualTo(expectedReason);
      if ("core".equals(testCase.oracle())) {
        assertThat(refusal.message()).as(testCase.id() + " message").isEqualTo(expected.get("message"));
      }
      assertThat(refusal.unitIndex()).as(testCase.id() + " unit").isEqualTo(expected.get("unit"));
    }
    PASSED.add(testCase.id());
  }

  private static Optional<Refusal> judge(CorpusCases.CorpusCase testCase) {
    UnitInput unit = testCase.unit();
    UnitJudge judge = new UnitJudge(DependencyGate.NO_VERDICT, ClaimGate.NONE);
    return judge.judge(unit, testCase.fold(), testCase.facts());
  }

  @AfterAll
  static void floorsAndReport() {
    long legalEdges = SELECTED.stream()
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
    assertThat(PASSED).containsExactlyInAnyOrderElementsOf(SELECTED.stream().map(CorpusCases.CorpusCase::id).toList());
    long excluded = ALL.stream().filter(testCase -> testCase.domain() && !owned(testCase)).count();
    Map<String, Long> areas = new LinkedHashMap<>();
    SELECTED.forEach(testCase -> areas.merge(testCase.area(), 1L, Long::sum));
    System.out.printf("WP06 parity corpus: %d/%d passed; areas=%s; excluded as WP07-owned=%d%n",
        PASSED.size(), SELECTED.size(), areas, excluded);
  }

  private static long countReason(String suffix) {
    return SELECTED.stream().filter(testCase -> ("urn:spec-kitty:write-refusal:" + suffix).equals(testCase.expected().get("reason"))).count();
  }

  private static long countArea(String area) {
    return SELECTED.stream().filter(testCase -> testCase.area().equals(area)).count();
  }
}
