// Root project: aggregation only. Module builds live in their own directories, conventions under build-logic/.

plugins {
    // On the root classpath, applied by no project here. The service libraries (domain-model, event-log) apply the
    // dependency-management plugin by id and read SpringBootPlugin.BOM_COORDINATES; in core's standalone build the
    // service root project put both plugins on their classpath, here this block does.
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.spring.dependency.management) apply false
    // Spring Boot wires its AOT plugin (processAot, processTestAot) only when the GraalVM plugin class is visible
    // from Spring Boot's own classloader. With Spring Boot here and GraalVM only on :mission-status-api's classpath,
    // AOT silently drops out and the native image cannot start; the guard in service/build.gradle.kts fails if so.
    alias(libs.plugins.graalvm.native) apply false
}

tasks.register("verifyAll") {
    group = "verification"
    description = "Runs every module's check task."
    dependsOn(subprojects.map { "${it.path}:check" })
}
