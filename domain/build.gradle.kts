dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.slf4j.api)
    implementation(libs.bouncycastle.provider)
    implementation(libs.logstash.logback.encoder)
    implementation(libs.opentelemetry.api)

    // Item pré-serializado (Map<String, AttributeValue>) flui pelos ports de saga (outbox/writer)
    // como bloco opaco montado pelos repositórios — tradeoff do design DynamoDB deste plano.
    implementation(libs.aws.sdk.kotlin.dynamodb)

    implementation(libs.jackson.module.kotlin)
    implementation(libs.jackson.datatype.jsr310)

    runtimeOnly(libs.logback.classic)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.mockk)
    testRuntimeOnly(libs.junit.platform.launcher)
}
