import org.springframework.boot.gradle.plugin.SpringBootPlugin

// domain-model: the shared Mission status types (Mission, work package, lane, status event and snapshot). A library of #6117
// decision 12, extracted from the query service by #6192. It is framework-free and uses no java.io or java.nio.file: it declares no
// dependency outside the tests, and DomainModelBoundaryTest holds its classes to the JDK packages of the root architecture rule 1b.
// The Spring Boot BOM is imported for the test libraries' versions only (no version literal in this script, as in the service).
// The toolchain, compiler settings, JaCoCo version and test failure format come from the events.service convention.

plugins {
    id("events.service")
    `java-library`
    id("io.spring.dependency-management")
}

dependencyManagement {
    imports {
        mavenBom(SpringBootPlugin.BOM_COORDINATES)
    }
}

dependencies {
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testImplementation(libs.archunit.junit5)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.named<Test>("test") {
    useJUnitPlatform()
    // A root-level `./gradlew test --tests <pattern>` selects this task too; a pattern that names a root test must not fail it.
    filter.isFailOnNoMatchingTests = false
    // The boundary test imports exactly this module's production classes.
    systemProperty("module.mainClasses", sourceSets.main.get().output.classesDirs.asPath)
}
