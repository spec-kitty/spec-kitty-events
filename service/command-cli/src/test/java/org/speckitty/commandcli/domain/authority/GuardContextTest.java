package org.speckitty.commandcli.domain.authority;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class GuardContextTest {
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
    }, true);
    assertThat(context.actor()).isEqualTo("codex");
    assertThat(context.workspaceContext()).startsWith("worktree:");
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
    assertThat(Guards.context(row, state, wp -> new SubtaskRoster.Resolved(List.of()), null).subtasksComplete()).isTrue();
    assertThat(Guards.context(row, state, wp -> new SubtaskRoster.Resolved(List.of("T001")), null).subtasksComplete()).isFalse();
    assertThat(Guards.context(row, state, wp -> new SubtaskRoster.Resolved(List.of("T001")), null).implementationEvidencePresent()).isTrue();
    assertThat(Guards.context(row, state, wp -> new SubtaskRoster.Resolved(List.of("T001")), null).dependencyReady()).isNull();
    assertThat(Guards.context(row, state, wp -> new SubtaskRoster.Unresolvable(), null).subtasksComplete()).isFalse();
  }
}
