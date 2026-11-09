package org.speckitty.commandcli.domain.authority;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.speckitty.missionstatus.domain.model.Lane;

final class AuthorityFixtures {
  private AuthorityFixtures() {}

  static GuardContext context() {
    return new GuardContext("implementer", "worktree:/root", true, true, "reason", null, null, false, null, null);
  }

  static GuardContext with(GuardContext base, String actor, Boolean dependencies) {
    return new GuardContext(actor, base.workspaceContext(), base.subtasksComplete(), base.implementationEvidencePresent(),
        base.reason(), base.reviewRef(), base.evidence(), base.force(), base.reviewResult(), dependencies);
  }

  static GuardContext force(GuardContext base, boolean force) {
    return new GuardContext(base.actor(), base.workspaceContext(), base.subtasksComplete(), base.implementationEvidencePresent(),
        base.reason(), base.reviewRef(), base.evidence(), force, base.reviewResult(), base.dependencyReady());
  }

  static GuardContext changing(GuardContext base, String workspace, Boolean subtasks, Boolean evidence, String reason,
      String reviewRef, Object doneEvidence, Object reviewResult) {
    return new GuardContext(base.actor(), workspace, subtasks, evidence, reason, reviewRef, doneEvidence, base.force(),
        reviewResult, base.dependencyReady());
  }

  static Map<String, Object> reviewResult(String verdict, String reference) {
    return new LinkedHashMap<>(Map.of("reviewer", "reviewer-a", "verdict", verdict, "reference", reference));
  }

  static Map<String, Object> approvalEvidence(String verdict, String reference) {
    return new LinkedHashMap<>(Map.of("review", reviewResult(verdict, reference)));
  }

  static Map<String, Object> row(String from, String to) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("event_id", "01J00000000000000000000000");
    row.put("wp_id", "WP01");
    row.put("from_lane", from);
    row.put("to_lane", to);
    row.put("at", "2026-01-01T00:00:00+00:00");
    row.put("actor", "implementer-a");
    row.put("force", false);
    row.put("execution_mode", "worktree");
    row.put("reason", null);
    row.put("review_ref", null);
    row.put("evidence", null);
    row.put("policy_metadata", null);
    return row;
  }

  static List<Map<String, Object>> rows(String[][] lanePairs) {
    List<Map<String, Object>> rows = new ArrayList<>();
    int id = 1;
    for (String[] pair : lanePairs) {
      Map<String, Object> row = row(pair[0], pair[1]);
      row.put("event_id", String.format("%026d", id++));
      rows.add(row);
    }
    return rows;
  }

  static FoldView foldTo(Lane lane) {
    List<String[]> pairs = new ArrayList<>();
    pairs.add(new String[] {"genesis", "planned"});
    if (lane != Lane.PLANNED) pairs.add(new String[] {"planned", "claimed"});
    if (lane == Lane.IN_PROGRESS || lane == Lane.FOR_REVIEW || lane == Lane.IN_REVIEW || lane == Lane.APPROVED || lane == Lane.DONE) {
      pairs.add(new String[] {"claimed", "in_progress"});
    }
    if (lane == Lane.FOR_REVIEW || lane == Lane.IN_REVIEW || lane == Lane.APPROVED || lane == Lane.DONE) {
      pairs.add(new String[] {"in_progress", "for_review"});
    }
    if (lane == Lane.IN_REVIEW || lane == Lane.APPROVED || lane == Lane.DONE) pairs.add(new String[] {"for_review", "in_review"});
    if (lane == Lane.APPROVED || lane == Lane.DONE) pairs.add(new String[] {"in_review", "approved"});
    if (lane == Lane.DONE) pairs.add(new String[] {"approved", "done"});
    List<Map<String, Object>> rows = new ArrayList<>();
    int id = 1;
    for (String[] pair : pairs) {
      Map<String, Object> row = row(pair[0], pair[1]);
      row.put("event_id", String.format("%026d", id++));
      rows.add(row);
    }
    return WriteFold.fold(rows);
  }
}
