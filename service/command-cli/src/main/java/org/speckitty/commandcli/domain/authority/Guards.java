package org.speckitty.commandcli.domain.authority;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import org.speckitty.commandcli.domain.service.RefusalReason;
import org.speckitty.missionstatus.domain.model.Lane;
import org.speckitty.missionstatus.domain.model.PyText;

/** Core's non-forced lane guards in their exact order, with verbatim messages. */
public final class Guards {
  private static final String APPROVAL = "Transition to approved/done requires evidence (reviewer identity and approval reference)";

  private Guards() {}

  public static Optional<Refusal> check(Lane from, Lane to, GuardContext context) {
    return switch (from) {
      case PLANNED -> planned(to, context);
      case CLAIMED -> claimed(to, context);
      case IN_PROGRESS -> inProgress(to, context);
      case FOR_REVIEW -> forReview(to, context);
      case IN_REVIEW -> inReview(to, context);
      case APPROVED -> approved(to, context);
      default -> Optional.empty();
    };
  }

  /**
   * The inputs core's guards consult. {@code suppliedEvidence} is the unit's request-only {@code implementationEvidencePresent}: for the
   * unforced in_progress to for_review guard a supplied boolean is used verbatim and null falls back to inference, as core's pipeline does.
   * It never touches the subtask derivation. {@code root} is the resolved root checkout: the workspace context is core's default synthesis,
   * the execution mode, a colon and that root.
   */
  public static GuardContext context(Map<String, Object> row, FoldView state,
      Function<String, SubtaskRoster> roster, Boolean dependencyReady, Boolean suppliedEvidence, String root) {
    WpWriteState wp = state.of(String.valueOf(row.get("wp_id")));
    Lane from = Lane.fromWire(String.valueOf(row.get("from_lane")));
    Lane to = Lane.fromWire(String.valueOf(row.get("to_lane")));
    boolean force = Boolean.TRUE.equals(row.get("force"));
    Boolean subtasks = null;
    if (!force && from == Lane.IN_PROGRESS && to == Lane.FOR_REVIEW) {
      subtasks = switch (roster.apply(String.valueOf(row.get("wp_id")))) {
        case SubtaskRoster.Resolved resolved -> complete(resolved.ids(), wp.subtaskState());
        case SubtaskRoster.Unresolvable unresolvable -> false;
      };
    }
    Boolean evidence = from == Lane.IN_PROGRESS && to == Lane.FOR_REVIEW
        ? (suppliedEvidence != null ? suppliedEvidence : wp.hasTransitions())
        : null;
    String executionMode = String.valueOf(row.getOrDefault("execution_mode", "worktree"));
    return new GuardContext(Actors.project(row.get("actor")), executionMode + ":" + root, subtasks, evidence,
        text(row.get("reason")), text(row.get("review_ref")), row.get("evidence"), force, row.get("review_result"), dependencyReady);
  }

  private static boolean complete(List<String> roster, Map<String, String> state) {
    for (String id : roster) {
      String status = state.get(id);
      if (!"done".equals(status) && !"skipped".equals(status)) return false;
    }
    return true;
  }

  private static Optional<Refusal> planned(Lane to, GuardContext context) {
    if (to != Lane.CLAIMED) return Optional.empty();
    if (PyText.isBlank(context.actor())) return refusal(RefusalReason.ACTOR_REQUIRED, "Transition requires actor identity");
    if (Boolean.FALSE.equals(context.dependencyReady())) return dependencies("planned", "claimed");
    return Optional.empty();
  }

  private static Optional<Refusal> claimed(Lane to, GuardContext context) {
    if (to != Lane.IN_PROGRESS) return Optional.empty();
    if (PyText.isBlank(context.workspaceContext())) {
      return refusal(RefusalReason.WORKSPACE_CONTEXT_REQUIRED, "Transition claimed -> in_progress requires workspace context");
    }
    if (Boolean.FALSE.equals(context.dependencyReady())) return dependencies("claimed", "in_progress");
    return Optional.empty();
  }

  private static Optional<Refusal> inProgress(Lane to, GuardContext context) {
    if (to == Lane.FOR_REVIEW) {
      if (!Boolean.TRUE.equals(context.subtasksComplete())) {
        return refusal(RefusalReason.IMPLEMENTATION_EVIDENCE_REQUIRED,
            "Transition in_progress -> for_review requires completed subtasks or force with reason");
      }
      if (!Boolean.TRUE.equals(context.implementationEvidencePresent())) {
        return refusal(RefusalReason.IMPLEMENTATION_EVIDENCE_REQUIRED,
            "Transition in_progress -> for_review requires implementation evidence or force with reason");
      }
    }
    if (to == Lane.APPROVED) return reviewerApproval(context);
    if (to == Lane.PLANNED && PyText.isBlank(context.reason())) {
      return refusal(RefusalReason.REASON_REQUIRED, "Transition in_progress -> planned requires reason");
    }
    return Optional.empty();
  }

