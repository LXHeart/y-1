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
    implementation(platform(libs.testcontainers.bom))
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation(libs.spring.boot.opentelemetry)
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")
    implementation("org.springframework.boot:spring-boot-starter-data-r2dbc")
    implementation("org.springframework.boot:spring-boot-starter-mail")
    implementation("org.springframework.boot:spring-boot-starter-kafka")
    // HLD 7.4：消费 BFF 签发的内部身份断言（CurrentAccountResolver 优先断言头，回退 cookie）。
    implementation(project(":platform-identity-assertion"))
    implementation(project(":platform-http"))
    implementation(project(":platform-messaging"))
    implementation(project(":platform-database"))
    testImplementation(testFixtures(project(":platform-identity-assertion")))
    // GL-P3-MERCHANT-001：KYB 敏感字段（法人身份证号/银行账号）信封加密。
    implementation(project(":platform-crypto"))
    implementation(libs.r2dbc.postgresql)
    implementation(libs.bcrypt)
    implementation(libs.bouncycastle)
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.postgresql:postgresql:42.7.12")

    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.spring.boot.test)
    testImplementation(libs.reactor.test)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.r2dbc)
    testImplementation(libs.testcontainers.kafka)
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.postgresql:postgresql:42.7.12")
    testImplementation("org.springframework.boot:spring-boot-test-autoconfigure")
    // GL-P1-NOTIFY-001：事务邮件 outbox 测试用 GreenMail（内存 SMTP），填 identity 邮件零测试的盲区。
    testImplementation("com.icegreen:greenmail-junit5:2.0.1")
    testImplementation("org.assertj:assertj-core")
}

tasks.test {
    useJUnitPlatform()
}

tasks.jar {
    // 恢复 plain jar（classifier 与 bootJar 的 identity-service.jar 并存，Docker/发布清单不受影响）：
    // marketplace 的 VerificationNotificationCrossKafkaIT 需要跨项目消费 identity main 类做双上下文 e2e，
    // jar 被 disable 时 project 依赖解析到空产物。
    enabled = true
    archiveClassifier = "plain"
}

tasks.bootJar {
    archiveFileName = "identity-service.jar"
}
