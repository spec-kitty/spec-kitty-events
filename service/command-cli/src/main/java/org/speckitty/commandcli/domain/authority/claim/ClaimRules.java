package org.speckitty.commandcli.domain.authority.claim;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.speckitty.commandcli.domain.authority.ClaimGate;
import org.speckitty.commandcli.domain.authority.FoldView;
import org.speckitty.commandcli.domain.authority.Operation;
import org.speckitty.commandcli.domain.authority.Refusal;
import org.speckitty.commandcli.domain.authority.UnitInput;
import org.speckitty.commandcli.domain.authority.WpWriteState;
import org.speckitty.commandcli.domain.service.RefusalReason;
import org.speckitty.missionstatus.domain.model.Lane;
import org.speckitty.missionstatus.domain.model.PyText;

/** Lifecycle-only claim, review re-claim, start and operator-force rules. */
public final class ClaimRules implements ClaimGate {
  private static final Set<Lane> REVIEW_LANES = Set.of(Lane.FOR_REVIEW, Lane.IN_REVIEW, Lane.APPROVED);
  private static final String FORCE_NOTE = "--force requires a non-blank --note explaining why the review lane is being left";
  private static final String FORCE_HINT = "`--force --note <why>`";

  public Optional<Refusal> check(UnitInput unit, FoldView state) {
    return check(unit.operation(), unit, state);
  }

  @Override
  public Optional<Refusal> check(Operation operation, UnitInput unit, FoldView state) {
    return switch (operation) {
      case TRANSITION -> Optional.empty();
      case START_IMPLEMENTATION -> startImplementation(unit, state.of(unit.wpId()));
      case START_REVIEW -> startReview(unit, state.of(unit.wpId()));
    };
  }

  private static Optional<Refusal> startImplementation(UnitInput unit, WpWriteState current) {
    Lane lane = current.lane() == Lane.UNINITIALIZED ? Lane.GENESIS : current.lane();
    if (lane == Lane.GENESIS) {
      return startRejected("WP " + unit.wpId() + " is not finalized; run `spec-kitty agent mission finalize-tasks`");
    }
    Map<String, Object> row = unit.transitionRow();
    Object requester = row.get("actor");
    boolean reviewExit = Boolean.TRUE.equals(row.get("force"));
    String note = row.get("reason") != null ? String.valueOf(row.get("reason")) : null;
    Optional<Refusal> force = rejectForce(unit.wpId(), lane, reviewExit, note);
    if (force.isPresent()) return force;
    return switch (lane) {
      case PLANNED -> Optional.empty();
      case CLAIMED -> implementationClaim(unit, current, requester, false);
      case IN_PROGRESS -> implementationClaim(unit, current, requester, true);
      case FOR_REVIEW, IN_REVIEW, APPROVED -> reviewExit
          ? reviewLaneExit(unit, current, requester, note)
          : wrongImplementationLane(unit.wpId(), lane);
      default -> wrongImplementationLane(unit.wpId(), lane);
    };
  }

  private static Optional<Refusal> implementationClaim(UnitInput unit, WpWriteState current, Object requester, boolean resume) {
    if (ActorCompatibility.compatible(current.actor(), requester, true)) return Optional.empty();
    if (resume && ActorCompatibility.sameRealActor(current.slot("latest_implementer_actor"), requester)) return Optional.empty();
    return claim(unit.wpId(), holder(current), "implementation");
  }

  private static Optional<Refusal> reviewLaneExit(UnitInput unit, WpWriteState current, Object requester, String note) {
    if (note != null) return Optional.empty();
    if (current.lane() == Lane.IN_REVIEW) return claim(unit.wpId(), holder(current), "review");
    if (current.lane() == Lane.APPROVED) {
      return startRejected("WP " + unit.wpId() + " is approved; implementation cannot resume it. To rework it run "
          + "`spec-kitty agent tasks move-task " + unit.wpId() + " --to planned --review-feedback-file <file>`, "
          + "or, as an operator, " + FORCE_HINT);
    }
    Object implementer = current.slot("latest_implementer_actor");
    if (ActorCompatibility.sameRealActor(implementer, requester)) return Optional.empty();
    String label = implementerLabel(implementer);
    return Optional.of(Refusal.of(RefusalReason.CLAIM_HELD_BY_OTHER_ACTOR,
        "WP " + unit.wpId() + " is in for_review; only its implementer of record (" + label + ") may withdraw it, "
            + "or an operator with " + FORCE_HINT));
  }

  private static Optional<Refusal> startReview(UnitInput unit, WpWriteState current) {
    Lane lane = current.lane() == Lane.UNINITIALIZED ? Lane.GENESIS : current.lane();
    return switch (lane) {
      case FOR_REVIEW -> Optional.empty();
      case IN_REVIEW -> reviewClaim(unit, current);
      default -> startRejected("WP " + unit.wpId() + " is in '" + lane.wire() + "', cannot start review");
    };
  }

  private static Optional<Refusal> reviewClaim(UnitInput unit, WpWriteState current) {
    String holder = current.tool() == null ? "" : current.tool().strip();
    String requester = ActorCompatibility.key(unit.transitionRow().get("actor"));
    if (holder.isEmpty() || !"reviewer".equals(current.role() == null ? null : current.role().strip()) || holder.equals(requester)) {
      return Optional.empty();
    }
    return claim(unit.wpId(), holder, "review");
  }

  private static Optional<Refusal> rejectForce(String wpId, Lane lane, boolean reviewExit, String note) {
    if (note == null) return Optional.empty();
    if (PyText.isBlank(note)) return startRejected(FORCE_NOTE);
    if (!reviewExit || !REVIEW_LANES.contains(lane)) {
      return startRejected("--force only applies to a WP in for_review, in_review or approved; " + wpId + " is in '" + lane.wire() + "'");
    }
    return Optional.empty();
  }

  private static String implementerLabel(Object implementer) {
    String label = implementer instanceof Map<?, ?> structured ? compactIdentity(structured)
        : implementer == null ? null : String.valueOf(implementer);
    return label == null || label.isEmpty() || "unknown".equals(label) ? "none on record" : label;
  }

  private static String compactIdentity(Map<?, ?> actor) {
    String profile = "wp_frontmatter".equals(actor.get("profile_source")) ? "" : identityPart(actor.get("profile"));
    String identity = identityPart(actor.get("tool")) + ":" + identityPart(actor.get("model")) + ":" + profile;
    while (identity.endsWith(":")) identity = identity.substring(0, identity.length() - 1);
    return identity;
  }

  private static String identityPart(Object value) {
    return value == null ? "" : String.valueOf(value).strip();
  }

  private static String holder(WpWriteState current) {
    return current.tool() == null || current.tool().isEmpty() ? "unknown" : current.tool();
  }

  private static Optional<Refusal> wrongImplementationLane(String wpId, Lane lane) {
    return startRejected("WP " + wpId + " is in '" + lane.wire() + "', cannot start implementation");
  }

  private static Optional<Refusal> claim(String wpId, String holder, String kind) {
    return Optional.of(Refusal.of(RefusalReason.CLAIM_HELD_BY_OTHER_ACTOR,
        "WP " + wpId + " is already claimed for " + kind + " by '" + holder + "'"));
  }

  private static Optional<Refusal> startRejected(String message) {
    return Optional.of(Refusal.of(RefusalReason.START_REJECTED, message));
  }
}
