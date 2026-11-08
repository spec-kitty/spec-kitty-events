package org.speckitty.commandcli.domain.authority;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.speckitty.commandcli.domain.service.RefusalReason;
import org.speckitty.missionstatus.domain.model.Lane;
import org.speckitty.missionstatus.domain.model.PyText;

/** Pipeline checks which core performs immediately before its lane machine. */
public final class PipelineGuards {
  private static final String DONE_MESSAGE = "Moving to done requires evidence with review.reviewer review.verdict, and review.reference";
  private static final String STALE_MESSAGE = "The unit's from_lane is not the work package's current lane.";

  private PipelineGuards() {}

  public static Optional<Refusal> unknownLane(Map<String, Object> row) {
    for (String key : new String[] {"from_lane", "to_lane"}) {
      String original = String.valueOf(row.get(key));
      if (lane(original) == null) {
        return Optional.of(Refusal.of(RefusalReason.UNKNOWN_LANE, "Unknown lane: " + original));
      }
    }
    return Optional.empty();
  }

  public static Optional<Refusal> staleFromLane(Lane actual, Lane rowFrom) {
    if (actual != rowFrom && !(actual == Lane.UNINITIALIZED && rowFrom == Lane.GENESIS)) {
      return Optional.of(Refusal.of(RefusalReason.STALE_FROM_LANE, STALE_MESSAGE));
    }
    return Optional.empty();
  }

  public static Optional<Refusal> forceNoteRequired(String wpId, Lane from, Map<String, Object> row) {
    boolean reviewLane = from == Lane.IN_REVIEW || from == Lane.APPROVED;
    if (!Boolean.TRUE.equals(row.get("force")) || !reviewLane || row.get("review_result") != null
        || Actors.isMigration(row.get("actor")) || !PyText.isBlank(text(row.get("reason")))) {
      return Optional.empty();
    }
    String lane = from.wire();
    String message = wpId + " cannot leave " + lane + " by force without a note. "
        + "--force requires a non-blank --note explaining why the review lane is being left; as an operator pass "
        + "`--force --note <why>` (`--reason <why>` on `agent status emit`).";
    return Optional.of(Refusal.of(RefusalReason.FORCE_NOTE_REQUIRED, message));
  }

  public static Optional<Refusal> doneEvidence(Object evidence) {
    if (evidence == null) return Optional.empty();
    Object review = field(evidence, "review");
    Object reviewer = field(review, "reviewer");
    Object verdict = field(review, "verdict");
    Object reference = field(review, "reference");
    if (!(review instanceof Map<?, ?>) || !truthy(reviewer) || !truthy(verdict)
        || !truthy(reference) || PyText.isBlank(String.valueOf(reference))) {
      return Optional.of(Refusal.of(RefusalReason.DONE_EVIDENCE_INCOMPLETE, DONE_MESSAGE));
    }
    return Optional.empty();
  }

  static Lane lane(String value) {
    try {
      return Lane.fromWire(value);
    } catch (IllegalArgumentException exception) {
      return null;
    }
  }

  private static Object field(Object value, String key) {
    return value instanceof Map<?, ?> map ? map.get(key) : null;
  }

  private static boolean truthy(Object value) {
    return switch (value) {
      case null -> false;
      case Boolean flag -> flag;
      case Number number -> number.doubleValue() != 0.0;
      case String string -> !string.isEmpty();
      case List<?> list -> !list.isEmpty();
      case Map<?, ?> map -> !map.isEmpty();
      default -> true;
    };
  }

  private static String text(Object value) {
    return value == null ? null : String.valueOf(value);
  }
}
