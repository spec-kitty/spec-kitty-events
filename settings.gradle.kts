// spec-kitty-events: one Gradle build for the JVM side of the events domain (ADR 0003).
// The Python package (src/, pyproject.toml) is not part of this build; the Java binding reads its
// producer_contract directory in place. Conventions live under build-logic/.
pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
    }
}

rootProject.name = "spec-kitty-events"

// The Java binding of the CloudEvents contract (spec-kitty-events#92), built from java/.
include(":java-binding")
project(":java-binding").projectDir = file("java")

// The events services (ADR 0002), imported from core at cutover with their history (#147). The Mission Status API
// is the query-service deployable; domain-model and event-log are its framework-free libraries (core #6117 decision
// 12, #6192). The project paths are the ones core's standalone build used, so `:event-log:test` and the service's
// project(":domain-model") references keep their meaning.
include(":mission-status-api")
project(":mission-status-api").projectDir = file("service")
include(":domain-model")
project(":domain-model").projectDir = file("service/domain-model")
include(":event-log")
project(":event-log").projectDir = file("service/event-log")
// The write-service deployable (core #6117 decision 12; ADR 0002; spec-kitty-events#156): command-cli, the resident
// service per git common dir that core's launcher starts. It builds as a jar and a GraalVM native image like the
// Mission Status API.
include(":command-cli")
project(":command-cli").projectDir = file("service/command-cli")

// The service contracts and their tooling (contracts/build.gradle stays Groovy). Its tasks validate the split
// contracts and, driven by contracts/tools/bundle.py and client_smoke.py, the bundle; `check` validates the split roots.
include(":contracts")
project(":contracts").projectDir = file("contracts")
