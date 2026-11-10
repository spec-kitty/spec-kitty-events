package org.speckitty.commandcli.domain.authority.batch;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.speckitty.commandcli.domain.authority.FoldView;
import org.speckitty.commandcli.domain.authority.Judgement;
import org.speckitty.commandcli.domain.authority.LogLine;
import org.speckitty.commandcli.domain.authority.MissionFacts;
import org.speckitty.commandcli.domain.authority.Refusal;
import org.speckitty.commandcli.domain.authority.UnitInput;
import org.speckitty.commandcli.domain.authority.UnitJudge;
import org.speckitty.commandcli.domain.authority.WriteFold;
import org.speckitty.commandcli.domain.authority.claim.ClaimRules;
import org.speckitty.commandcli.domain.authority.dependency.DependencyVerdict;

/** Judges each new unit against the log plus all earlier accepted units of the same request. */
public final class BatchJudge {
  private final UnitJudge unitJudge;

  public BatchJudge(UnitJudge unitJudge) {
    this.unitJudge = unitJudge;
  }

  /** Tests only: the root is the constant {@code "."}; production wires the real root through {@link #standard(String)}. */
  public static BatchJudge standard() {
    return standard(".");
  }

  public static BatchJudge standard(String root) {
    return new BatchJudge(new UnitJudge(new DependencyVerdict(), new ClaimRules(), root));
  }

  public Judgement judge(List<LogLine> log, List<UnitInput> newUnits, boolean batch, MissionFacts facts) {
    List<LogLine> accumulated = new ArrayList<>(log);
    for (int index = 0; index < newUnits.size(); index++) {
      UnitInput unit = newUnits.get(index);
      FoldView state = WriteFold.fold(accumulated);
      Optional<Refusal> refusal = unitJudge.judge(unit, state, facts);
      if (refusal.isPresent()) return new Judgement.Refused(indexed(refusal.orElseThrow(), batch ? index : null));
      for (Map<String, Object> row : unit.rows()) accumulated.add(new LogLine.Value(row));
    }
    return new Judgement.Accepted();
  }

  private static Refusal indexed(Refusal refusal, Integer index) {
    return new Refusal(refusal.code(), refusal.reason(), refusal.message(), refusal.detail(), index);
  }
}
