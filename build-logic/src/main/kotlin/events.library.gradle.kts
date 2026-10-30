// A published-shape Java library: the base conventions plus every compiler warning as an error.
plugins {
    id("events.java-base")
    `java-library`
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}
