package org.speckitty.commandcli.architecture;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideOutsideOfPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.speckitty.commandcli.architecture.fixture.authority.ClockAuthority;
import org.speckitty.commandcli.architecture.fixture.authority.ConcurrentAuthority;
import org.speckitty.commandcli.architecture.fixture.authority.ConformingAuthority;
import org.speckitty.commandcli.architecture.fixture.authority.FileAuthority;
import org.speckitty.commandcli.architecture.fixture.authority.FrameworkAuthority;
import org.speckitty.commandcli.architecture.fixture.authority.NioAuthority;

/** The write authority uses only its own model and the side-effect-free JDK surface (FR-016). */
class AuthorityPurityTest {
  private static final String AUTHORITY = "org.speckitty.commandcli.domain.authority..";
  private static final String FIXTURE = "org.speckitty.commandcli.architecture.fixture.authority..";
  private static JavaClasses main;

  @BeforeAll
  static void importMain() {
    main = new ClassFileImporter()
        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
        .withImportOption(location -> !location.contains("/classes/java/aotTest/"))
        .importPackages("org.speckitty.commandcli");
  }

  static DescribedPredicate<JavaClass> allowedjdk() {
    return resideInAnyPackage("java.lang..", "java.util..", "java.math..", "java.time..")
        .and(resideOutsideOfPackage("java.util.concurrent.."))
        .and(DescribedPredicate.describe("not a clock", javaClass ->
            !javaClass.getName().equals("java.time.Clock") && !javaClass.getName().equals("java.time.InstantSource")));
  }

  static ArchRule authorityRule(String authorityPackage) {
    return classes().that().resideInAPackage(authorityPackage).should().onlyDependOnClassesThat(
        resideInAnyPackage(authorityPackage,
            "org.speckitty.commandcli.domain.service..", "org.speckitty.missionstatus.domain.model..")
            .or(allowedjdk()))
        .because("domain.authority is pure: no frameworks, IO, NIO, networking, concurrency or clock");
  }

  @Test
  void realAuthorityIsPureAndNotVacuous() {
    assertThat(main.contain("org.speckitty.commandcli.domain.authority.UnitJudge")).isTrue();
    assertThat(main.contain("org.speckitty.commandcli.domain.authority.WriteFold")).isTrue();
    assertThat(authorityRule(AUTHORITY).evaluate(main).hasViolation())
        .as("violations:%n%s", authorityRule(AUTHORITY).evaluate(main).getFailureReport()).isFalse();
  }

  @Test
  void fileApiIsRejected() {
    assertThat(authorityRule(FIXTURE).evaluate(new ClassFileImporter().importClasses(FileAuthority.class)).hasViolation()).isTrue();
  }

  @Test
  void nioFileApiIsRejected() {
    assertThat(authorityRule(FIXTURE).evaluate(new ClassFileImporter().importClasses(NioAuthority.class)).hasViolation()).isTrue();
  }

  @Test
  void concurrencyIsRejected() {
    assertThat(authorityRule(FIXTURE).evaluate(new ClassFileImporter().importClasses(ConcurrentAuthority.class)).hasViolation()).isTrue();
  }

  @Test
  void frameworkIsRejected() {
    assertThat(authorityRule(FIXTURE).evaluate(new ClassFileImporter().importClasses(FrameworkAuthority.class)).hasViolation()).isTrue();
  }

  @Test
  void clockIsRejected() {
    assertThat(authorityRule(FIXTURE).evaluate(new ClassFileImporter().importClasses(ClockAuthority.class)).hasViolation()).isTrue();
  }

  @Test
  void conformingFixtureHolds() {
    assertThat(authorityRule(FIXTURE).evaluate(new ClassFileImporter().importClasses(ConformingAuthority.class)).hasViolation()).isFalse();
  }
}
