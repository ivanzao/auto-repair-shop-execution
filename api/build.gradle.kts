dependencies {
    implementation(project.dependencies.platform(libs.koin.bom))

    implementation(project(":domain"))

    implementation(libs.koin.ktor)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.statusPages)
    implementation(libs.ktor.server.swagger)
    implementation(libs.ktor.serialization.jackson)
    implementation(libs.ktor.logging)
    implementation(libs.ktor.server.metrics.micrometer)
    implementation(libs.micrometer.registry.prometheus)

    implementation(libs.jackson.module.kotlin)
    implementation(libs.jackson.datatype.jsr310)

    implementation(libs.slf4j.api)
    runtimeOnly(libs.logback.classic)
    runtimeOnly(libs.logstash.logback.encoder)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
