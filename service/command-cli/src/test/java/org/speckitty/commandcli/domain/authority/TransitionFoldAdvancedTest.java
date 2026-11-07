package org.speckitty.commandcli.domain.authority;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.speckitty.missionstatus.domain.model.Lane;

/** Adversarial histories pin the core reducer arms rather than just the happy-path lane fold. */
class TransitionFoldAdvancedTest {
  @Test
  void realRollbackBeatsForwardAtSameTimestampAndStaleForwardsNeverOverrideIt() {
    List<Map<String, Object>> rows = new ArrayList<>();
    rows.add(event(1, "01", "actor", "in_review", "approved"));
    rows.add(event(2, "01", "actor", "in_review", "planned"));
    rows.add(event(3, "02", "actor", "in_review", "approved"));
    assertThat(WriteFold.fold(rows).of("WP01").lane()).isEqualTo(Lane.PLANNED);

    Map<String, Object> rework = event(3, "02", "actor", "planned", "claimed");
    rows.set(2, rework);
    rows.add(event(4, "03", "actor", "in_review", "approved"));
    assertThat(WriteFold.fold(rows).of("WP01").lane()).isEqualTo(Lane.CLAIMED);
  }

  @Test
  void rollbackSeedWinsOnlyBareSiblingThenYieldsToRealLifecycle() {
    Map<String, Object> seed = event(2, "01", "migration:seed", "in_review", "planned");
    Map<String, Object> approval = event(1, "01", "actor", "in_review", "approved");
    Map<String, Object> rework = event(3, "02", "actor", "planned", "claimed");
    assertThat(WriteFold.fold(List.of(seed, approval, rework)).of("WP01").lane()).isEqualTo(Lane.CLAIMED);

    Map<String, Object> seedFirst = event(1, "01", "migration:seed", "in_review", "planned");
    Map<String, Object> bareLater = event(2, "01", "actor", "in_review", "approved");
    assertThat(WriteFold.fold(List.of(seedFirst, bareLater)).of("WP01").lane()).isEqualTo(Lane.PLANNED);
  }

  @Test
  void arrivingRollbackSeedCannotReopenSettledLaneButFillsVerdict() {
    Map<String, Object> done = event(1, "01", "actor", "approved", "done");
    Map<String, Object> seed = event(2, "02", "migration:seed", "in_review", "planned");
    seed.put("review_result", AuthorityFixtures.reviewResult("changes_requested", "historic"));
    WpWriteState state = WriteFold.fold(List.of(done, seed)).of("WP01");
    assertThat(state.lane()).isEqualTo(Lane.DONE);
    assertThat(state.slot("review_result")).isNotNull();
  }

  @Test
  void forwardSeedSetterYieldsAndLosingSeedsFillClaimsWithoutTouchingLane() {
    Map<String, Object> seed = event(1, "01", "migration:seed", "planned", "claimed");
    Map<String, Object> real = event(2, "02", "actor", "planned", "blocked");
    assertThat(WriteFold.fold(List.of(seed, real)).of("WP01").lane()).isEqualTo(Lane.BLOCKED);

    Map<String, Object> current = event(1, "01", "actor", "genesis", "in_progress");
    Map<String, Object> claim = event(2, "02", "migration:seed", "planned", "claimed");
    claim.put("policy_metadata", Map.of("agent", "seed-agent", "shell_pid_created_at", "seed-at"));
    WpWriteState restored = WriteFold.fold(List.of(current, claim)).of("WP01");
    assertThat(restored.lane()).isEqualTo(Lane.IN_PROGRESS);
    assertThat(restored.agent()).isEqualTo("seed-agent");
    assertThat(restored.slot("shell_pid_created_at")).isEqualTo("seed-at");

    Map<String, Object> realClaim = event(3, "03", "actor", "planned", "claimed");
    realClaim.put("force", true);
    realClaim.put("policy_metadata", Map.of("agent", "real-agent", "shell_pid", 10));
    WpWriteState realWins = WriteFold.fold(List.of(current, claim, realClaim)).of("WP01");
    assertThat(realWins.lane()).isEqualTo(Lane.CLAIMED);
    assertThat(realWins.agent()).isEqualTo("real-agent");
    assertThat(realWins.slot("shell_pid")).isEqualTo(10);
    assertThat(realWins.slot("shell_pid_created_at")).isNull();
  }

