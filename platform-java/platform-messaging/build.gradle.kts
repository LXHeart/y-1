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
    // 公共 API 直接暴露 DatabaseClient / KafkaTemplate / MeterRegistry 类型，
    // 与 platform-http 暴露 webflux 同口径（消费者经 starter 自带运行时）。
    api("org.springframework:spring-r2dbc")
    api("org.springframework.kafka:spring-kafka")
    api("io.micrometer:micrometer-core")
    implementation("io.projectreactor:reactor-core")
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("org.slf4j:slf4j-api")

    testImplementation(libs.spring.boot.test)
    testImplementation(libs.reactor.test)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
