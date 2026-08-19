plugins {
    java
    alias(libs.plugins.spring.boot)
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

dependencies {
    implementation(platform(libs.spring.boot.dependencies))
    implementation(libs.spring.boot.actuator)
    implementation(libs.spring.boot.opentelemetry)
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")
    implementation(project(":platform-identity-assertion"))
    implementation(project(":platform-http"))
    testImplementation(testFixtures(project(":platform-identity-assertion")))
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.springframework.boot:spring-boot-starter-kafka")
    implementation("org.springframework.boot:spring-boot-starter-data-r2dbc")
    implementation(libs.temporal.spring.boot.starter)
    implementation(libs.temporal.opentracing)
    implementation(libs.r2dbc.postgresql)
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.postgresql:postgresql:42.7.12")

    testImplementation(libs.spring.boot.test)
    testImplementation("org.wiremock:wiremock-standalone:3.9.2")
    testImplementation(libs.reactor.test)
    testImplementation(libs.temporal.testing)
    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.r2dbc)
    testImplementation(libs.testcontainers.kafka)
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.postgresql:postgresql:42.7.12")
    // VerificationNotificationCrossKafkaIT：同 JVM 手动启动 identity 完整上下文做跨服务通知 e2e。
    // implementation 依赖不跨模块传递，identity 启动所需但本模块没有的库在此补齐（mail/crypto/bcrypt/bouncycastle）。
    testImplementation(project(":services:identity-service"))
    testImplementation("org.springframework.boot:spring-boot-starter-mail")
    testImplementation(project(":platform-crypto"))
    testImplementation(libs.bcrypt)
    testImplementation(libs.bouncycastle)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

tasks.jar {
    enabled = false
}

tasks.bootJar {
    archiveFileName = "marketplace-service.jar"
}
