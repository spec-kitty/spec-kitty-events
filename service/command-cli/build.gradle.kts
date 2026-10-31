import org.graalvm.buildtools.gradle.tasks.BuildNativeImageTask
import org.graalvm.buildtools.gradle.tasks.NativeRunTask
import java.util.Properties
import javax.xml.parsers.DocumentBuilderFactory

// command-cli: the write-service deployable (core #6117 decision 12; ADR 0002; ADR 0003; spec-kitty-events#156).
//
// One resident process per git common dir, started by core's launcher as `command-cli serve --git-common-dir <abs>
// [--idle-timeout <s>]` (contracts/mission-write/LAUNCHER.md). It shares the read service's stack (core ADR 2026-10-27-1:
// Spring Boot with AOT, GraalVM native through the Spring Boot plugin, the guard copied with its tests) and its test partition:
//   Tags:   native        runs on the JVM in `test` AND natively through nativeTest (list written by nativeTagJvmTest)
//           native-binary needs a built binary (NativeSmokeTest): only nativeSmoke runs it
//           process       starts the boot jar as a child process (processTest); never part of `test`
//   `check` depends on nativeTagJvmTest, processTest and the JaCoCo rules; it never runs the binary tests.
// Writes refuse fail-closed until the canonical writer (#160) and the lane authority (#161) land.

plugins {
    id("events.service")
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
    alias(libs.plugins.graalvm.native)
}

// Guard: the native image needs Spring AOT (see service/build.gradle.kts and the root build).
tasks.named("processAot")
tasks.named("processTestAot")

// processTestAot fails with "'groupId' must not be empty" without a group.
group = "org.speckitty.commandcli"
version = "0.0.0-dev"

// The version the service reports in its handshake (serviceVersion), written to META-INF/build-info.properties. The release
// build passes -PreleaseVersion=<ver>; a development build reports 0.0.0-dev. It must match the Handshake pattern ^[0-9A-Za-z][0-9A-Za-z.+_-]{0,63}$.
val serviceVersion: String = providers.gradleProperty("releaseVersion").orElse("0.0.0-dev").get()

dependencies {
    implementation(libs.spring.boot.starter.web)
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.archunit.junit5)
    testImplementation(libs.json.schema.validator)
    testImplementation(libs.snakeyaml)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// ---------------------------------------------------------------------------------------------------------------------------
// Contract consumption: no code generation. The service writes its few bodies by hand (Handshake, Health, the WriteRefusal) and the
// conformance tests validate every one of them against the split contract's schemas, read in place from contracts/mission-write. The
// generator's spring models for this contract need Jackson 2 annotations (uniqueItems) and mistype its `const: true` members, which a
// Jackson 3 service cannot compile; the read service avoids the same by generating only a filtered handful of models.
// ---------------------------------------------------------------------------------------------------------------------------

val contractRoot: String = providers.gradleProperty("contractRoot").orElse(file("../../contracts").path).get()

springBoot {
    mainClass.set("org.speckitty.commandcli.config.CommandCliMain")
    // META-INF/build-info.properties carries the version the handshake reports; the native image has no jar manifest to read it from.
    // The build time is left out so the jar stays reproducible.
    buildInfo {
        excludes.set(setOf("time"))
        properties {
            name.set("command-cli")
            version.set(serviceVersion)
        }
    }
}

tasks.bootJar {
    archiveFileName.set("command-cli.jar")
    manifest {
        attributes("Implementation-Title" to "command-cli", "Implementation-Version" to serviceVersion)
    }
}

// ---------------------------------------------------------------------------------------------------------------------------
// Test partition
// ---------------------------------------------------------------------------------------------------------------------------

val testSourceSet = sourceSets.test
val bootJarFile = tasks.bootJar.flatMap { it.archiveFile }

// Every JVM test task can read the split contract (the conformance tests validate responses against its schemas).
val contractRootPath: String = file(contractRoot).absolutePath
tasks.withType<Test>().configureEach {
    systemProperty("commandCli.contractRoot", contractRootPath)
}

tasks.named<Test>("test") {
    useJUnitPlatform {
        excludeTags("native-binary", "process")
    }
    finalizedBy("jacocoTestReport")
}

