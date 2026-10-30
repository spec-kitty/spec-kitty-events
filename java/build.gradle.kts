// Java binding of the spec-kitty-events CloudEvents contract (spec-kitty-events#92): CloudEvents envelope
// validation and the legacy-row upcaster, against the committed schemas and fixtures.
//
// It reads the Python package's producer_contract directory in place (registry, schemas, upcast corpus): the
// committed files are the single source of truth and nothing is copied into the repository or generated.
// Not published; built and tested by `make test-java` (`./gradlew :java-binding:check`).
plugins {
    id("events.library")
}

group = "ai.spec-kitty"
// Must equal [project].version in pyproject.toml: one version line for both artifacts (ADR 0004).
// checkVersionMatchesPyproject below fails the check when the two drift.
version = "11.3.0"

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
