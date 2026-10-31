package org.speckitty.commandcli.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.onionArchitecture;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.core.importer.Location;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The onion layout of the write service (charter: ArchUnit onion and boundary rules are mandatory for service modules; plan C-003).
 * The domain is framework-free and file-free, the application depends only on the domain, adapters never depend on each other, and
 * nothing but configuration wires them together.
 */
class ArchitectureRulesTest {

  static final String ROOT = "org.speckitty.commandcli";

  private static JavaClasses main;

  @BeforeAll
  static void importMainClasses() {
    main =
        new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .withImportOption((Location location) -> !location.contains("/classes/java/aotTest/"))
            .importPackages(ROOT);
  }

  @Test
  void theProductionClassSetIsNotEmpty() {
    assertThat(main.size()).isPositive();
  }

  @Test
  void theLayersFormAnOnion() {
    ArchRule rule =
        onionArchitecture()
            .domainModels(ROOT + ".domain..")
            .applicationServices(ROOT + ".application..")
            .adapter("http", ROOT + ".adapter.in.http..")
            .adapter("process", ROOT + ".adapter.in.process..")
            .adapter("filesystem", ROOT + ".adapter.out..")
            .ignoreDependency(
                com.tngtech.archunit.base.DescribedPredicate.describe("configuration", c -> c.getPackageName().startsWith(ROOT + ".config")),
                com.tngtech.archunit.base.DescribedPredicate.alwaysTrue())
            .withOptionalLayers(true);
    rule.check(main);
  }

  @Test
  void theDomainUsesNoFrameworkAndNoFiles() {
    noClasses()
        .that()
        .resideInAPackage(ROOT + ".domain..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage("org.springframework..", "tools.jackson..", "com.fasterxml..", "jakarta..", "java.nio.file..", "java.io..", "java.nio.channels..")
        .allowEmptyShould(true)
        .check(main);
  }

  @Test
  void theApplicationUsesNoWebFrameworkAndNoFiles() {
    noClasses()
        .that()
        .resideInAPackage(ROOT + ".application..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage("org.springframework.web..", "jakarta.servlet..", "tools.jackson..", "java.nio.file..", "java.nio.channels..")
        .allowEmptyShould(true)
        .check(main);
  }

  @Test
  void onlyConfigurationDependsOnConfiguration() {
    noClasses()
        .that()
        .resideOutsideOfPackage(ROOT + ".config..")
        .should()
        .dependOnClassesThat()
        .resideInAPackage(ROOT + ".config..")
        .allowEmptyShould(true)
        .check(main);
  }
}