val nativeTestListDirectory = layout.buildDirectory.dir("test-results/nativeTagJvmTest/testlist")

// The JVM twin of the native test run (see service/build.gradle.kts): @Tag("native") classes, writing the unique-id list.
val nativeTagJvmTest = tasks.register<Test>("nativeTagJvmTest") {
    group = "verification"
    description = "Runs the @Tag(\"native\") tests on the JVM and writes the unique-id list the native test binary executes."
    testClassesDirs = testSourceSet.get().output.classesDirs
    classpath = testSourceSet.get().runtimeClasspath
    useJUnitPlatform {
        includeTags("native")
        excludeTags("process", "native-binary")
    }
    systemProperty("junit.platform.listeners.uid.tracking.enabled", "true")
    systemProperty("junit.platform.listeners.uid.tracking.output.dir", nativeTestListDirectory.get().asFile.path)
    outputs.dir(nativeTestListDirectory)
}

// The process tests start the boot jar the way core's launcher does (LAUNCHER.md) and drive its lifecycle.
val processTest = tasks.register<Test>("processTest") {
    group = "verification"
    description = "Starts the boot jar as a child process and drives the launcher contract (start, ready, shutdown, idle, signal)."
    testClassesDirs = testSourceSet.get().output.classesDirs
    classpath = testSourceSet.get().runtimeClasspath
    useJUnitPlatform {
        includeTags("process")
    }
    dependsOn(tasks.bootJar)
    inputs.file(bootJarFile).withPropertyName("bootJar")
    val jar = bootJarFile
    doFirst { systemProperty("commandCli.jar", jar.get().asFile.absolutePath) }
}

val nativeSmoke = tasks.register<Test>("nativeSmoke") {
    group = "verification"
    description = "Starts the built native binary and drives the launcher contract (needs -PnativeBinary=<path>)."
    testClassesDirs = testSourceSet.get().output.classesDirs
    classpath = testSourceSet.get().runtimeClasspath
    useJUnitPlatform {
        includeTags("native-binary")
    }
    val binary = providers.gradleProperty("nativeBinary")
    mustRunAfter("nativeCompile")
    doFirst {
        if (!binary.isPresent) {
            throw GradleException("nativeSmoke needs the built binary: pass -PnativeBinary=<path to the native executable>")
        }
    }
    binary.orNull?.let { systemProperty("nativeBinary", it) }
}

// ---------------------------------------------------------------------------------------------------------------------------
// Native image (GraalVM Native Build Tools through the Spring Boot plugin)
// ---------------------------------------------------------------------------------------------------------------------------

graalvmNative {
    // The image comes from JAVA_HOME (graalvm/setup-graalvm in CI), never from toolchain detection.
    toolchainDetection.set(false)
    binaries.named("main") {
        imageName.set("command-cli")
    }
}

tasks.named<BuildNativeImageTask>("nativeTestCompile") {
    dependsOn(nativeTagJvmTest)
    testListDirectory.set(nativeTestListDirectory)
}

tasks.named<NativeRunTask>("nativeTest") {
    dependsOn(nativeTagJvmTest)
    environment.put("JUNIT_PLATFORM_LISTENERS_UID_TRACKING_OUTPUT_DIR", nativeTestListDirectory.get().asFile.path)
    runtimeArgs.set(
        listOf(
            "-Djunit.platform.listeners.uid.tracking.output.dir=${nativeTestListDirectory.get().asFile.path}",
            "--xml-output-dir",
            layout.buildDirectory.dir("test-results/test-native").get().asFile.path,
        ),
    )
}

// ---------------------------------------------------------------------------------------------------------------------------
// JaCoCo: the read service's three rules over this module's domain.* and application.*, with the scope guard
// ---------------------------------------------------------------------------------------------------------------------------

val coverageExcludes = arrayOf("org/speckitty/commandcli/adapter/**", "org/speckitty/commandcli/config/**")
val coverageClassDirectories = files(sourceSets.main.get().output.classesDirs.asFileTree.matching { exclude(*coverageExcludes) })

