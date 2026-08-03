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
    implementation(libs.bouncycastle)
    // SLF4J API（由消费者提供实现）
    implementation("org.slf4j:slf4j-api")
    // 库保持轻量：autoconfigure/spi 编译期可见，由消费者在运行时提供 Boot。
    compileOnly("org.springframework.boot:spring-boot-autoconfigure")
    compileOnly("org.springframework.boot:spring-boot")
    compileOnly("jakarta.annotation:jakarta.annotation-api")

    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.spring.boot.test)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.testcontainers:junit-jupiter")
}

tasks.test {
    useJUnitPlatform()
}
