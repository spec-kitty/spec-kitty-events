package org.speckitty.commandcli.domain.authority;

import java.util.List;
import org.speckitty.commandcli.domain.authority.batch.BatchJudge;
import org.speckitty.commandcli.domain.authority.claim.ClaimRules;
import org.speckitty.commandcli.domain.authority.dependency.DependencyVerdict;

/** The standard pure write-authority assembly. */
public final class Authority {
  private final BatchJudge batchJudge;

  private Authority(BatchJudge batchJudge) {
    this.batchJudge = batchJudge;
  }

  /** Tests only: the root is the constant {@code "."}; production wires the real root through {@link #standard(String)}. */
  public static Authority standard() {
    return standard(".");
  }

  /** The assembly whose guards see {@code root}, the resolved root checkout, in their workspace context. */
  public static Authority standard(String root) {
    return new Authority(BatchJudge.standard(root));
  }

  public Judgement judge(List<LogLine> log, List<UnitInput> newUnits, boolean batch, MissionFacts facts) {
    return batchJudge.judge(log, newUnits, batch, facts);
  }

  public Judgement judge(AuthorityInput input) {
    return judge(input.log(), input.newUnits(), input.batch(), input.facts());
  }
}
