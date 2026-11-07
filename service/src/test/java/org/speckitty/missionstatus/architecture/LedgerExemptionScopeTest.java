package org.speckitty.missionstatus.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Rule 7 exempts {@code adapter.out.ledger} and {@code adapter.out.statuslog} by package name. Both exemptions are for event-log's file
 * mechanics only, so every production class in either package must come from that module: a query-service class placed there would
 * otherwise get file APIs.
 */
class LedgerExemptionScopeTest {

  @Test
  void onlyEventLogClassesSitInTheExemptedWritePackages() {
    List<JavaClass> classes = ArchitectureRules.mainClasses().stream().toList();
    for (String suffix : List.of("adapter.out.ledger", "adapter.out.statuslog")) {
      List<JavaClass> exempted = classes.stream().filter(c -> c.getPackageName().startsWith(ArchitectureRules.ROOT + "." + suffix)).toList();
      assertThat(exempted).as("the %s package is on the classpath (non-vacuous)", suffix).isNotEmpty();
      assertThat(exempted)
          .allSatisfy(c -> assertThat(c.getSource().orElseThrow().getUri().toString()).as(c.getName()).contains("event-log"));
    }
  }
}
