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
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.springframework.boot:spring-boot-starter-kafka")
    implementation("org.springframework.boot:spring-boot-starter-data-r2dbc")
    implementation(libs.temporal.spring.boot.starter)
    implementation(libs.temporal.opentracing)
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation(libs.r2dbc.postgresql)
    implementation("org.postgresql:postgresql:42.7.12")

    testImplementation(libs.spring.boot.test)
    testImplementation(libs.reactor.test)
    testImplementation(libs.temporal.testing)
    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.r2dbc)
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
    archiveFileName = "trust-service.jar"
}
