package org.speckitty.commandcli.domain.authority;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class GuardContextTest {
  private static final String ROOT = "/repo/root";

  @Test
  void derivesWorkspaceActorForceEvidenceAndReviewFields() {
    var row = AuthorityFixtures.row("in_progress", "for_review");
    row.put("actor", java.util.Map.of("tool", "codex", "role", "implementer"));
    row.put("force", true);
    row.put("reason", "rework");
    row.put("review_ref", "ref");
    row.put("evidence", AuthorityFixtures.approvalEvidence("approved", "ref"));
    row.put("review_result", AuthorityFixtures.reviewResult("approved", "ref"));
    GuardContext context = Guards.context(row, AuthorityFixtures.foldTo(org.speckitty.missionstatus.domain.model.Lane.IN_PROGRESS), wp -> {
      throw new AssertionError("roster unused under force");
    }, true, null, ROOT);
    assertThat(context.actor()).isEqualTo("codex");
    assertThat(context.workspaceContext()).isEqualTo("worktree:/repo/root");
    assertThat(context.subtasksComplete()).isNull();
    assertThat(context.implementationEvidencePresent()).isTrue();
    assertThat(context.force()).isTrue();
    assertThat(context.reason()).isEqualTo("rework");
    assertThat(context.reviewRef()).isEqualTo("ref");
    assertThat(context.reviewResult()).isNotNull();
    assertThat(context.dependencyReady()).isTrue();
  }

  @Test
  void rosterMustAllBeDoneOrSkipped() {
    var row = AuthorityFixtures.row("in_progress", "for_review");
    var state = AuthorityFixtures.foldTo(org.speckitty.missionstatus.domain.model.Lane.IN_PROGRESS);
    assertThat(Guards.context(row, state, wp -> new SubtaskRoster.Resolved(List.of()), null, null, ROOT).subtasksComplete()).isTrue();
    assertThat(Guards.context(row, state, wp -> new SubtaskRoster.Resolved(List.of("T001")), null, null, ROOT).subtasksComplete()).isFalse();
    assertThat(Guards.context(row, state, wp -> new SubtaskRoster.Resolved(List.of("T001")), null, null, ROOT).implementationEvidencePresent()).isTrue();
    assertThat(Guards.context(row, state, wp -> new SubtaskRoster.Resolved(List.of("T001")), null, null, ROOT).dependencyReady()).isNull();
    assertThat(Guards.context(row, state, wp -> new SubtaskRoster.Unresolvable(), null, null, ROOT).subtasksComplete()).isFalse();
  }

  @Test
  void aSuppliedEvidenceFlagIsUsedVerbatimOnlyForTheUnforcedReviewHandoff() {
    var row = AuthorityFixtures.row("in_progress", "for_review");
    var state = AuthorityFixtures.foldTo(org.speckitty.missionstatus.domain.model.Lane.IN_PROGRESS);
    java.util.function.Function<String, SubtaskRoster> done = wp -> new SubtaskRoster.Resolved(List.of());
    assertThat(Guards.context(row, state, done, null, false, ROOT).implementationEvidencePresent()).isFalse();
    assertThat(Guards.context(row, state, done, null, true, ROOT).implementationEvidencePresent()).isTrue();
    assertThat(Guards.context(row, state, done, null, null, ROOT).implementationEvidencePresent()).isTrue();
    // The flag never touches the subtask half.
    var incomplete = (java.util.function.Function<String, SubtaskRoster>) wp -> new SubtaskRoster.Resolved(List.of("T001"));
    assertThat(Guards.context(row, state, incomplete, null, true, ROOT).subtasksComplete()).isFalse();
    assertThat(Guards.context(row, state, incomplete, null, false, ROOT).subtasksComplete()).isFalse();
    // Another transition has no evidence input at all.
    var other = AuthorityFixtures.row("planned", "claimed");
    assertThat(Guards.context(other, AuthorityFixtures.foldTo(org.speckitty.missionstatus.domain.model.Lane.PLANNED), done, null, false, ROOT)
        .implementationEvidencePresent()).isNull();
  }

  @Test
  void theWorkspaceContextCarriesTheModeAndTheRootItWasGiven() {
    var row = AuthorityFixtures.row("claimed", "in_progress");
    var state = AuthorityFixtures.foldTo(org.speckitty.missionstatus.domain.model.Lane.CLAIMED);
    java.util.function.Function<String, SubtaskRoster> none = wp -> new SubtaskRoster.Resolved(List.of());
    assertThat(Guards.context(row, state, none, null, null, "/a/b").workspaceContext()).isEqualTo("worktree:/a/b");
    row.put("execution_mode", "direct_repo");
    assertThat(Guards.context(row, state, none, null, null, "/c").workspaceContext()).isEqualTo("direct_repo:/c");
  }
}
