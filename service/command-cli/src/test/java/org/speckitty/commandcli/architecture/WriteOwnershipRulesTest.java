package org.speckitty.commandcli.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;
import org.speckitty.commandcli.architecture.fixture.writes.ConformingReader;
import org.speckitty.commandcli.architecture.fixture.writes.RogueWriter;
import org.speckitty.missionstatus.architecture.FileWriteApis;

/**
 * FR-010's command-cli half: only adapter.out.filesystem writes files (endpoint/service lock). Event-log's statuslog and ledger
 * allowances are enforced by EventLogBoundaryTest, and the read service is covered by the root ArchitectureRules rule 7.
 */
class WriteOwnershipRulesTest {
  private static final ArchRule RULE = noClasses()
      .that().resideOutsideOfPackage("org.speckitty.commandcli.adapter.out.filesystem..")
      .should().callCodeUnitWhere(FileWriteApis.jdkOpenForWriteApi())
      .because("only org.speckitty.commandcli.adapter.out.filesystem may write command-cli state-directory files; status logs go through event-log");

  @Test
  void mainClassesHoldTheRule() {
    var classes = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
        .importPackages("org.speckitty.commandcli");
    assertThat(RULE.evaluate(classes).hasViolation()).isFalse();
  }

  @Test
  void theRuleFiresOnThePlantedWriterAndAcceptsARead() {
    assertThat(RULE.evaluate(new ClassFileImporter().importClasses(RogueWriter.class)).hasViolation()).isTrue();
    assertThat(RULE.evaluate(new ClassFileImporter().importClasses(ConformingReader.class)).hasViolation()).isFalse();
  }
}
