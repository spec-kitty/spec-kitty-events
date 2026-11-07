package org.speckitty.commandcli.domain.authority;

import java.util.Map;
import java.util.Optional;
import org.speckitty.missionstatus.domain.model.Lane;

/** Judges one decoded unit in the binding unknown/claim/stale/pipeline/dependency/machine order. */
public final class UnitJudge {
  private final DependencyGate dependencyGate;
  private final ClaimGate claimGate;

  public UnitJudge(DependencyGate dependencyGate, ClaimGate claimGate) {
    this.dependencyGate = dependencyGate;
    this.claimGate = claimGate;
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
    refusal = PipelineGuards.doneEvidence(to, row.get("evidence"));
    if (refusal.isPresent()) return refusal;
    Boolean ready = dependencyGate.readiness(unit.wpId(), from, to, state, facts);
    GuardContext context = Guards.context(row, state, facts::subtaskRoster, ready);
    return LaneMachine.check(from, to, context);
  }

  public Optional<Refusal> judge(UnitInput unit, Map<String, WpWriteState> state, MissionFacts facts) {
    return judge(unit, new FoldView(state), facts);
  }
}
