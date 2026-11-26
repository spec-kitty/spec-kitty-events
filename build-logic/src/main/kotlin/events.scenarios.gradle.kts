// Cucumber-JVM over the BDD scenarios of tests/scenarios/ (spec-kitty-events#199, #270, #284).
//
// tests/scenarios/jvm-runners.json names, for each Gradle project, its glue package and the scenario areas it runs; the
// contract check (scripts/ci/scenario_contract.py) reads the same file, so the two cannot disagree. A project that applies
// this plugin gets a `scenarios` task that reads its areas' feature files in place and runs the scenarios tagged @jvm or
// @jvm-only (neither @wip nor @manual) against its glue. It runs through Cucumber's own CLI, not a JUnit Platform engine, so Cucumber's
// engine never meets a module's JUnit platform. The JUnit XML report is the task output; the task fails when a scenario
// fails, a step is undefined or pending, or the number of scenarios that ran differs from what the @jvm/@jvm-only tags
// select (#286): `python3 scripts/ci/scenario_contract.py --jvm-count <project>`, the contract check's own parser
// (outline rows counted one each), so no count is kept by hand for two parallel PRs to bump to the same wrong value.
// `-Pscenarios.python=<exe>` names another interpreter (the script needs only the standard library). `check` depends
// on the task, so `verifyAll` and service-gate run it. Each module adds its own system properties to the task.
//
// Cucumber's CLI Main applies its arguments last (cucumber-core 7.20.1 Main.run builds them over the system properties,
// which are built over the environment, which is built over cucumber.properties), so the --tags, feature paths and
// --glue below win over any CUCUMBER_* variable or cucumber.* property. Settings the CLI does not pass, such as
// cucumber.execution.limit or cucumber.execution.dry-run, would still narrow the run: the task drops every cucumber
// environment variable before it starts (they are no declared input), and the count check fails any run they narrow.
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
val contractScript: RegularFile = rootProject.layout.projectDirectory.file("scripts/ci/scenario_contract.py")

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
    // Plain values for doLast, which may not reach the script object (configuration cache).
    val countCommand = listOf(
        providers.gradleProperty("scenarios.python").getOrElse("python3"),
        contractScript.asFile.absolutePath,
        "--features", scenarioRoot.asFile.absolutePath,
        "--jvm-runners", registryFile.asFile.absolutePath,
        "--jvm-count", project.path,
    )
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
    inputs.file(contractScript).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("countScript")
    outputs.file(report).withPropertyName("report")
    doFirst {
        // CUCUMBER_FILTER_TAGS, cucumber.filter.tags and cucumber_filter_tags are all read from the environment (#286).
        val cucumberVariable = Regex("(?i)^cucumber[._]")
        (this as JavaExec).environment.keys.removeIf { cucumberVariable.containsMatchIn(it) }
    }
    doLast {
        val xml = report.get().asFile.readText()
        val suite = Regex("""<testsuite\b[^>]*>""").find(xml)?.value
            ?: throw GradleException("Cucumber wrote no <testsuite> to ${report.get().asFile}")
        fun count(name: String): Int = Regex("""\b$name="(\d+)"""").find(suite)?.groupValues?.get(1)?.toInt() ?: 0
        val ran = count("tests")
        val counter = try {
            ProcessBuilder(countCommand).redirectErrorStream(true).start()
        } catch (e: java.io.IOException) {
            throw GradleException("Cannot run ${countCommand.first()} for the scenario count (-Pscenarios.python=<exe>)", e)
        }
        val counted = counter.inputStream.bufferedReader().readText().trim()
        val expected = counted.toIntOrNull()?.takeIf { counter.waitFor() == 0 && it > 0 }
            ?: throw GradleException("${countCommand.joinToString(" ")} did not print a positive scenario count: $counted")
        val notPassed = count("failures") + count("errors") + count("skipped")
        if (ran != expected || notPassed != 0) {
            throw GradleException(
                "Cucumber-JVM: $ran scenario(s) ran, $expected expected from the @jvm tags (scenario_contract.py --jvm-count), " +
                    "$notPassed not passed; see ${report.get().asFile}",
            )
        }
        logger.lifecycle("Cucumber-JVM: $ran of $expected scenario(s) of $ranAreas passed (count asserted)")
    }
}

tasks.named("check") { dependsOn(scenarios) }
