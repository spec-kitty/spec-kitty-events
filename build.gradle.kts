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

subprojects {
    pluginManager.withPlugin("java") {
        // These file collections are configuration-cache safe; their configurations resolve during task execution.
        val compileClasspath = objects.fileCollection().from(configurations.named("compileClasspath"))
        val testCompileClasspath = objects.fileCollection().from(configurations.named("testCompileClasspath"))
        val testRuntimeClasspath = objects.fileCollection().from(configurations.named("testRuntimeClasspath"))

        tasks.register("sonarLibraries") {
            group = "verification"
            description = "Writes absolute main and test Java classpaths for Sonar, one path per line."
            val librariesFile = layout.buildDirectory.file("sonar/libraries.txt")
            val testLibrariesFile = layout.buildDirectory.file("sonar/test-libraries.txt")
            inputs.files(compileClasspath).withPropertyName("compileClasspath")
            inputs.files(testCompileClasspath).withPropertyName("testCompileClasspath")
            inputs.files(testRuntimeClasspath).withPropertyName("testRuntimeClasspath")
            outputs.file(librariesFile)
            outputs.file(testLibrariesFile)
            doLast {
                val mainPaths = compileClasspath.files.map { it.absolutePath }.distinct().sorted()
                librariesFile.get().asFile.apply {
                    parentFile.mkdirs()
                    writeText(if (mainPaths.isEmpty()) "" else mainPaths.joinToString("\n", postfix = "\n"))
                }
                val testPaths = (testCompileClasspath.files + testRuntimeClasspath.files)
                    .map { it.absolutePath }
                    .distinct()
                    .sorted()
                testLibrariesFile.get().asFile.apply {
                    parentFile.mkdirs()
                    writeText(if (testPaths.isEmpty()) "" else testPaths.joinToString("\n", postfix = "\n"))
                }
            }
        }
    }
}
