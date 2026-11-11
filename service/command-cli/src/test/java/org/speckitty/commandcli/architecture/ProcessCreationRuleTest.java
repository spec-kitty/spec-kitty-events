package org.speckitty.commandcli.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;
import org.speckitty.commandcli.architecture.planted.PlantedPidReader;
import org.speckitty.commandcli.architecture.planted.PlantedRuntimeExecCaller;
import org.speckitty.commandcli.architecture.planted.PlantedProcessCreator;

/** Proves the process-creation rule bites on a planted ProcessBuilder user and does not over-reach to ProcessHandle (#231 4.11). */
class ProcessCreationRuleTest {

  @Test
  void aPlantedProcessBuilderUserViolatesTheRule() {
    JavaClasses classes = new ClassFileImporter().importClasses(PlantedProcessCreator.class);
    assertThat(ArchitectureRulesTest.PROCESS_CREATION_ONLY_IN_MISSION.evaluate(classes).hasViolation()).isTrue();
  }

  @Test
  void aPlantedProcessHandlePidReaderPassesTheRule() {
    JavaClasses classes = new ClassFileImporter().importClasses(PlantedPidReader.class);
    assertThat(ArchitectureRulesTest.PROCESS_CREATION_ONLY_IN_MISSION.evaluate(classes).hasViolation()).isFalse();
  }

  @Test
  void aPlantedRuntimeExecCallerViolatesTheRule() {
    JavaClasses classes = new ClassFileImporter().importClasses(PlantedRuntimeExecCaller.class);
    assertThat(ArchitectureRulesTest.PROCESS_CREATION_ONLY_IN_MISSION.evaluate(classes).hasViolation()).isTrue();
  }
}