  @Test
  void sameTimestampRollbackDropDoesNotFoldLosingSeedPayload() {
    Map<String, Object> rollback = event(1, "01", "actor", "in_review", "planned");
    Map<String, Object> claim = event(2, "01", "migration:seed", "planned", "claimed");
    claim.put("policy_metadata", Map.of("agent", "not-restored"));
    WpWriteState state = WriteFold.fold(List.of(rollback, claim)).of("WP01");
    assertThat(state.lane()).isEqualTo(Lane.PLANNED);
    assertThat(state.agent()).isNull();
  }

  @Test
  void legacyForReviewRollbackUsesReviewRefAndClearsForcedInReviewVerdict() {
    Map<String, Object> forward = event(1, "01", "actor", "for_review", "approved");
    Map<String, Object> rollback = event(2, "01", "actor", "for_review", "planned");
    rollback.put("review_ref", "feedback");
    assertThat(WriteFold.fold(List.of(forward, rollback)).of("WP01").lane()).isEqualTo(Lane.PLANNED);

    Map<String, Object> exit = event(1, "01", "actor", "in_review", "blocked");
    exit.put("review_result", AuthorityFixtures.reviewResult("changes_requested", "ref"));
    Map<String, Object> later = event(2, "02", "actor", "in_review", "canceled");
    later.put("force", true);
    WpWriteState state = WriteFold.fold(List.of(exit, later)).of("WP01");
    assertThat(state.slot("review_result")).isNull();
    assertThat(state.slot("force_count")).isEqualTo(1);
  }

  @Test
  void sameIdSeedsUseCanonicalMinimumIndependentOfFileOrder() {
    Map<String, Object> z = event(1, "01", "migration:seed", "planned", "claimed");
    z.put("policy_metadata", Map.of("agent", "z-agent"));
    Map<String, Object> a = event(1, "01", "migration:seed", "planned", "claimed");
    a.put("policy_metadata", Map.of("agent", "a-agent"));
    assertThat(WriteFold.fold(List.of(z, a)).of("WP01").agent()).isEqualTo("a-agent");
    assertThat(WriteFold.fold(List.of(a, z)).of("WP01").agent()).isEqualTo("a-agent");
  }

  @Test
  void approvedDepartureLiftHandlesEqualTimestampsAndCarriesReworkFloor() {
    Map<String, Object> base = event(0, "00", "actor", "genesis", "in_review");
    Map<String, Object> departure = event(1, "01", "actor", "approved", "in_progress");
    Map<String, Object> approval = event(2, "01", "actor", "in_review", "approved");
    assertThat(WriteFold.fold(List.of(base, departure, approval)).of("WP01").lane()).isEqualTo(Lane.IN_PROGRESS);

    Map<String, Object> lateDeparture = event(3, "01", "actor", "approved", "in_progress");
    Map<String, Object> rework = event(4, "02", "actor", "in_progress", "for_review");
    Map<String, Object> clockAhead = event(5, "03", "actor", "in_review", "approved");
    assertThat(WriteFold.fold(List.of(base, lateDeparture, rework, clockAhead)).of("WP01").lane())
        .isEqualTo(Lane.FOR_REVIEW);
  }

  private static Map<String, Object> event(int id, String time, String actor, String from, String to) {
    Map<String, Object> row = AuthorityFixtures.row(from, to);
    row.put("event_id", String.format("%026d", id));
    row.put("at", "2026-01-" + time + "T00:00:00+00:00");
    row.put("actor", actor);
    return row;
  }
}
