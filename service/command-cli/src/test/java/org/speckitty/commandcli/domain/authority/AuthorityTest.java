package org.speckitty.commandcli.domain.authority;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AuthorityTest {
  @Test
  void standardAssemblyAndAuthorityInputJudgeTheNewUnits() {
    List<Map<String, Object>> logRows = AuthorityFixtures.rows(new String[][] {{"genesis", "planned"}});
    List<LogLine> log = logRows.stream().map(LogLine.Value::new).map(LogLine.class::cast).toList();
    UnitInput claim = new UnitInput("WP01", Operation.TRANSITION, List.of(AuthorityFixtures.row("planned", "claimed")));
    MissionFacts facts = new CorpusMissionFacts(Map.of());
    Authority authority = Authority.standard();
    assertThat(authority.judge(log, List.of(claim), false, facts)).isInstanceOf(Judgement.Accepted.class);
    assertThat(authority.judge(new AuthorityInput(log, List.of(claim), false, facts))).isInstanceOf(Judgement.Accepted.class);
  }
}
