// Cucumber-JVM over the BDD scenarios of tests/scenarios/ (spec-kitty-events#199, #270, #284).
//
// tests/scenarios/jvm-runners.json names, for each Gradle project, its glue package and the scenario areas it runs; the
// contract check (scripts/ci/scenario_contract.py) reads the same file, so the two cannot disagree. A project that applies
// this plugin gets a `scenarios` task that reads its areas' feature files in place and runs the scenarios tagged @jvm or
// @jvm-only (neither @wip nor @manual) against its glue. It runs through Cucumber's own CLI, not a JUnit Platform engine, so Cucumber's
// engine never meets a module's JUnit platform. The JUnit XML report is the task output; the task fails when a scenario
// fails, a step is undefined or pending, or no scenario ran (a tag filter that selects nothing must not pass). `check`
// depends on it, so `verifyAll` and service-gate run it. Each module adds its own system properties to the task.
plugins {
    java
}

val scenarioRoot: Directory = rootProject.layout.projectDirectory.dir("tests/scenarios")
val registryFile: RegularFile = scenarioRoot.file("jvm-runners.json")

@Suppress("UNCHECKED_CAST")
val runner: Map<String, Any> = run {
    val document = groovy.json.JsonSlurper().parseText(providers.fileContents(registryFile).asText.get()) as Map<String, Any>
    val runners = document["runners"] as List<Map<String, Any>>
    runners.singleOrNull { it["project"] == project.path }
        ?: throw GradleException("${project.path}: no single runner entry in ${registryFile.asFile}")
}
val glue = runner["glue"] as String

@Suppress("UNCHECKED_CAST")
val areas = runner["areas"] as List<String>

abstract class FeatureDirsArgument : CommandLineArgumentProvider {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val features: ConfigurableFileCollection

    @get:Internal
    abstract val dirs: ListProperty<String>

    override fun asArguments(): Iterable<String> = dirs.get()
}

val scenarios = tasks.register<JavaExec>("scenarios") {
    group = "verification"
    description = "Runs the @jvm and @jvm-only BDD scenarios of $areas with Cucumber-JVM (spec-kitty-events#270)."
    val report = layout.buildDirectory.file("test-results/scenarios/cucumber.xml")
    val ranAreas = areas.joinToString() // a plain value: doLast may not reach the script object
    classpath = sourceSets["test"].runtimeClasspath
    mainClass = "io.cucumber.core.cli.Main"
    systemProperty("cucumber.publish.quiet", "true")
    args(
        "--glue", glue,
        "--tags", "(@jvm or @jvm-only) and not @wip and not @manual",
        "--plugin", "summary",
        "--plugin", "junit:${report.get().asFile.absolutePath}",
        "--monochrome",
    )
    argumentProviders.add(objects.newInstance<FeatureDirsArgument>().apply {
        val roots = areas.map { scenarioRoot.dir(it) }
        features.from(roots.map { root -> fileTree(root) { include("**/*.feature") } })
        dirs.set(roots.map { it.asFile.absolutePath })
    })
    inputs.file(registryFile).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("runners")
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
        logger.lifecycle("Cucumber-JVM: $ran scenario(s) of $ranAreas passed")
    }
}

tasks.named("check") { dependsOn(scenarios) }
