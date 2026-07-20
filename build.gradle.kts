import org.gradle.api.tasks.testing.Test
import org.gradle.testing.jacoco.tasks.JacocoReport
import org.gradle.testing.jacoco.plugins.JacocoTaskExtension

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    jacoco
    alias(libs.plugins.sonarqube)
}

repositories {
    mavenCentral()
}

subprojects {
    group = "br.com.soat"
    version = "1.0"

    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "jacoco")
    apply(plugin = "org.sonarqube")

    val aggregatedReportPath =
        rootProject.file("build/reports/jacoco/jacocoAggregatedReport/jacocoAggregatedReport.xml").absolutePath
    sonar {
        properties {
            property("sonar.coverage.jacoco.xmlReportPaths", aggregatedReportPath)
        }
    }

    repositories {
        mavenCentral()
    }

    configure<JacocoPluginExtension> {
        toolVersion = "0.8.12"
    }

    tasks.withType<Test> {
        useJUnitPlatform()
        exclude("**/*IntegrationTest.*")
        exclude("**/*BddTest.*")
    }

    tasks.register<Test>("integrationTest") {
        useJUnitPlatform()
        excludes.clear()
        include("**/*IntegrationTest.*")

        extensions.configure(JacocoTaskExtension::class) {
            isEnabled = true
            setDestinationFile(layout.buildDirectory.file("jacoco/integrationTest.exec").get().asFile)
        }

        dependsOn(tasks.named("classes"))
    }

    tasks.register<Test>("bddTest") {
        useJUnitPlatform()
        excludes.clear()
        include("**/*BddTest.*")

        extensions.configure(JacocoTaskExtension::class) {
            isEnabled = true
            setDestinationFile(layout.buildDirectory.file("jacoco/bddTest.exec").get().asFile)
        }

        dependsOn(tasks.named("classes"))
    }

    tasks.named<JacocoReport>("jacocoTestReport") {
        dependsOn(tasks.named("test"))
        enabled = false
    }
}

tasks.register<JacocoReport>("jacocoAggregatedReport") {
    dependsOn(subprojects.map { it.tasks.named("test") })
    dependsOn(subprojects.map { it.tasks.named("integrationTest") })
    dependsOn(subprojects.map { it.tasks.named("bddTest") })
    dependsOn(subprojects.map { it.tasks.named("classes") })

    group = "verification"
    description = "Generates an aggregated JaCoCo coverage report from all subprojects"

    reports {
        xml.required.set(true)
        html.required.set(true)
        csv.required.set(false)

        xml.outputLocation.set(layout.buildDirectory.file("reports/jacoco/jacocoAggregatedReport/jacocoAggregatedReport.xml"))
        html.outputLocation.set(layout.buildDirectory.dir("reports/jacoco/jacocoAggregatedReport/html"))
    }

    val execFiles = subprojects.map { subproject ->
        fileTree(subproject.layout.buildDirectory) {
            include("jacoco/test.exec", "jacoco/integrationTest.exec", "jacoco/bddTest.exec")
        }
    }

    val classFiles = subprojects.map { subproject ->
        fileTree(subproject.layout.buildDirectory) {
            include("classes/kotlin/main/**")
        }
    }

    val srcDirs = subprojects.map { subproject ->
        file("${subproject.projectDir}/src/main/kotlin")
    }

    executionData.setFrom(execFiles)
    classDirectories.setFrom(classFiles)
    sourceDirectories.setFrom(srcDirs)
}

sonar {
    properties {
        property("sonar.projectKey", "auto-repair-shop-execution")
        property("sonar.projectName", "Auto Repair Shop Execution")
        property("sonar.host.url", System.getenv("SONAR_HOST_URL") ?: "http://localhost:9000")
        property("sonar.token", System.getenv("SONAR_TOKEN") ?: "")

        property("sonar.exclusions", "**/build/**,**/*Fixtures.kt")
        property("sonar.test.exclusions", "**/build/**")
        property("sonar.coverage.exclusions", "**/main/src/main/kotlin/**,**/KtorHttpServer.kt,**/*DTO.kt")

        property(
            "sonar.coverage.jacoco.xmlReportPaths",
            "${layout.buildDirectory.get()}/reports/jacoco/jacocoAggregatedReport/jacocoAggregatedReport.xml"
        )
    }
}

tasks.named("sonar") {
    dependsOn(tasks.named("jacocoAggregatedReport"))
}