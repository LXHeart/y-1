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
    implementation(project(":platform-identity-assertion"))
    implementation(project(":platform-storage"))
    implementation(project(":platform-crypto"))
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-kafka")
    implementation("org.springframework.boot:spring-boot-starter-data-r2dbc")
    implementation(libs.r2dbc.postgresql)
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("org.jsoup:jsoup:1.21.1")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.postgresql:postgresql")

    testImplementation(libs.spring.boot.test)
    testImplementation(libs.reactor.test)
    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.r2dbc)
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.postgresql:postgresql")
    testImplementation("org.wiremock:wiremock-standalone:3.9.2")
    // S3GeneratedImageStoreIT 直连 testcontainers MinIO 构造 S3ObjectStorageAdapter（仅测试）。
    testImplementation(platform(libs.aws.bom))
    testImplementation(libs.aws.s3)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

tasks.jar {
    enabled = false
}

tasks.bootJar {
    archiveFileName = "intelligence-service.jar"
}
