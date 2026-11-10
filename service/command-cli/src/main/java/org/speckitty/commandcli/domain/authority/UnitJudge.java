package org.speckitty.commandcli.domain.authority;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.speckitty.missionstatus.domain.model.Lane;

/** Judges one decoded unit in the binding unknown/claim/stale/pipeline/dependency/machine order. */
public final class UnitJudge {
  private static final Set<Lane> REVIEW_LANES = Set.of(Lane.FOR_REVIEW, Lane.IN_REVIEW, Lane.APPROVED);

  private final DependencyGate dependencyGate;
  private final ClaimGate claimGate;
  private final String root;

  /** Tests only: the root is the constant {@code "."}; production wires the real root through {@link #UnitJudge(DependencyGate, ClaimGate, String)}. */
  public UnitJudge(DependencyGate dependencyGate, ClaimGate claimGate) {
    this(dependencyGate, claimGate, ".");
  }

  public UnitJudge(DependencyGate dependencyGate, ClaimGate claimGate, String root) {
    this.dependencyGate = dependencyGate;
    this.claimGate = claimGate;
    this.root = root;
  }

  public Optional<Refusal> judge(UnitInput unit, FoldView state, MissionFacts facts) {
    Map<String, Object> row = unit.transitionRow();
    if ("annotation".equals(row.get("kind"))) return Optional.empty();
    Optional<Refusal> refusal = PipelineGuards.unknownLane(row);
    if (refusal.isPresent()) return refusal;
    if (unit.operation() == Operation.START_IMPLEMENTATION || unit.operation() == Operation.START_REVIEW) {
      refusal = claimGate.check(unit.operation(), unit, state);
      if (refusal.isPresent()) return refusal;
    }
    Lane from = PipelineGuards.lane(String.valueOf(row.get("from_lane")));
    Lane to = PipelineGuards.lane(String.valueOf(row.get("to_lane")));
    Lane actual = state.of(unit.wpId()).lane();
    refusal = PipelineGuards.staleFromLane(actual, from);
    if (refusal.isPresent()) return refusal;
    refusal = PipelineGuards.forceNoteRequired(unit.wpId(), from, row);
    if (refusal.isPresent()) return refusal;
    refusal = PipelineGuards.doneEvidence(row.get("evidence"));
    if (refusal.isPresent()) return refusal;
    Boolean ready = dependencyGate.readiness(unit.wpId(), from, to, state, facts);
    return LaneMachine.check(from, to, guardContext(unit, state, facts, ready));
  }

  /** The guard context of a transition unit: the row's inputs, the unit's request-only evidence flag and the resolved root. */
  GuardContext guardContext(UnitInput unit, FoldView state, MissionFacts facts, Boolean dependencyReady) {
    Map<String, Object> row = unit.transitionRow();
    Lane actual = state.of(unit.wpId()).lane();
    return Guards.context(transitionGuardRow(unit, row, actual), state, facts::subtaskRoster, dependencyReady,
        unit.implementationEvidencePresent(), root);
  }

  private static Map<String, Object> transitionGuardRow(UnitInput unit, Map<String, Object> row, Lane actual) {
    if (unit.operation() != Operation.START_IMPLEMENTATION || !Boolean.TRUE.equals(row.get("force"))
        || REVIEW_LANES.contains(actual)) {
      return row;
    }
    Map<String, Object> unforced = new LinkedHashMap<>(row);
    unforced.put("force", false);
    return unforced;
  }

  public Optional<Refusal> judge(UnitInput unit, Map<String, WpWriteState> state, MissionFacts facts) {
    return judge(unit, new FoldView(state), facts);
  }
}
