package org.speckitty.commandcli.domain.authority;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.speckitty.missionstatus.domain.model.Lane;

class WriteFoldTest {
  @Test
  void inputRulesSkipBlanksAndNonLaneRowsAndFailClosed() {
    FoldView folded = WriteFold.fold(List.of(new LogLine.Blank(), new LogLine.Value(Map.of("event_type", "DecisionPointRecorded"))));
    assertThat(folded.all()).isEmpty();
    assertThatThrownBy(() -> WriteFold.fold(List.of(new LogLine.Unparseable()))).isInstanceOf(UnreducibleLog.class);
    assertThatThrownBy(() -> WriteFold.fold(List.of(Map.of("kind", "future"))))
        .isInstanceOf(UnreducibleLog.class).hasMessageContaining("future");
    assertThatThrownBy(() -> WriteFold.fold(List.of(Map.of("event_id", "x")))).isInstanceOf(UnreducibleLog.class);
    Map<String, Object> missingId = AuthorityFixtures.row("genesis", "planned");
    missingId.remove("event_id");
    assertThatThrownBy(() -> WriteFold.fold(List.of(missingId))).isInstanceOf(UnreducibleLog.class);
  }

  @Test
  void transitionsMissingRequiredFieldsAreUnreducible() {
    for (String field : List.of("wp_id", "at", "actor", "force", "execution_mode")) {
      Map<String, Object> malformed = AuthorityFixtures.row("genesis", "planned");
      malformed.remove(field);
      assertThatThrownBy(() -> WriteFold.fold(List.of(malformed)))
          .as(field)
          .isInstanceOf(UnreducibleLog.class)
          .hasMessageContaining(field);
    }
  }

  @Test
  void structuredActorsRequireAnExactCoreKeySet() {
    Map<String, Object> subset = AuthorityFixtures.row("genesis", "planned");
    subset.put("actor", Map.of("tool", "codex"));
    assertThatThrownBy(() -> WriteFold.fold(List.of(subset))).isInstanceOf(UnreducibleLog.class);

    Map<String, Object> extra = AuthorityFixtures.row("genesis", "planned");
    Map<String, Object> actor = new LinkedHashMap<>();
    actor.put("role", "implementer");
    actor.put("profile", null);
    actor.put("tool", "codex");
    actor.put("model", null);
    actor.put("unexpected", null);
    extra.put("actor", actor);
    assertThatThrownBy(() -> WriteFold.fold(List.of(extra))).isInstanceOf(UnreducibleLog.class);
  }

  @Test
  void structuredActorValuesAndProfileSourceFollowCoreValidation() {
    Map<String, Object> wrongType = AuthorityFixtures.row("genesis", "planned");
    wrongType.put("actor", Map.of("role", "implementer", "profile", "p", "tool", 1, "model", "m"));
    assertThatThrownBy(() -> WriteFold.fold(List.of(wrongType))).isInstanceOf(UnreducibleLog.class);

    Map<String, Object> inconsistent = AuthorityFixtures.row("genesis", "planned");
    Map<String, Object> actor = new LinkedHashMap<>();
    actor.put("role", "implementer");
    actor.put("profile", null);
    actor.put("profile_source", "invocation");
    actor.put("tool", "codex");
    actor.put("model", null);
    inconsistent.put("actor", actor);
    assertThatThrownBy(() -> WriteFold.fold(List.of(inconsistent))).isInstanceOf(UnreducibleLog.class);

    actor.put("profile", "java-jenny");
    assertThat(WriteFold.fold(List.of(inconsistent)).of("WP01").tool()).isEqualTo("codex");
  }

