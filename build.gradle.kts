// Root project: aggregation only. Module builds live in their own directories, conventions under build-logic/.
tasks.register("verifyAll") {
    group = "verification"
    description = "Runs every module's check task."
    dependsOn(subprojects.map { "${it.path}:check" })
}
