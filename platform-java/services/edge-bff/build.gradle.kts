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
    implementation(platform(libs.spring.cloud.dependencies))
    implementation(libs.spring.cloud.gateway.webflux)
    implementation(libs.spring.boot.actuator)
    implementation(libs.spring.boot.opentelemetry)
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")
    // HLD 7.4 内部身份断言：共享契约（signer/verifier）+ 直读 session 表（R2DBC）。
    implementation(project(":platform-identity-assertion"))
    testImplementation(testFixtures(project(":platform-identity-assertion")))
    implementation("org.springframework.boot:spring-boot-starter-data-r2dbc")
    implementation(libs.r2dbc.postgresql)
    implementation("com.fasterxml.jackson.core:jackson-databind")

    testImplementation(libs.spring.boot.test)
    testImplementation(libs.reactor.test)
    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.r2dbc)
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.postgresql:postgresql:42.7.12")
}

tasks.test {
    useJUnitPlatform()
    systemProperty("BILIBILI_PROXY_TOKEN_SECRET", "test-bilibili-proxy-secret-32-chars")
    systemProperty("DOUYIN_PROXY_TOKEN_SECRET", "test-douyin-proxy-secret-32-chars!!")
    systemProperty("PUBLIC_BACKEND_ORIGIN", "https://media.test.example")
}

tasks.jar {
    enabled = false
}

tasks.bootJar {
    archiveFileName = "edge-bff.jar"
}
