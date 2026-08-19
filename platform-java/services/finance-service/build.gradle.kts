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
    implementation(project(":platform-reporting"))
    testImplementation(testFixtures(project(":platform-identity-assertion")))
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.springframework.boot:spring-boot-starter-kafka")
    implementation("org.springframework.boot:spring-boot-starter-data-r2dbc")
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation(libs.r2dbc.postgresql)
    implementation("org.postgresql:postgresql:42.7.12")

    testImplementation(libs.spring.boot.test)
    testImplementation(libs.reactor.test)
    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.r2dbc)
    // 任务书 #31：审判奖励消费者真 broker IT（镜像 identity NotificationKafkaTestcontainersIT）
    testImplementation(libs.testcontainers.kafka)
    testImplementation("org.awaitility:awaitility:4.3.0")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.postgresql:postgresql:42.7.12")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

tasks.jar {
    enabled = false
}

tasks.bootJar {
    archiveFileName = "finance-service.jar"
}
