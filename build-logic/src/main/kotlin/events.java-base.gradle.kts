// Every Java module: one toolchain and release, JUnit Platform, reproducible archives.
// It holds no quality bar; libraries add theirs with `events.library`. Service conventions join at cutover.
plugins {
    java
}

// JDK 25 by default; `-Pevents.javaVersion=<n>` overrides it.
val eventsJavaVersion = providers.gradleProperty("events.javaVersion").map(String::toInt).orElse(25).get()

java {
    toolchain { languageVersion = JavaLanguageVersion.of(eventsJavaVersion) }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = eventsJavaVersion
    options.compilerArgs.add("-parameters")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}