  @Test
  void foldedReviewPermitsNullValuesButRemainsUnmodifiable() {
    Map<String, Object> review = new LinkedHashMap<>();
    review.put("at", null);
    review.put("actor", "reviewer-a");
    review.put("wp_id", "WP01");
    review.put("reason", "complete");
    WpWriteState state = WriteFold.fold(List.of(annotation("WP01", Map.of("review", review)))).of("WP01");
    assertThat(state.review()).containsKey("at").containsEntry("actor", "reviewer-a");
    assertThat(state.review().get("at")).isNull();
    assertThatThrownBy(() -> state.review().put("actor", "other"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void skipsRetrospectiveEventNameRows() {
    assertSkipsRetrospectiveRow(Map.of("event_name", "retrospective.captured"));
  }

  @Test
  void skipsRetrospectiveCapturedTypeRows() {
    assertSkipsRetrospectiveRow(Map.of("type", "RetrospectiveCaptured"));
  }

  @Test
  void skipsRetrospectiveCaptureFailedTypeRows() {
    assertSkipsRetrospectiveRow(Map.of("type", "RetrospectiveCaptureFailed"));
  }

  @Test
  void skipsRetrospectiveSkippedTypeRows() {
    assertSkipsRetrospectiveRow(Map.of("type", "RetrospectiveSkipped"));
  }

  private static void assertSkipsRetrospectiveRow(Map<String, Object> retrospective) {
    FoldView folded = WriteFold.fold(List.of(retrospective, AuthorityFixtures.row("genesis", "planned")));
    assertThat(folded.of("WP01").lane()).isEqualTo(Lane.PLANNED);
  }

  @Test
  void emptyUnknownAndAnnotationOnlyStatesAreUninitialized() {
    FoldView empty = WriteFold.fold(List.<Map<String, Object>>of());
    assertThat(empty.of("WP99").lane()).isEqualTo(Lane.UNINITIALIZED);
    assertThat(empty.of("WP99").hasTransitions()).isFalse();
    assertThat(empty.known("WP99")).isFalse();
    FoldView annotation = WriteFold.fold(List.of(annotation("WP99", Map.of("role", "implementer"))));
    WpWriteState state = annotation.of("WP99");
    assertThat(state.lane()).isEqualTo(Lane.UNINITIALIZED);
    assertThat(state.role()).isEqualTo("implementer");
    assertThat(state.actor()).isEqualTo("implementer-a");
    assertThat(state.tool()).isEqualTo("implementer-a");
    assertThat(annotation.known("WP99")).isTrue();
  }

  @Test
  void firstDuplicateWinsAndFirstOccurrenceOrderDoesNotControlSort() {
    Map<String, Object> blocked = AuthorityFixtures.row("genesis", "blocked");
    Map<String, Object> planned = AuthorityFixtures.row("genesis", "planned");
    FoldView folded = WriteFold.fold(List.of(blocked, planned));
    assertThat(folded.of("WP01").lane()).isEqualTo(Lane.BLOCKED);
    assertThat(folded.of("WP01").hasTransitions()).isTrue();

    Map<String, Object> first = AuthorityFixtures.row("genesis", "planned");
    first.put("event_id", "00000000000000000000000002");
    first.put("at", "2026-01-02T00:00:00+00:00");
    Map<String, Object> second = AuthorityFixtures.row("planned", "canceled");
    second.put("event_id", "00000000000000000000000001");
    second.put("at", "2026-01-01T00:00:00+00:00");
    second.put("reason", "operator close");
    FoldView reordered = WriteFold.fold(List.of(first, second));
    assertThat(reordered.of("WP01").lane()).isEqualTo(Lane.CANCELED);
  }

  @Test
  void foldDoesNotRetainMutableStructuredActorFromInput() {
    Map<String, Object> actor = new LinkedHashMap<>();
    actor.put("tool", "codex");
    actor.put("role", "implementer");
    actor.put("profile", null);
    actor.put("model", null);
    Map<String, Object> row = AuthorityFixtures.row("genesis", "planned");
    row.put("actor", actor);
    WpWriteState state = WriteFold.fold(List.of(row)).of("WP01");
    actor.put("tool", "changed");
    assertThat(state.tool()).isEqualTo("codex");
    Map<String, Object> foldedActor = (Map<String, Object>) state.actor();
    assertThat(foldedActor).containsEntry("tool", "codex");
    assertThatThrownBy(() -> foldedActor.put("tool", "changed"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void foldsActorsClaimSidecarAndRuntimeSlotsAcrossTransitions() {
    Map<String, Object> claim = AuthorityFixtures.row("planned", "claimed");
    claim.put("event_id", "00000000000000000000000002");
    claim.put("actor", Map.of("role", "implementer", "profile", "java-jenny", "tool", "codex", "model", "k3"));
    claim.put("policy_metadata", new LinkedHashMap<>(Map.of("agent", "codex-agent", "shell_pid", 42)));
    Map<String, Object> progress = AuthorityFixtures.row("claimed", "in_progress");
    progress.put("event_id", "00000000000000000000000003");
    FoldView folded = WriteFold.fold(List.of(claim, progress, AuthorityFixtures.row("genesis", "planned")));
    WpWriteState state = folded.of("WP01");
    assertThat(state.lane()).isEqualTo(Lane.IN_PROGRESS);
    assertThat(state.actor()).isEqualTo("implementer-a");
    assertThat(state.agent()).isEqualTo("codex-agent");
    assertThat(state.implementerOfRecord()).isEqualTo("codex-agent");
    assertThat(state.slot("shell_pid")).isEqualTo(42);
  }

  @Test
  void cancellationProvenanceUsesExplicitSyntheticAndOperatorRules() {
    for (String[] sample : new String[][] {{null, null, "synthetic"}, {null, " ", "synthetic"},
        {null, "Force move to planned", "synthetic"}, {null, "move-task: planned", "synthetic"},
        {null, "human cancellation", "operator"}, {"explicit", null, "explicit"}}) {
      Map<String, Object> cancel = AuthorityFixtures.row("planned", "canceled");
      cancel.put("event_id", "00000000000000000000000002");
      cancel.put("reason", sample[1]);
      cancel.put("reason_source", sample[0]);
      FoldView folded = WriteFold.fold(List.of(AuthorityFixtures.row("genesis", "planned"), cancel));
      assertThat(folded.of("WP01").reasonSource()).isEqualTo(sample[2]);
    }
  }

  @Test
  void annotationDeltasPortAllSlotMergeRules() {
    List<Map<String, Object>> rows = new ArrayList<>();
    rows.add(AuthorityFixtures.row("genesis", "planned"));
    rows.add(annotation("WP01", delta(Map.of(
        "agent", "codex", "role", "implementer", "assignee", "a", "agent_profile", "p",
        "agent_profile_version", "v", "model", "m", "provider", "x", "shell_pid_created_at", "at"))));
    Map<String, Object> second = delta(Map.of("agent", "", "note", "first"));
    second.put("subtasks", Map.of("T001", "not_applicable"));
    second.put("tracker_refs", List.of("b", "a", "b"));
    second.put("review", Map.of("at", "at", "actor", "reviewer", "wp_id", "WP01", "reason", "complete"));
    rows.add(annotation("WP01", second));
    rows.add(annotation("WP01", delta(Map.of("note", "second", "tracker_refs_replace", List.of("c", "a", "c")))));
    FoldView folded = WriteFold.fold(rows);
    WpWriteState state = folded.of("WP01");
    assertThat(state.agent()).isEqualTo("codex");
    assertThat(state.role()).isEqualTo("implementer");
    assertThat(state.subtaskState()).containsEntry("T001", "skipped");
    assertThat(state.slot("notes")).isEqualTo(List.of("first", "second"));
    assertThat(state.slot("tracker_refs")).isEqualTo(List.of("c", "a"));
    assertThat(state.review()).containsEntry("actor", "reviewer");
    assertThat(state.slot("agent_profile")).isEqualTo("p");
    assertThat(state.slot("agent_profile_version")).isEqualTo("v");
    assertThat(state.slot("model")).isEqualTo("m");
    assertThat(state.slot("provider")).isEqualTo("x");
    assertThat(state.slot("assignee")).isEqualTo("a");
    assertThat(state.slot("shell_pid_created_at")).isEqualTo("at");

    FoldView released = WriteFold.fold(List.of(annotation("WP01", delta(Map.of("agent", "codex"))),
        annotation("WP01", delta(Map.of("release_runtime_claim", true, "agent", "next"))),
        annotation("WP01", delta(Map.of("release_runtime_claim", true))),
        annotation("WP01", delta(Map.of("review", Map.of("at", "", "actor", "", "wp_id", "", "reason", ""))))));
    assertThat(released.of("WP01").agent()).isNull();
    assertThat(released.of("WP01").review()).isNull();
  }

  @Test
  void approvedDepartureAndFollowingReworkSortBehindClockAheadApproval() {
    List<Map<String, Object>> rows = new ArrayList<>();
    rows.add(AuthorityFixtures.row("genesis", "in_review"));
    Map<String, Object> approval = AuthorityFixtures.row("in_review", "approved");
    approval.put("event_id", "00000000000000000000000006");
    approval.put("at", "2026-01-03T00:00:00+00:00");
    approval.put("review_result", AuthorityFixtures.reviewResult("approved", "ref"));
    Map<String, Object> departure = AuthorityFixtures.row("approved", "in_progress");
    departure.put("event_id", "00000000000000000000000007");
    departure.put("at", "2026-01-01T00:00:00+00:00");
    Map<String, Object> review = AuthorityFixtures.row("in_progress", "for_review");
    review.put("event_id", "00000000000000000000000008");
    review.put("at", "2026-01-02T00:00:00+00:00");
    rows.addAll(List.of(departure, review, approval));
    FoldView folded = WriteFold.fold(rows);
    assertThat(folded.of("WP01").lane()).isEqualTo(Lane.FOR_REVIEW);
  }

  @Test
  void rollbackAndContinuityArbitrateConcurrentHistories() {
    List<Map<String, Object>> rows = new ArrayList<>();
    rows.add(AuthorityFixtures.row("in_review", "planned"));
    rows.add(AuthorityFixtures.row("in_review", "approved"));
    FoldView folded = WriteFold.fold(rows);
    assertThat(folded.of("WP01").lane()).isEqualTo(Lane.PLANNED);

    Map<String, Object> forced = AuthorityFixtures.row("done", "in_progress");
    forced.put("event_id", "00000000000000000000000002");
    forced.put("force", true);
    assertThat(WriteFold.fold(List.of(AuthorityFixtures.row("approved", "done"), forced)).of("WP01").lane())
        .isEqualTo(Lane.IN_PROGRESS);
  }

  @Test
  void sameIdSeedsAreCanonicalAndLosingSeedFillsOnlyUnwrittenClaimSlots() {
    Map<String, Object> real = AuthorityFixtures.row("genesis", "planned");
    Map<String, Object> seed = AuthorityFixtures.row("approved", "done");
    seed.put("event_id", "00000000000000000000000002");
    seed.put("actor", "migration:seed");
    seed.put("at", "2026-01-02T00:00:00+00:00");
    FoldView folded = WriteFold.fold(List.of(real, seed));
    assertThat(folded.of("WP01").lane()).isEqualTo(Lane.PLANNED);

    Map<String, Object> claim = AuthorityFixtures.row("planned", "claimed");
    claim.put("event_id", "00000000000000000000000003");
    claim.put("actor", "migration:seed");
    claim.put("policy_metadata", Map.of("agent", "seed-agent", "shell_pid", 7));
    FoldView claimed = WriteFold.fold(List.of(real, claim));
    assertThat(claimed.of("WP01").agent()).isEqualTo("seed-agent");
  }

  private static Map<String, Object> annotation(String wpId, Map<String, Object> delta) {
    Map<String, Object> annotation = new LinkedHashMap<>();
    annotation.put("kind", "annotation");
    annotation.put("event_id", "0000000000000000000000000A");
    annotation.put("wp_id", wpId);
    annotation.put("at", "2026-01-04T00:00:00+00:00");
    annotation.put("actor", "implementer-a");
    annotation.put("delta", delta);
    return annotation;
  }

  private static Map<String, Object> delta(Map<String, Object> values) {
    return new LinkedHashMap<>(values);
  }
}
