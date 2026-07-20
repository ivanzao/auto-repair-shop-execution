plugins {
    kotlin("jvm")
}

dependencies {
    implementation(project(":domain"))

    implementation(libs.slf4j.api)
    implementation(libs.micrometer.registry.prometheus)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
