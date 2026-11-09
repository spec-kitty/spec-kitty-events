package org.speckitty.commandcli.domain.authority;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.speckitty.commandcli.domain.service.RefusalReason;
import org.speckitty.missionstatus.domain.model.Lane;
import org.speckitty.missionstatus.domain.model.PyText;

/** The 11-lane, 29-edge machine ported from core's WPState.check_transition. */
public final class LaneMachine {
  public record Edge(Lane from, Lane to) {}

  private static final Map<Lane, Set<Lane>> TARGETS = Map.ofEntries(
      Map.entry(Lane.GENESIS, Set.of(Lane.PLANNED, Lane.CANCELED)),
      Map.entry(Lane.UNINITIALIZED, Set.of()),
      Map.entry(Lane.PLANNED, Set.of(Lane.CLAIMED, Lane.BLOCKED, Lane.CANCELED)),
      Map.entry(Lane.CLAIMED, Set.of(Lane.IN_PROGRESS, Lane.BLOCKED, Lane.CANCELED)),
      Map.entry(Lane.IN_PROGRESS, Set.of(Lane.FOR_REVIEW, Lane.APPROVED, Lane.PLANNED, Lane.BLOCKED, Lane.CANCELED)),
      Map.entry(Lane.FOR_REVIEW, Set.of(Lane.IN_REVIEW, Lane.BLOCKED, Lane.CANCELED)),
      Map.entry(Lane.IN_REVIEW, Set.of(Lane.APPROVED, Lane.DONE, Lane.IN_PROGRESS, Lane.PLANNED, Lane.BLOCKED, Lane.CANCELED)),
      Map.entry(Lane.APPROVED, Set.of(Lane.DONE, Lane.IN_PROGRESS, Lane.PLANNED, Lane.BLOCKED, Lane.CANCELED)),
      Map.entry(Lane.DONE, Set.of()),
      Map.entry(Lane.BLOCKED, Set.of(Lane.IN_PROGRESS, Lane.CANCELED)),
      Map.entry(Lane.CANCELED, Set.of()));

  private static final Set<Edge> EDGES = TARGETS.entrySet().stream()
      .flatMap(entry -> entry.getValue().stream().map(to -> new Edge(entry.getKey(), to)))
      .collect(java.util.stream.Collectors.toUnmodifiableSet());

  private LaneMachine() {}

  public static Set<Edge> legalEdges() {
    return EDGES;
  }

  public static Set<Lane> targets(Lane from) {
    return TARGETS.get(from);
  }

  public static Optional<Refusal> check(Lane from, Lane to, GuardContext context) {
    if (to == Lane.GENESIS) return illegal(from, to);
    if (context.force()) return checkForce(context);
    if (!targets(from).contains(to)) return illegal(from, to);
    return Guards.check(from, to, context);
  }

  private static Optional<Refusal> checkForce(GuardContext context) {
    if (PyText.isBlank(context.actor()) || PyText.isBlank(context.reason())) {
      return Optional.of(Refusal.of(RefusalReason.FORCE_REQUIRES_ACTOR_AND_REASON, "Force transitions require actor and reason"));
    }
    return Optional.empty();
  }

  private static Optional<Refusal> illegal(Lane from, Lane to) {
    return Optional.of(Refusal.of(RefusalReason.ILLEGAL_TRANSITION,
        "Illegal transition: " + from.wire() + " -> " + to.wire()));
  }
}
