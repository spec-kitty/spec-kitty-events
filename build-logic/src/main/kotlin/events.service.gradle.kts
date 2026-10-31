// The service modules (service/, imported from core at cutover, #147): the settings core's standalone build repeated in
// each of its three module scripts, with the same behaviour. It deliberately holds nothing more: events.java-base's
// --release flag, JUnit Platform default and reproducible archives, and events.library's -Werror, would each change what
// the service compiles, runs or packages. Each module still declares its own plugins, dependencies and test tasks.
plugins {
    java
    jacoco
}

val libs = the<VersionCatalogsExtension>().named("libs")

java {
    toolchain { languageVersion = JavaLanguageVersion.of(25) }
}

// The settings the Spring Boot plugin gives a Boot project, so the library modules compile their moved sources the same
// way (non-ASCII literals in the tests). In a Boot project they are already set; setting them again changes nothing.
tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    if ("-parameters" !in options.compilerArgs) {
        options.compilerArgs.add("-parameters")
    }
}

jacoco {
    toolVersion = libs.findVersion("jacoco").get().requiredVersion
}

val serviceTestSlot = gradle.sharedServices.registerIfAbsent("serviceTestSlot", ServiceTestSlot::class) {
    maxParallelUsages = 1
}

tasks.withType<Test>().configureEach {
    testLogging {
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
    // As in core: the service test tasks run one at a time (timing budgets in perfTest and the stream tests) and always
    // execute, never replayed from the build cache (kitTest and the lock-interop spike depend on inputs Gradle cannot see).
    usesService(serviceTestSlot)
    outputs.doNotCacheIf("service tests always execute, as in core's build") { true }
}
