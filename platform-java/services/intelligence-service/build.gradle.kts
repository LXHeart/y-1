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
    implementation(project(":platform-messaging"))
    implementation(project(":platform-database"))
    testImplementation(testFixtures(project(":platform-identity-assertion")))
    implementation(project(":platform-storage"))
    implementation(project(":platform-crypto"))
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-kafka")
    implementation("org.springframework.boot:spring-boot-starter-data-r2dbc")
    // 任务书 #36：游客试用 IP 限流（ReactiveStringRedisTemplate；经 platform-identity-assertion 已传递，此处显式声明）
    implementation("org.springframework.boot:spring-boot-starter-data-redis-reactive")
    implementation(libs.r2dbc.postgresql)
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("org.jsoup:jsoup:1.21.1")
    implementation("com.microsoft.playwright:playwright:1.55.0") {
        exclude(group = "com.microsoft.playwright", module = "driver-bundle")
        exclude(group = "org.junit.jupiter")
        exclude(group = "org.opentest4j")
        exclude(group = "org.slf4j", module = "slf4j-simple")
    }
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.postgresql:postgresql:42.7.12")

    testImplementation(libs.spring.boot.test)
    testImplementation(libs.reactor.test)
    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.r2dbc)
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.postgresql:postgresql:42.7.12")
    testImplementation("org.wiremock:wiremock-standalone:3.9.2")
    // S3GeneratedImageStoreIT 直连 testcontainers MinIO 构造 S3ObjectStorageAdapter（仅测试）。
    testImplementation(platform(libs.aws.bom))
    testImplementation(libs.aws.s3)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

// Keep the server-authoritative snapshot rules identical to the frontend contract.
tasks.processResources {
    from(rootProject.file("../contracts/platform-format-rules.json")) {
        into("contracts")
    }
    // 任务书 #34 / ADR-D16：内容安全词库契约（版本化，镜像 platform-format-rules 机制）。
    from(rootProject.file("../contracts/content-safety-lexicon.json")) {
        into("contracts")
    }
    // 任务书 #35：热点行业/城市/内容类型确定性分类词表。
    from(rootProject.file("../contracts/hot-topic-taxonomy.json")) {
        into("contracts")
    }
}

// Keep the 194MB all-platform native driver bundle out; the container supplies a matching Node driver and Chromium.
configurations.testRuntimeClasspath {
    exclude(group = "com.microsoft.playwright", module = "driver-bundle")
}

tasks.jar {
    enabled = false
}

tasks.bootJar {
    archiveFileName = "intelligence-service.jar"
}
