plugins {
    kotlin("jvm")
    application
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

application {
    mainClass.set("br.com.soat.MainKt")
}

tasks {
    shadowJar {
        archiveBaseName.set("application")
        archiveClassifier.set("")
        archiveVersion.set("")
        mergeServiceFiles()
        manifest {
            attributes["Main-Class"] = "br.com.soat.MainKt"
        }
    }

    test {
        useJUnitPlatform()
    }
}

dependencies {
    implementation(project(":domain"))
    implementation(project(":api"))
    implementation(project(":storage"))
    implementation(project(":worker"))
    implementation(project(":metric"))

    implementation(libs.kotlinx.coroutines.core)

    implementation(project.dependencies.platform(libs.koin.bom))
    implementation(libs.koin.core)

    implementation(libs.jackson.module.kotlin)
    implementation(libs.jackson.datatype.jsr310)
    implementation(libs.jackson.dataformat.yaml)

    implementation(libs.slf4j.api)
    implementation(libs.micrometer.registry.prometheus)
    runtimeOnly(libs.logback.classic)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.jackson.module.kotlin)
    testImplementation(libs.jackson.databind)
    testImplementation(libs.jackson.datatype.jsr310)
    testImplementation(libs.testcontainers.localstack)
    testImplementation(libs.aws.sdk.kotlin.sqs)
    testImplementation(libs.aws.sdk.kotlin.sns)
    testImplementation(libs.aws.sdk.kotlin.dynamodb)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
    testRuntimeOnly(libs.junit.platform.launcher)
}
