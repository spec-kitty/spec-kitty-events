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

// The events service modules join this build at cutover (ADR 0002).
