// Java binding of the spec-kitty-events CloudEvents contract (spec-kitty-events#92): CloudEvents envelope
// validation and the legacy-row upcaster, against the committed schemas and fixtures.
//
// It reads the Python package's producer_contract directory in place (registry, schemas, upcast corpus): the
// committed files are the single source of truth and nothing is copied into the repository or generated.
// Not published; built and tested by `make test-java` (`./gradlew :java-binding:check`). `check` also runs the
// `scenarios` task: Cucumber-JVM over the @jvm BDD scenarios of tests/scenarios/ (spec-kitty-events#199).
plugins {
    id("events.library")
}

group = "ai.spec-kitty"
// Must equal [project].version in pyproject.toml: one version line for both artifacts (ADR 0004).
// checkVersionMatchesPyproject below fails the check when the two drift.
version = "11.5.0"

base.archivesName = "spec-kitty-events-java"

val contractDir: Directory = rootProject.layout.projectDirectory.dir("src/spec_kitty_events/producer_contract")
val upcastCases: Directory = contractDir.dir("fixtures/upcast/cases")

dependencies {
    implementation(libs.jackson.databind)
    implementation(libs.json.schema.validator)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testRuntimeOnly(libs.slf4j.nop)
    testImplementation(libs.cucumber.java)
}

// The registry and schemas, read in place from the Python package. A source-set srcDir cannot carry a target
// path, so processResources copies them under spec_kitty_events/producer_contract; `from` makes them task inputs.
tasks.processResources {
    from(contractDir) {
        include("*.json", "data/*.json", "composed/rows/*.json", "composed/read/*.json")
        into("spec_kitty_events/producer_contract")
    }
}

// Passes the corpus path as -Dupcast.cases while keying the cache on the directory's relative content, so a
// checkout at another path reuses the cached test result and a fixture change reruns the corpus test.
abstract class UpcastCasesArgument : CommandLineArgumentProvider {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val cases: DirectoryProperty

    override fun asArguments(): Iterable<String> = listOf("-Dupcast.cases=${cases.get().asFile.absolutePath}")
}

tasks.test {
    jvmArgumentProviders.add(objects.newInstance<UpcastCasesArgument>().apply { cases.set(upcastCases) })
}

// The @jvm BDD scenarios (spec-kitty-events#199): Cucumber-JVM reads the same feature files as pytest-bdd, in place
// under tests/scenarios/, and runs the scenarios tagged @jvm (not @wip) against the step classes in the glue package.
// It runs through Cucumber's own CLI, not a JUnit Platform engine, so Cucumber's engine never meets this module's
// JUnit 6 platform. The JUnit XML report is the task output; the task fails when a scenario fails, a step is undefined
// or pending, or no scenario ran (a tag filter that selects nothing must not pass).
val scenarioFeatures: Directory = rootProject.layout.projectDirectory.dir("tests/scenarios")

abstract class FeaturesArgument : CommandLineArgumentProvider {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val features: ConfigurableFileCollection

    @get:Internal
    abstract val root: DirectoryProperty

    override fun asArguments(): Iterable<String> = listOf(root.get().asFile.absolutePath)
}

val scenarios = tasks.register<JavaExec>("scenarios") {
    group = "verification"
    description = "Runs the @jvm BDD scenarios of tests/scenarios/ with Cucumber-JVM (spec-kitty-events#199)."
    val report = layout.buildDirectory.file("test-results/scenarios/cucumber.xml")
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "io.cucumber.core.cli.Main"
    systemProperty("cucumber.publish.quiet", "true")
    jvmArgumentProviders.add(objects.newInstance<UpcastCasesArgument>().apply { cases.set(upcastCases) })
    args(
        "--glue", "ai.speckitty.events.cloudevents",
        "--tags", "@jvm and not @wip",
        "--plugin", "summary",
        "--plugin", "junit:${report.get().asFile.absolutePath}",
        "--monochrome",
    )
    argumentProviders.add(objects.newInstance<FeaturesArgument>().apply {
        features.from(fileTree(scenarioFeatures) { include("**/*.feature") })
        root.set(scenarioFeatures)
    })
    outputs.file(report).withPropertyName("report")
    doLast {
        val xml = report.get().asFile.readText()
        val suite = Regex("""<testsuite\b[^>]*>""").find(xml)?.value
            ?: throw GradleException("Cucumber wrote no <testsuite> to ${report.get().asFile}")
        fun count(name: String): Int = Regex("""\b$name="(\d+)"""").find(suite)?.groupValues?.get(1)?.toInt() ?: 0
        val ran = count("tests")
        val notPassed = count("failures") + count("errors") + count("skipped")
        if (ran == 0 || notPassed != 0) {
            throw GradleException("Cucumber-JVM: $ran scenario(s), $notPassed not passed; see ${report.get().asFile}")
        }
        logger.lifecycle("Cucumber-JVM: $ran @jvm scenario(s) passed")
    }
}

tasks.check { dependsOn(scenarios) }

val checkVersionMatchesPyproject = tasks.register("checkVersionMatchesPyproject") {
    group = "verification"
    description = "Fails when this module's version differs from [project].version in pyproject.toml (ADR 0004)."
    val pyproject = rootProject.layout.projectDirectory.file("pyproject.toml")
    val expected = version.toString()
    inputs.file(pyproject).withPropertyName("pyproject").withPathSensitivity(PathSensitivity.NONE)
    inputs.property("version", expected)
    doLast {
        val text = pyproject.asFile.readText()
        val project = text.substringAfter("[project]", "").substringBefore("\n[")
        val actual = Regex("""(?m)^version\s*=\s*"([^"]+)"""").find(project)?.groupValues?.get(1)
            ?: throw GradleException("No [project].version in ${pyproject.asFile}")
        if (actual != expected) {
            throw GradleException("java-binding version $expected differs from pyproject.toml version $actual (ADR 0004)")
        }
    }
}

tasks.check { dependsOn(checkVersionMatchesPyproject) }
