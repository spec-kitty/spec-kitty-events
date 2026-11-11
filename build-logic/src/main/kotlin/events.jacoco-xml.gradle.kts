// JaCoCo's Sonar-facing XML report for every Java library and service, without changing coverage gates.
plugins {
    jacoco
}

val libs = the<VersionCatalogsExtension>().named("libs")

jacoco {
    toolVersion = libs.findVersion("jacoco").get().requiredVersion
}

// :domain-model and :event-log classes are also in the :mission-status-api aggregate; Sonar merges the duplicates.
tasks.named<Test>("test") {
    finalizedBy("jacocoTestReport")
}

tasks.named<JacocoReport>("jacocoTestReport") {
    reports {
        xml.required.set(true)
    }
}
