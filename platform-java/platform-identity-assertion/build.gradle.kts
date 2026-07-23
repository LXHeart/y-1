plugins {
    `java-library`
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

dependencies {
    implementation(platform(libs.spring.boot.dependencies))
    // 断言 payload 用 Jackson 序列化（Instant 走 jsr310）；implementation 暴露给消费者（两消费者本就依赖 Jackson，BOM 统一版本）。
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
    // autoconfigure/@ConfigurationProperties 注解编译期可见，运行时由消费者提供 Boot（与 platform-storage 一致）。
    compileOnly("org.springframework.boot:spring-boot-autoconfigure")
    compileOnly("org.springframework.boot:spring-boot")

    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.spring.boot.test)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