  private static Optional<Refusal> forReview(Lane to, GuardContext context) {
    if (to == Lane.IN_REVIEW && PyText.isBlank(context.actor())) {
      return refusal(RefusalReason.ACTOR_REQUIRED, "Transition requires actor identity");
    }
    return Optional.empty();
  }

  private static Optional<Refusal> approved(Lane to, GuardContext context) {
    if (to == Lane.DONE) return reviewerApproval(context);
    if ((to == Lane.IN_PROGRESS || to == Lane.PLANNED) && PyText.isBlank(context.reviewRef())) {
      return refusal(RefusalReason.REVIEW_REF_REQUIRED, "Transition requires review_ref (review feedback reference)");
    }
    return Optional.empty();
  }

  private static Optional<Refusal> inReview(Lane to, GuardContext context) {
    Optional<Refusal> complete = reviewResultComplete(context.reviewResult());
    if (complete.isPresent()) return complete;
    if (to == Lane.APPROVED || to == Lane.DONE) {
      Optional<Refusal> approval = inReviewApproval(context);
      if (approval.isPresent()) return approval;
    }
    return reviewConsistency(context);
  }

  private static Optional<Refusal> reviewResultComplete(Object result) {
    if (result == null) return refusal(RefusalReason.REVIEW_EVIDENCE_INVALID,
        "Transition from in_review requires review_result (structured review outcome)");
    for (String field : List.of("reviewer", "verdict", "reference")) {
      if (blank(field(result, field))) return refusal(RefusalReason.REVIEW_EVIDENCE_INVALID,
          "Transition from in_review requires review_result with " + field);
    }
    return Optional.empty();
  }

  private static Optional<Refusal> reviewerApproval(GuardContext context) {
    Object review = field(context.evidence(), "review");
    if (blank(field(review, "reviewer")) || blank(field(review, "reference"))
        || !"approved".equals(field(review, "verdict"))) {
      return refusal(RefusalReason.REVIEWER_APPROVAL_REQUIRED, APPROVAL);
    }
    return Optional.empty();
  }

  private static Optional<Refusal> inReviewApproval(GuardContext context) {
    if (context.evidence() != null) return reviewerApproval(context);
    Object result = context.reviewResult();
    if (!blank(field(result, "reviewer")) && "approved".equals(field(result, "verdict"))
        && !blank(field(result, "reference"))) return Optional.empty();
    return refusal(RefusalReason.REVIEWER_APPROVAL_REQUIRED, APPROVAL);
  }

  private static Optional<Refusal> reviewConsistency(GuardContext context) {
    Object result = context.reviewResult();
    if (context.reviewRef() != null && !Objects.equals(context.reviewRef(), field(result, "reference"))) {
      return refusal(RefusalReason.REVIEW_EVIDENCE_INVALID, "review_ref must match review_result.reference");
    }
    Object evidenceReview = field(context.evidence(), "review");
    if (context.evidence() != null && !sameReviewTriple(evidenceReview, result)) {
      return refusal(RefusalReason.REVIEW_EVIDENCE_INVALID, "Review evidence must match review_result");
    }
    return Optional.empty();
  }

  private static boolean sameReviewTriple(Object left, Object right) {
    return Objects.equals(field(left, "reviewer"), field(right, "reviewer"))
        && Objects.equals(field(left, "verdict"), field(right, "verdict"))
        && Objects.equals(field(left, "reference"), field(right, "reference"));
  }

  private static Object field(Object value, String key) {
    return value instanceof Map<?, ?> map ? map.get(key) : null;
  }

  private static boolean blank(Object value) {
    return value == null || PyText.isBlank(String.valueOf(value));
  }

  private static String text(Object value) {
    return value == null ? null : String.valueOf(value);
  }

  private static Optional<Refusal> dependencies(String from, String to) {
    return refusal(RefusalReason.DEPENDENCIES_UNSATISFIED,
        "Transition " + from + " -> " + to + " blocked: unsatisfied dependencies (force with reason to override)");
  }

  private static Optional<Refusal> refusal(RefusalReason reason, String message) {
    return Optional.of(Refusal.of(reason, message));
  }
}