tasks.named<JacocoReport>("jacocoTestReport") {
    dependsOn(tasks.test)
    classDirectories.setFrom(coverageClassDirectories)
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

tasks.named<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
    dependsOn(tasks.test)
    classDirectories.setFrom(coverageClassDirectories)
    violationRules {
        rule {
            element = "CLASS"
            includes = listOf("org.speckitty.commandcli.domain.*")
            limit {
                counter = "LINE"
                minimum = "0.90".toBigDecimal()
            }
        }
        rule {
            element = "CLASS"
            includes = listOf("org.speckitty.commandcli.application.*")
            limit {
                counter = "LINE"
                minimum = "0.90".toBigDecimal()
            }
        }
        rule {
            element = "CLASS"
            includes = listOf("org.speckitty.commandcli.domain.*")
            limit {
                counter = "BRANCH"
                minimum = "0.80".toBigDecimal()
            }
        }
    }
}

/** One scope pattern per verification rule; the key names the line of jacoco-class-floor.properties. */
val jacocoScopePatterns = mapOf(
    "domain" to "org.speckitty.commandcli.domain.*",
    "application" to "org.speckitty.commandcli.application.*",
)

/** The scope check of service/build.gradle.kts: a rule may not pass vacuously once its pattern matches a class. */
object CommandCliJacocoScope {
    fun regex(pattern: String): Regex =
        Regex(pattern.split("*").joinToString(".*") { part -> part.split("?").joinToString(".") { Regex.escape(it) } })

    fun failures(classNames: List<String>, floors: Properties?, patterns: Map<String, String>): List<String> {
        val failures = mutableListOf<String>()
        for ((key, pattern) in patterns) {
            val matched = classNames.count { regex(pattern).matches(it) }
            val floor = floors?.getProperty(key)?.trim()?.toIntOrNull()
            if (matched == 0) {
                if (floor != null && floor > 0) {
                    failures += "scope $key ($pattern) matched 0 classes but the floor is $floor"
                }
                continue
            }
            when {
                floors == null -> failures += "scope $key ($pattern) matched $matched classes but jacoco-class-floor.properties is absent"
                floor == null -> failures += "scope $key ($pattern) matched $matched classes but the floor file has no '$key' line"
                floor <= 0 -> failures += "scope $key ($pattern) matched $matched classes but its floor is $floor (must be positive)"
                matched < floor -> failures += "scope $key ($pattern) matched $matched classes, below the floor $floor"
            }
        }
        return failures
    }

    fun classNames(report: File): List<String> {
        val factory = DocumentBuilderFactory.newInstance()
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", false)
        val document = factory.newDocumentBuilder().parse(report)
        val nodes = document.getElementsByTagName("class")
        return (0 until nodes.length).map { nodes.item(it).attributes.getNamedItem("name").nodeValue.replace('/', '.') }
    }
}

val jacocoScopeCheck = tasks.register("jacocoScopeCheck") {
    group = "verification"
    description = "Fails unless each JaCoCo rule pattern matched at least the class count in jacoco-class-floor.properties."
    dependsOn("jacocoTestReport")
    val report = layout.buildDirectory.file("reports/jacoco/test/jacocoTestReport.xml")
    val floorFile = layout.projectDirectory.file("src/test/resources/jacoco-class-floor.properties")
    inputs.file(report)
    inputs.files(providers.provider { if (floorFile.asFile.isFile) listOf(floorFile.asFile) else emptyList() })
    val patterns = jacocoScopePatterns
    doLast {
        val reportFile = report.get().asFile
        if (!reportFile.isFile) {
            throw GradleException("jacocoScopeCheck: the JaCoCo XML report is missing: $reportFile")
        }
        val floors = if (floorFile.asFile.isFile) Properties().apply { floorFile.asFile.inputStream().use { load(it) } } else null
        val failures = CommandCliJacocoScope.failures(CommandCliJacocoScope.classNames(reportFile), floors, patterns)
        if (failures.isNotEmpty()) {
            throw GradleException("jacocoScopeCheck failed:\n" + failures.joinToString("\n") { "  - $it" })
        }
    }
}

tasks.named("check") {
    dependsOn("nativeTagJvmTest", processTest, "jacocoTestCoverageVerification", "jacocoScopeCheck")
}
